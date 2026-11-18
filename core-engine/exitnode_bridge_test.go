package engine

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"tailscale.com/derp/derpserver"
	"tailscale.com/net/stun"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"

	"com.tailcat.vpn/engine/exitnode"
)

// localDERP serves a DERP relay and STUN on loopback so the bridge can start a
// real node without the Internet. Test-only.
func localDERP(t *testing.T) *tailcfg.DERPRegion {
	t.Helper()
	d := derpserver.New(key.NewNode(), logger.Discard)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewUnstartedServer(derpserver.Handler(d))
	srv.Listener = ln
	srv.Config.TLSNextProto = make(map[string]func(*http.Server, *tls.Conn, http.Handler))
	srv.StartTLS()
	t.Cleanup(srv.Close)
	uln, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { uln.Close() })
	go func() {
		var buf [1500]byte
		for {
			n, src, err := uln.ReadFromUDPAddrPort(buf[:])
			if err != nil {
				return
			}
			if txid, err := stun.ParseBindingRequest(buf[:n]); err == nil {
				uln.WriteToUDPAddrPort(stun.Response(txid, src), src)
			}
		}
	}()
	return &tailcfg.DERPRegion{
		RegionID: 1, RegionCode: "D",
		Nodes: []*tailcfg.DERPNode{{
			Name: "t1", RegionID: 1, HostName: "T", IPv4: "127.0.0.1", IPv6: "none",
			STUNPort:         uln.LocalAddr().(*net.UDPAddr).Port,
			DERPPort:         ln.Addr().(*net.TCPAddr).Port,
			InsecureForTests: true,
		}},
	}
}

// useLocalDERP points the bridge at a local relay for one test and guarantees
// the node is stopped afterwards.
func useLocalDERP(t *testing.T) {
	t.Helper()
	region := localDERP(t)
	exitNodeConfigHook = func(c *exitnode.Config) { c.Region = region }
	t.Cleanup(func() {
		exitNodeConfigHook = nil
		_ = StopExitNode()
	})
}

const approvalConfig = `{"ttlSeconds":3600,"admission":"approval"}`

func TestParseExitNodeConfig(t *testing.T) {
	good, err := parseExitNodeConfig(approvalConfig)
	if err != nil || good.Admission != exitnode.AdmitApproval || good.TTL.Hours() != 1 {
		t.Fatalf("good config: %+v, %v", good, err)
	}
	open, err := parseExitNodeConfig(`{"ttlSeconds":60,"admission":"open"}`)
	if err != nil || open.Admission != exitnode.AdmitOpen {
		t.Fatalf("open config: %+v, %v", open, err)
	}
	for _, raw := range []string{
		`{"noExpiry":true,"admission":"approval"}`,
		`{"noExpiry":true,"ttlSeconds":0,"admission":"approval"}`,
	} {
		unlimited, err := parseExitNodeConfig(raw)
		if err != nil || !unlimited.NoExpiry || unlimited.TTL != 0 || unlimited.Admission != exitnode.AdmitApproval {
			t.Fatalf("no-limit config %s: %+v, %v", raw, unlimited, err)
		}
	}
	for name, raw := range map[string]string{
		"empty":           ``,
		"not json":        `ttl=3600`,
		"no admission":    `{"ttlSeconds":3600}`,
		"unknown mode":    `{"ttlSeconds":3600,"admission":"anyone"}`,
		"zero ttl":        `{"ttlSeconds":0,"admission":"approval"}`,
		"negative ttl":    `{"ttlSeconds":-5,"admission":"approval"}`,
		"over a day":      `{"ttlSeconds":86401,"admission":"approval"}`,
		"unknown field":   `{"ttlSeconds":3600,"admission":"approval","allowLocal":true}`,
		"trailing object": approvalConfig + `{}`,
		"oversized":       `{"ttlSeconds":3600,"admission":"approval","x":"` + strings.Repeat("a", 2000) + `"}`,
		"overflowing ttl": `{"ttlSeconds":9223372036854775807,"admission":"approval"}`,
		// No time limit: never with a TTL, never with open admission.
		"no limit + ttl":   `{"noExpiry":true,"ttlSeconds":3600,"admission":"approval"}`,
		"no limit + open":  `{"noExpiry":true,"admission":"open"}`,
		"no limit is bool": `{"noExpiry":"yes","admission":"approval"}`,
		"explicit false":   `{"noExpiry":false,"admission":"approval"}`,
	} {
		if _, err := parseExitNodeConfig(raw); err == nil {
			t.Errorf("%s: accepted %q", name, raw)
		}
	}
}

