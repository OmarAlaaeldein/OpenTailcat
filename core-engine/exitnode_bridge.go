package engine

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"tailscale.com/tailcfg"
	"tailscale.com/types/key"

	"com.tailcat.vpn/engine/exitnode"
)

// Exit-node bridge: the phone as a gateway. The node itself lives in package
// exitnode (the only place allowed to open OS sockets for forwarded traffic);
// this file is only the gomobile surface and the lifecycle glue.
//
// Mutual exclusion with the client tunnel is enforced here, natively, and not
// only in the UI: with the client's default route installed the node's own
// outbound dials would be captured and loop back through the phone.
// Prepare refuses while the node runs, and StartExitNode refuses while the
// client engine is anywhere past STOPPED/FAILED. Both decisions are taken
// under globalCore.mu, so they cannot interleave.

const (
	maxExitNodeConfigBytes = 2048
	exitNodeStartTimeout   = 30 * time.Second
	exitNodeStopWait       = 10 * time.Second
)

// Self-hosted relay defaults. A region ID of 900+ is the convention for
// private DERP regions, so a custom relay can never be mistaken for one of the
// public regions (301-304) a token might otherwise name.
const (
	customRelayRegionID  = 900
	defaultRelayDERPPort = 443
	defaultRelaySTUNPort = 3478
)

// exitNodeClaim is true from the moment StartExitNode wins the engine until
// the node has ended. It is read and written only with globalCore.mu held.
var exitNodeClaim atomic.Bool

// exitNodeConfigHook lets tests substitute fields the bridge does not expose
// (for example a local DERP region). Nil in production.
var exitNodeConfigHook func(*exitnode.Config)

// exitNodeStartHook runs after the bridge has released its lock and before
// the node starts. Tests use it to hold a start open. Nil in production.
var exitNodeStartHook func(context.Context)

// exitNodeControlSeq orders start and stop requests. A stop only cancels the
// starts issued before it, and a start only publishes if no later stop won.
var exitNodeControlSeq atomic.Uint64

var exitHost struct {
	mu sync.Mutex
	// node is kept after it ends, so stats can still report EXPIRED or STOPPED
	// instead of an error. The next start replaces it.
	node *exitnode.Node
	// starting is true while one StartExitNode is building a node. The network
	// work happens without mu held, so stats and approvals never wait for it.
	starting    bool
	startSeq    uint64
	startCancel context.CancelFunc
	startDone   chan struct{}
	// stopSeq is the newest stop request. A start issued before it must not
	// publish, and a stop that lost to a newer start must not close it.
	stopSeq uint64
	// nodeSeq is the request that published node. A stop older than it leaves
	// the session alone.
	nodeSeq uint64
	// swapped records the protector installed for the node so it can be
	// undone, and what it replaced.
	swapped  bool
	previous SocketProtector
	ours     SocketProtector
}

// exitNodeConfigJSON is what Kotlin sends: {"ttlSeconds":3600,"admission":
// "approval"}, or {"noExpiry":true,"admission":"approval"} for a share that
// lasts until stopped (approval only; ttlSeconds then absent or 0). "relay"
// is optional; without it the nearest public DERP region is used.
type exitNodeConfigJSON struct {
	TTLSeconds int64              `json:"ttlSeconds"`
	NoExpiry   bool               `json:"noExpiry"`
	Admission  string             `json:"admission"`
	Relay      *exitNodeRelayJSON `json:"relay"`
}

// exitNodeRelayJSON points a share at its own DERP server instead of the
// nearest public one. The region is embedded in the token this node mints, so
// a guest needs no configuration of its own: pairing is enough.
//
// A relay only ever sees WireGuard ciphertext, but a public relay still sees
// the metadata (which keys talk, when, how much), and the nearest public
// region is chosen from the host's latency alone. Naming a private relay
// keeps that first-party and lets the operator place it for the pair.
type exitNodeRelayJSON struct {
	// Hostname is the relay's name. It must match the certificate the relay
	// serves; it is not a URL and takes no scheme or path.
	Hostname string `json:"hostname"`
	// DERPPort and STUNPort default to 443 and 3478 when zero.
	DERPPort int `json:"derpPort"`
	STUNPort int `json:"stunPort"`
}

