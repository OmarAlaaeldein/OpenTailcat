package engine

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/tailscale/tailcat"
)

type EngineState int

const (
	StateStopped EngineState = iota
	StatePreparing
	StatePrepared
	StateAttaching
	StateRunning
	StateStopping
	StateFailed
)

func (s EngineState) String() string {
	switch s {
	case StateStopped:
		return "STOPPED"
	case StatePreparing:
		return "PREPARING"
	case StatePrepared:
		return "PREPARED"
	case StateAttaching:
		return "ATTACHING"
	case StateRunning:
		return "RUNNING"
	case StateStopping:
		return "STOPPING"
	case StateFailed:
		return "FAILED"
	default:
		return "STOPPED"
	}
}

type session struct {
	id        int64
	ctx       context.Context
	cancel    context.CancelFunc
	token     *ParsedToken
	client    preparedClient
	bridge    *TunBridge
	transport string
	rttMs     int64
	tcpOnly   bool
}

type TailcatCore struct {
	mu         sync.Mutex
	state      EngineState
	sess       *session
	sessionID  int64
	lastErr    string
	healthUnix atomic.Int64
	pendingDNS atomic.Pointer[DNSConfig]
	stopping   bool
	stopWait   chan struct{}
}

var globalCore = &TailcatCore{}

const stopWaitTimeout = 3 * time.Second

func (c *TailcatCore) markFailed(sess *session, err error) {
	if sess == nil {
		return
	}
	c.mu.Lock()
	if c.sess != sess || (c.state != StateRunning && c.state != StateAttaching) {
		c.mu.Unlock()
		return
	}
	c.state = StateFailed
	if err != nil {
		c.lastErr = err.Error()
	}
	c.healthUnix.Store(0)
	bridge := sess.bridge
	c.mu.Unlock()

	sess.cancel()
	if bridge != nil && bridge.tunFile != nil {
		_ = bridge.tunFile.Close()
	}
}

func closeSession(sess *session) {
	if sess == nil {
		return
	}
	sess.cancel()
	if sess.bridge != nil {
		_ = sess.bridge.Stop()
		sess.bridge = nil
	}
	if sess.client != nil {
		_ = sess.client.Close()
		sess.client = nil
	}
}

func Prepare(tokenStr string) error {
	pt, err := ParseToken(tokenStr)
	if err != nil {
		return fmt.Errorf("token rejected: %w", err)
	}
	if !pt.IsConnectable() {
		return fmt.Errorf("token classification %s cannot be used for connection: %s", pt.Classification, pt.ErrorMessage)
	}

	globalCore.mu.Lock()
	for globalCore.stopping {
		ch := globalCore.stopWait
		globalCore.mu.Unlock()
		if ch != nil {
			<-ch
		}
		globalCore.mu.Lock()
	}
	prev := globalCore.sess
	globalCore.sess = nil
	ctx, cancel := context.WithCancel(context.Background())
	sess := &session{ctx: ctx, cancel: cancel, token: pt}
	globalCore.state = StatePreparing
	globalCore.sess = sess
	globalCore.lastErr = ""
	globalCore.mu.Unlock()

	closeSession(prev)

	client := newTailcatClient(tailcat.ConnBlob(pt.RawToken))

	pingCtx, pingCancel := context.WithTimeout(sess.ctx, 10*time.Second)
	res, err := client.Ping(pingCtx)
	pingCancel()
	if err != nil {
		_ = client.Close()
		abandonPrepare(sess)
		return fmt.Errorf("gateway handshake failed: %w", err)
	}
	if res.Latency <= 0 {
		_ = client.Close()
		abandonPrepare(sess)
		return errors.New("invalid reachability latency from gateway")
	}
	tcpOnly := !client.HasServerCap(tailcat.CapExitUDP)

	transport := "DERP_RELAY"
	rttMs := res.Latency.Milliseconds()
	discoCtx, discoCancel := context.WithTimeout(sess.ctx, 4*time.Second)
	if disco, discoErr := client.DiscoPing(discoCtx); discoErr == nil {
		if disco.Endpoint != "" {
			transport = "DIRECT_P2P"
		}
		if disco.LatencySeconds > 0 {
			rttMs = int64(disco.LatencySeconds * 1_000)
		}
	}
	discoCancel()

	if sess.ctx.Err() != nil {
		_ = client.Close()
		abandonPrepare(sess)
		return sess.ctx.Err()
	}

	globalCore.mu.Lock()
	if globalCore.sess != sess || sess.ctx.Err() != nil {
		globalCore.mu.Unlock()
		_ = client.Close()
		return context.Canceled
	}
	globalCore.sessionID++
	sess.id = globalCore.sessionID
	sess.client = client
	sess.transport = transport
	sess.rttMs = rttMs
	sess.tcpOnly = tcpOnly
	globalCore.state = StatePrepared
	globalCore.mu.Unlock()

	netStateMu.Lock()
	if nm := client.NetMon(); nm != nil {
		activeMonitor = nm
	}
	netStateMu.Unlock()

	return nil
}

func abandonPrepare(sess *session) {
	globalCore.mu.Lock()
	if globalCore.sess == sess && globalCore.state == StatePreparing {
		globalCore.sess = nil
		globalCore.state = StateStopped
	}
	globalCore.mu.Unlock()
	if sess != nil {
		sess.cancel()
	}
}