// The bridge must not be able to switch on the host-network escape hatch.
func TestBridgeConfigCannotAllowLocalDestinations(t *testing.T) {
	cfg, err := parseExitNodeConfig(approvalConfig)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.AllowLocalDestinations {
		t.Fatal("bridge config enabled AllowLocalDestinations")
	}
}

func TestPrepareRefusedWhileSharing(t *testing.T) {
	globalCore.mu.Lock()
	exitNodeClaim.Store(true)
	globalCore.mu.Unlock()
	t.Cleanup(func() {
		globalCore.mu.Lock()
		exitNodeClaim.Store(false)
		globalCore.mu.Unlock()
	})

	err := Prepare(officialTestToken(t))
	if err == nil || !strings.Contains(err.Error(), "exit node is active") {
		t.Fatalf("Prepare = %v, want a refusal naming the active exit node", err)
	}
	if _, state := statsState(t); state != StateStopped {
		t.Fatalf("refused Prepare left the engine in %v", state)
	}
}

func TestStartExitNodeRefusedWhileTheClientEngineIsActive(t *testing.T) {
	for _, st := range []EngineState{StatePreparing, StatePrepared, StateAttaching, StateRunning, StateStopping} {
		globalCore.mu.Lock()
		prev := globalCore.state
		globalCore.state = st
		globalCore.mu.Unlock()

		_, err := StartExitNode(approvalConfig)

		globalCore.mu.Lock()
		globalCore.state = prev
		claimed := exitNodeClaim.Load()
		globalCore.mu.Unlock()

		if err == nil {
			_ = StopExitNode()
			t.Fatalf("StartExitNode succeeded with the client engine in %v", st)
		}
		if claimed {
			t.Fatalf("a refused start left the engine claimed (state %v)", st)
		}
	}
}

func TestStartExitNodeValidatesBeforeTouchingTheEngine(t *testing.T) {
	if _, err := StartExitNode(`{"ttlSeconds":0,"admission":"approval"}`); err == nil {
		t.Fatal("accepted a zero ttl")
	}
	if exitNodeClaim.Load() {
		t.Fatal("an invalid config claimed the engine")
	}
}

func TestExitNodeStatsBeforeStartIsAnError(t *testing.T) {
	exitHost.mu.Lock()
	exitHost.node = nil
	exitHost.mu.Unlock()
	if _, err := ExitNodeStatsJSON(); err == nil {
		t.Fatal("stats succeeded with no node")
	}
	if err := ExitNodeApprove("nodekey:" + strings.Repeat("ab", 32)); err == nil {
		t.Fatal("approve succeeded with no node")
	}
}