// region validates the relay and builds the DERP region the node advertises.
// The endpoint policy mirrors the token parser's, so a configuration that
// would mint a token this app then refuses is rejected here rather than at
// pairing time on the guest.
func (r *exitNodeRelayJSON) region() (*tailcfg.DERPRegion, error) {
	host := strings.TrimSpace(r.Hostname)
	if host == "" {
		return nil, errors.New("relay hostname is required")
	}
	if strings.ContainsAny(host, "/?#@: \t\\") {
		return nil, errors.New("relay hostname must be a bare host name, not a URL or host:port")
	}
	// An IP literal can pass the token parser's endpoint policy, but the relay
	// would then have to serve a certificate with that IP in it, which no
	// public CA issues. Reject it here so the failure lands on the switch that
	// configured it rather than on every guest at pairing time.
	if _, err := netip.ParseAddr(host); err == nil {
		return nil, errors.New("relay hostname must be a DNS name, not an IP address")
	}
	// The token parser applies the same endpoint policy to the region this
	// function builds, so a rejection here would otherwise surface at pairing
	// time on the guest instead of at the switch that caused it.
	if err := rejectUnsafeDERPEndpoint("hostname", host); err != nil {
		return nil, fmt.Errorf("relay hostname %q is not a usable DNS name", host)
	}
	derpPort, stunPort := r.DERPPort, r.STUNPort
	if derpPort == 0 {
		derpPort = defaultRelayDERPPort
	}
	if stunPort == 0 {
		stunPort = defaultRelaySTUNPort
	}
	if derpPort < 1 || derpPort > 65535 {
		return nil, errors.New("relay derpPort must be between 1 and 65535")
	}
	if stunPort < 1 || stunPort > 65535 {
		return nil, errors.New("relay stunPort must be between 1 and 65535")
	}
	return &tailcfg.DERPRegion{
		RegionID:   customRelayRegionID,
		RegionCode: "custom",
		RegionName: "Custom relay",
		Nodes: []*tailcfg.DERPNode{{
			Name:     "900a",
			RegionID: customRelayRegionID,
			HostName: host,
			DERPPort: derpPort,
			STUNPort: stunPort,
		}},
	}, nil
}

// allowAllProtector accepts every socket. The client tunnel is not running
// while the node is (see above), so there is no VPN to route around and
// nothing for VpnService.protect to do; the hook only needs to not refuse.
type allowAllProtector struct{}

func (allowAllProtector) Protect(int) bool { return true }