func AttachTun(tunFD int) error {
	globalCore.mu.Lock()
	if globalCore.sess == nil || globalCore.sess.client == nil || globalCore.sess.token == nil {
		globalCore.mu.Unlock()
		return errors.New("cannot attach TUN: engine not prepared")
	}
	if globalCore.state != StatePrepared && globalCore.state != StateRunning {
		globalCore.mu.Unlock()
		return errors.New("cannot attach TUN: engine not prepared")
	}
	sess := globalCore.sess
	oldBridge := sess.bridge
	if oldBridge != nil {
		oldBridge.onPumpDead = nil
	}
	sess.bridge = nil
	globalCore.state = StateAttaching
	parentCtx := sess.ctx
	client := sess.client
	token := sess.token
	transport := sess.transport
	rttMs := sess.rttMs
	sessionID := sess.id
	tcpOnly := sess.tcpOnly
	dns := globalCore.pendingDNS.Load()
	globalCore.mu.Unlock()

	if oldBridge != nil {
		_ = oldBridge.Stop()
	}

	if parentCtx.Err() != nil {
		abandonAttach(sess, nil)
		return parentCtx.Err()
	}

	bridge, err := newTunBridge(tunFD, client, token, transport, rttMs, sessionID, parentCtx)
	if err != nil {
		abandonAttach(sess, nil)
		return fmt.Errorf("create tun bridge: %w", err)
	}
	bridge.tcpOnly = tcpOnly
	if dns != nil {
		bridge.SetDNSConfig(*dns)
	}
	bridge.onHealth = func() {
		globalCore.healthUnix.Store(time.Now().Unix())
	}
	bridge.onPumpDead = func(pumpErr error) {
		globalCore.mu.Lock()
		live := globalCore.sess == sess && sess.bridge == bridge
		globalCore.mu.Unlock()
		if live {
			globalCore.markFailed(sess, pumpErr)
		}
	}

	if err := bridge.Start(); err != nil {
		_ = bridge.Stop()
		abandonAttach(sess, nil)
		return fmt.Errorf("start tun bridge: %w", err)
	}

	globalCore.mu.Lock()
	if globalCore.sess != sess || sess.ctx.Err() != nil {
		globalCore.mu.Unlock()
		_ = bridge.Stop()
		return context.Canceled
	}
	sess.bridge = bridge
	globalCore.state = StateRunning
	globalCore.healthUnix.Store(time.Now().Unix())
	globalCore.mu.Unlock()
	return nil
}

func abandonAttach(sess *session, bridge *TunBridge) {
	if bridge != nil {
		_ = bridge.Stop()
	}
	globalCore.mu.Lock()
	if globalCore.sess == sess && (globalCore.state == StateAttaching || globalCore.state == StatePrepared) {
		globalCore.state = StatePrepared
	}
	globalCore.mu.Unlock()
}

func Stop() error {
	netStateMu.Lock()
	activeMonitor = nil
	netStateMu.Unlock()

	globalCore.mu.Lock()
	if globalCore.state == StateStopped && !globalCore.stopping {
		globalCore.pendingDNS.Store(nil)
		globalCore.mu.Unlock()
		return nil
	}
	if globalCore.stopping {
		ch := globalCore.stopWait
		globalCore.mu.Unlock()
		if ch != nil {
			<-ch
		}
		return nil
	}
	globalCore.stopping = true
	done := make(chan struct{})
	globalCore.stopWait = done
	sess := globalCore.sess
	globalCore.sess = nil
	globalCore.state = StateStopping
	globalCore.healthUnix.Store(0)
	globalCore.pendingDNS.Store(nil)
	globalCore.lastErr = ""
	globalCore.mu.Unlock()

	netStateMu.Lock()
	activeMonitor = nil
	netStateMu.Unlock()

	closeSession(sess)

	globalCore.mu.Lock()
	globalCore.state = StateStopped
	globalCore.stopping = false
	globalCore.stopWait = nil
	close(done)
	globalCore.mu.Unlock()
	return nil
}

func GetStatsJSON() string {
	globalCore.mu.Lock()
	state := globalCore.state
	sess := globalCore.sess
	sessionID := globalCore.sessionID
	lastErr := globalCore.lastErr
	var bridge *TunBridge
	if sess != nil {
		sessionID = sess.id
		bridge = sess.bridge
	}
	health := globalCore.healthUnix.Load()
	globalCore.mu.Unlock()

	if state != StateRunning && state != StateFailed {
		stats := EngineStats{
			Version:   2,
			SessionID: sessionID,
			State:     state.String(),
			Transport: "DISCONNECTED",
		}
		b, err := json.Marshal(stats)
		if err != nil {
			return `{"version":2,"state":"STOPPED","transport":"DISCONNECTED"}`
		}
		return string(b)
	}

	var stats EngineStats
	if bridge != nil {
		stats = bridge.GetStats()
	} else {
		stats = EngineStats{
			Version:   2,
			SessionID: sessionID,
			Transport: "DISCONNECTED",
		}
	}
	stats.Version = 2
	stats.SessionID = sessionID
	stats.State = state.String()
	stats.HealthUnixSec = health
	if state == StateFailed {
		stats.HealthUnixSec = 0
		if lastErr != "" {
			stats.EgressAuditError = lastErr
		}
	}
	bytes, err := json.Marshal(stats)
	if err != nil {
		return `{"version":2,"state":"ERROR","transport":"UNKNOWN"}`
	}
	return string(bytes)
}