func TestExitNodeLifecycleThroughTheBridge(t *testing.T) {
	useLocalDERP(t)

	// A protector the client engine had installed must survive a share.
	sentinel := &recordingProtector{}
	protectorMu.Lock()
	prior := currentProtector
	currentProtector = sentinel
	protectorMu.Unlock()
	t.Cleanup(func() {
		protectorMu.Lock()
		currentProtector = prior
		protectorMu.Unlock()
	})

	token, err := StartExitNode(approvalConfig)
	if err != nil {
		t.Fatalf("StartExitNode: %v", err)
	}
	if !strings.HasPrefix(token, "tc") || len(token) < 40 {
		t.Fatalf("token has the wrong shape (%d chars)", len(token))
	}
	if !exitNodeClaim.Load() {
		t.Fatal("a running node does not hold the engine")
	}
	protectorMu.Lock()
	_, swapped := currentProtector.(allowAllProtector)
	protectorMu.Unlock()
	if !swapped {
		t.Fatal("transport protection was not made permissive for the node")
	}

	// A second start while running is refused and does not disturb the first.
	if _, err := StartExitNode(approvalConfig); err == nil {
		t.Fatal("a second StartExitNode succeeded")
	}
	if err := Prepare(officialTestToken(t)); err == nil {
		t.Fatal("Prepare succeeded while the node ran")
	}

	raw, err := ExitNodeStatsJSON()
	if err != nil {
		t.Fatal(err)
	}
	var stats struct {
		Schema  int    `json:"schema"`
		State   string `json:"state"`
		Pending []any  `json:"pending"`
	}
	if err := json.Unmarshal([]byte(raw), &stats); err != nil {
		t.Fatal(err)
	}
	if stats.Schema != 1 || stats.State != "RUNNING" {
		t.Fatalf("stats = %s", raw)
	}

	// Device actions validate their input and reach the node.
	if err := ExitNodeApprove("not-a-key"); err == nil {
		t.Fatal("approved a malformed key")
	}
	good := key.NewNode().Public().String()
	if err := ExitNodeApprove(good); err != nil {
		t.Fatal(err)
	}
	if err := ExitNodeDeny(key.NewNode().Public().String()); err != nil {
		t.Fatal(err)
	}
	if ok, err := ExitNodeRevoke(good); err != nil || !ok {
		t.Fatalf("Revoke = %v, %v", ok, err)
	}

	if err := StopExitNode(); err != nil {
		t.Fatal(err)
	}
	if exitNodeClaim.Load() {
		t.Fatal("a stopped node still holds the engine")
	}
	protectorMu.Lock()
	restored := currentProtector == SocketProtector(sentinel)
	protectorMu.Unlock()
	if !restored {
		t.Fatal("the previous socket protector was not restored")
	}

	// After stopping, stats report the final state and actions are refused.
	raw, err = ExitNodeStatsJSON()
	if err != nil || !strings.Contains(raw, `"state":"STOPPED"`) {
		t.Fatalf("stats after stop = %q, %v", raw, err)
	}
	if err := ExitNodeApprove(good); err == nil {
		t.Fatal("approved a device on a stopped node")
	}
	if err := StopExitNode(); err != nil {
		t.Fatalf("second StopExitNode: %v", err)
	}

	// The engine is free again: sharing can restart.
	if _, err := StartExitNode(approvalConfig); err != nil {
		t.Fatalf("restart after stop: %v", err)
	}
}

// A start can take seconds (relay discovery). Stats and approvals must not
// wait for it: only the start's own state is locked while the network runs.
func TestStatsDoesNotWaitForAnInFlightStart(t *testing.T) {
	useLocalDERP(t)
	entered := make(chan struct{})
	release := make(chan struct{})
	exitNodeStartHook = func(context.Context) {
		close(entered)
		<-release
	}
	t.Cleanup(func() { exitNodeStartHook = nil })

	started := make(chan error, 1)
	go func() {
		_, err := StartExitNode(approvalConfig)
		started <- err
	}()
	<-entered

	statsDone := make(chan error, 1)
	go func() {
		_, err := ExitNodeStatsJSON()
		statsDone <- err
	}()
	select {
	case <-statsDone:
		// Either an answer or "was never started"; both are immediate.
	case <-time.After(2 * time.Second):
		t.Fatal("stats waited for the in-flight start")
	}

	close(release)
	if err := <-started; err != nil {
		t.Fatalf("StartExitNode: %v", err)
	}
}

// Stop during startup must cancel the start, not block until its 30 s timeout.
func TestStopCancelsAnInFlightStart(t *testing.T) {
	useLocalDERP(t)
	entered := make(chan struct{})
	exitNodeStartHook = func(ctx context.Context) {
		close(entered)
		<-ctx.Done()
	}
	t.Cleanup(func() { exitNodeStartHook = nil })

	started := make(chan error, 1)
	go func() {
		_, err := StartExitNode(approvalConfig)
		started <- err
	}()
	<-entered

	begin := time.Now()
	if err := StopExitNode(); err != nil {
		t.Fatalf("StopExitNode: %v", err)
	}
	if elapsed := time.Since(begin); elapsed > 2*time.Second {
		t.Fatalf("StopExitNode took %v with a start in flight", elapsed)
	}
	if err := <-started; err == nil {
		t.Fatal("a start cancelled by Stop reported success")
	}
	if exitNodeClaim.Load() {
		t.Fatal("a cancelled start left the engine claimed")
	}

	// The stop must not poison the next, deliberate start.
	exitNodeStartHook = nil
	if _, err := StartExitNode(approvalConfig); err != nil {
		t.Fatalf("start after a cancelled start: %v", err)
	}
}

type recordingProtector struct{}

func (*recordingProtector) Protect(int) bool { return true }