func parseExitNodeConfig(raw string) (exitnode.Config, error) {
	if len(raw) > maxExitNodeConfigBytes {
		return exitnode.Config{}, errors.New("exit node config is too large")
	}
	var in exitNodeConfigJSON
	dec := json.NewDecoder(bytes.NewReader([]byte(raw)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&in); err != nil {
		return exitnode.Config{}, errors.New("exit node config is not valid")
	}
	if dec.More() {
		return exitnode.Config{}, errors.New("exit node config has trailing data")
	}
	var cfg exitnode.Config
	if in.NoExpiry {
		if in.TTLSeconds != 0 {
			return exitnode.Config{}, errors.New("exit node noExpiry takes no ttlSeconds")
		}
		cfg.NoExpiry = true
	} else {
		// Range-check the integer first: converting an enormous value to a
		// Duration would overflow and wrap past the bound below.
		maxSec := int64(exitnode.MaxTTL / time.Second)
		if in.TTLSeconds <= 0 || in.TTLSeconds > maxSec {
			return exitnode.Config{}, fmt.Errorf("exit node ttlSeconds must be between 1 and %d", maxSec)
		}
		cfg.TTL = time.Duration(in.TTLSeconds) * time.Second
	}
	switch in.Admission {
	case "approval":
		cfg.Admission = exitnode.AdmitApproval
	case "open":
		cfg.Admission = exitnode.AdmitOpen
	default:
		return exitnode.Config{}, errors.New("exit node admission must be \"approval\" or \"open\"")
	}
	if cfg.NoExpiry && cfg.Admission != exitnode.AdmitApproval {
		return exitnode.Config{}, errors.New("a share without a time limit requires approval")
	}
	if in.Relay != nil {
		region, err := in.Relay.region()
		if err != nil {
			return exitnode.Config{}, err
		}
		cfg.Region = region
	}
	return cfg, nil
}

// StartExitNode starts sharing this device's connection and returns the
// session's token. The token is a credential: the caller must not log it.
// Exported to Java as Engine.startExitNode(String).
func StartExitNode(configJSON string) (token string, err error) {
	defer recoverExported("StartExitNode", &err)
	cfg, err := parseExitNodeConfig(configJSON)
	if err != nil {
		return "", err
	}
	if exitNodeConfigHook != nil {
		exitNodeConfigHook(&cfg)
	}
	seq := exitNodeControlSeq.Add(1)

	// Wait out a start already in flight, but never hold the lock across the
	// network: stats and approvals keep working while a node is starting.
	exitHost.mu.Lock()
	for {
		for exitHost.starting {
			done := exitHost.startDone
			exitHost.mu.Unlock()
			if done != nil {
				<-done
			}
			exitHost.mu.Lock()
		}
		// A stop issued after this start wins: the user asked to stop while
		// this request was queued, so do not bring a new session up behind
		// their back.
		if exitHost.stopSeq > seq {
			exitHost.mu.Unlock()
			return "", errors.New("exit node start was cancelled")
		}
		node := exitHost.node
		if node == nil || node.State() != exitnode.StateRunning {
			break
		}
		if exitHost.nodeSeq > exitHost.stopSeq {
			exitHost.mu.Unlock()
			return "", errors.New("exit node is already running")
		}
		// A stop that predates this start is closing that node. Wait for it
		// to finish instead of reporting "already running" for a session the
		// user already ended.
		exitHost.mu.Unlock()
		select {
		case <-node.Done():
		case <-time.After(exitNodeStopWait):
			return "", errors.New("exit node is still stopping")
		}
		exitHost.mu.Lock()
		// The node is gone; free the claim now rather than racing the stop's
		// own release, which is idempotent.
		if exitHost.node == node {
			releaseExitNodeLocked()
		}
	}

	globalCore.mu.Lock()
	idle := (globalCore.state == StateStopped || globalCore.state == StateFailed) && !globalCore.stopping
	if idle && !exitNodeClaim.Load() {
		exitNodeClaim.Store(true)
	} else {
		idle = false
	}
	globalCore.mu.Unlock()
	if !idle {
		exitHost.mu.Unlock()
		return "", errors.New("the VPN engine is active; disconnect before sharing")
	}

	installExitNodeProtectorLocked()
	ctx, cancel := context.WithTimeout(context.Background(), exitNodeStartTimeout)
	defer cancel()
	done := make(chan struct{})
	exitHost.starting = true
	exitHost.startSeq = seq
	exitHost.startCancel = cancel
	exitHost.startDone = done
	exitHost.mu.Unlock()

	// A panic in the native start must not leave the bridge "starting"
	// forever with the engine claimed. recoverExported turns the re-panic
	// into the exported function's error.
	defer func() {
		if r := recover(); r != nil {
			exitHost.mu.Lock()
			if exitHost.starting && exitHost.startSeq == seq {
				exitHost.starting = false
				exitHost.startCancel = nil
				exitHost.startDone = nil
				close(done)
				releaseExitNodeLocked()
			}
			exitHost.mu.Unlock()
			panic(r)
		}
	}()

	if exitNodeStartHook != nil {
		exitNodeStartHook(ctx)
	}
	node, startErr := exitnode.Start(ctx, cfg)
	cancel()
	if startErr == nil {
		token, startErr = node.Token()
	}

	exitHost.mu.Lock()
	if startErr == nil && exitHost.stopSeq > seq {
		// Stop arrived while the node was being built. Discard it rather than
		// publish a session the user has already ended.
		startErr = errors.New("exit node start was cancelled")
	}
	if startErr != nil {
		if node != nil {
			_ = node.Close()
		}
		exitHost.starting = false
		exitHost.startCancel = nil
		exitHost.startDone = nil
		close(done)
		releaseExitNodeLocked()
		exitHost.mu.Unlock()
		return "", startErr
	}
	exitHost.node = node
	exitHost.nodeSeq = seq
	exitHost.starting = false
	exitHost.startCancel = nil
	exitHost.startDone = nil
	close(done)

	// The node can end by itself (expiry). Free the engine when it does.
	go func() {
		<-node.Done()
		exitHost.mu.Lock()
		defer exitHost.mu.Unlock()
		if exitHost.node == node {
			releaseExitNodeLocked()
		}
	}()
	exitHost.mu.Unlock()
	return token, nil
}

// installExitNodeProtectorLocked makes socket creation succeed on Android
// while the node runs. exitHost.mu must be held.
func installExitNodeProtectorLocked() {
	protectorMu.Lock()
	exitHost.previous = currentProtector
	exitHost.ours = allowAllProtector{}
	currentProtector = exitHost.ours
	exitHost.swapped = true
	protectorMu.Unlock()
	hookAndroidProtect()
}

// releaseExitNodeLocked gives the engine back. It is idempotent and must be
// called with exitHost.mu held.
func releaseExitNodeLocked() {
	if exitHost.swapped {
		protectorMu.Lock()
		// Restore only if nothing else replaced ours in the meantime.
		if currentProtector == exitHost.ours {
			currentProtector = exitHost.previous
		}
		protectorMu.Unlock()
		exitHost.swapped = false
		exitHost.previous, exitHost.ours = nil, nil
	}
	globalCore.mu.Lock()
	exitNodeClaim.Store(false)
	globalCore.mu.Unlock()
}

// StopExitNode ends sharing and discards the session keys, which revokes every
// token it issued. Idempotent. Exported as Engine.stopExitNode().
func StopExitNode() (err error) {
	defer recoverExported("StopExitNode", &err)
	seq := exitNodeControlSeq.Add(1)

	exitHost.mu.Lock()
	if seq > exitHost.stopSeq {
		exitHost.stopSeq = seq
	}
	// Cancel only a start issued before this stop. A newer start is a new
	// session and must survive.
	if exitHost.starting && exitHost.startSeq < seq && exitHost.startCancel != nil {
		exitHost.startCancel()
	}
	done := exitHost.startDone
	exitHost.mu.Unlock()

	if done != nil {
		<-done
	}

	exitHost.mu.Lock()
	// A newer start may have won the lock while this stop waited. It owns the
	// engine now; leave it alone.
	if (exitHost.starting && exitHost.startSeq > seq) || exitHost.nodeSeq > seq {
		exitHost.mu.Unlock()
		return nil
	}
	if exitHost.node != nil {
		err = exitHost.node.Close()
	}
	releaseExitNodeLocked()
	exitHost.mu.Unlock()
	return err
}

// ExitNodeStatsJSON returns the node's current view (schema in
// testdata/exitnode_stats.json). After the node ends it keeps reporting its
// final state. Exported as Engine.exitNodeStatsJSON().
func ExitNodeStatsJSON() (out string, err error) {
	defer recoverExported("ExitNodeStatsJSON", &err)
	node, err := currentExitNode()
	if err != nil {
		return "", err
	}
	return node.StatsJSON()
}

// ExitNodeApprove admits a pending device. Exported as Engine.exitNodeApprove(String).
func ExitNodeApprove(deviceKey string) (err error) {
	defer recoverExported("ExitNodeApprove", &err)
	node, k, err := runningNodeAndKey(deviceKey)
	if err != nil {
		return err
	}
	return node.Approve(k)
}

// ExitNodeDeny refuses a pending device for the rest of the session.
func ExitNodeDeny(deviceKey string) (err error) {
	defer recoverExported("ExitNodeDeny", &err)
	node, k, err := runningNodeAndKey(deviceKey)
	if err != nil {
		return err
	}
	node.Deny(k)
	return nil
}

// ExitNodeRevoke withdraws a device's access and drops its connection.
func ExitNodeRevoke(deviceKey string) (revoked bool, err error) {
	defer recoverExported("ExitNodeRevoke", &err)
	node, k, err := runningNodeAndKey(deviceKey)
	if err != nil {
		return false, err
	}
	return node.Revoke(k), nil
}

func currentExitNode() (*exitnode.Node, error) {
	exitHost.mu.Lock()
	defer exitHost.mu.Unlock()
	if exitHost.node == nil {
		return nil, errors.New("exit node was never started")
	}
	return exitHost.node, nil
}

func runningNodeAndKey(deviceKey string) (*exitnode.Node, key.NodePublic, error) {
	node, err := currentExitNode()
	if err != nil {
		return nil, key.NodePublic{}, err
	}
	if node.State() != exitnode.StateRunning {
		return nil, key.NodePublic{}, errors.New("exit node is not running")
	}
	k, err := exitnode.ParseDeviceKey(deviceKey)
	if err != nil {
		return nil, key.NodePublic{}, err
	}
	return node, k, nil
}
