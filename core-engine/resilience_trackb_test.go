package engine

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"sync/atomic"
	"testing"

	"tailscale.com/ipn/ipnstate"
)

// TestDiscoFailureNoLongerKillsRelayedTunnel proves a failing DiscoPing does
// not mark the session FAILED: user traffic can flow over DERP while disco
// probes fail. Failures keep counting and disco goes stale honestly.
func TestDiscoFailureNoLongerKillsRelayedTunnel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	mock := &mockTunnelClient{
		discoPingFn: func(context.Context) (*ipnstate.PingResult, error) {
			return nil, errors.New("disco timeout on relayed path")
		},
	}
	bridge := &TunBridge{
		sessionID:  7,
		token:      &ParsedToken{RegionID: 1},
		client:     mock,
		ctx:        ctx,
		cancel:     cancel,
		transport:  "DERP_RELAY",
		rttMs:      40,
		rttSamples: []int64{40},
	}
	var pumpDead atomic.Int32
	bridge.setOnPumpDead(func(err error) { pumpDead.Add(1) })

	for i := 0; i < 5; i++ {
		bridge.sampleLiveRTT()
	}

	if got := pumpDead.Load(); got != 0 {
		t.Fatalf("disco failures must not report pump death, got %d calls", got)
	}
	if bridge.startupFailed.Load() {
		t.Fatal("disco failures must not set startupFailed")
	}
	if got := bridge.pingFails.Load(); got != 5 {
		t.Fatalf("expected 5 counted ping failures, got %d", got)
	}
	if bridge.discoFresh.Load() {
		t.Fatal("disco must stay stale after failures")
	}
	if stats := bridge.GetStats(); stats.Transport != "DERP_RELAY" {
		t.Fatalf("expected last-known transport DERP_RELAY kept, got %q", stats.Transport)
	}
	if bridge.currentRTTMs() != 40 {
		t.Fatalf("failed pings must not overwrite RTT, got %d", bridge.currentRTTMs())
	}
}

// panickingUDPClient is a TunnelClient whose dial methods panic, simulating a
// defective upstream client without killing the test process.
type panickingUDPClient struct {
	closed atomic.Bool
}

func (c *panickingUDPClient) DialTCP(_ context.Context, _ netip.AddrPort) (net.Conn, error) {
	panic("simulated TCP client panic")
}

func (c *panickingUDPClient) DialUDP(_ context.Context, _ netip.AddrPort) (net.Conn, error) {
	panic("simulated UDP client panic")
}

func (c *panickingUDPClient) Close() error {
	c.closed.Store(true)
	return nil
}

// TestPanicInFlowHandled proves a panicking TunnelClient is contained: the
// process stays alive and the session is marked FAILED via the existing
// reportPumpDead path.
func TestPanicInFlowHandled(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	bridge := &TunBridge{
		sessionID: 8,
		token:     &ParsedToken{RegionID: 1},
		ctx:       ctx,
		cancel:    cancel,
	}
	proxy, err := newNetstackProxy(bridge)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	bridge.netstack = proxy
	bridge.client = &panickingUDPClient{}
	var pumpDead atomic.Int32
	bridge.setOnPumpDead(func(err error) { pumpDead.Add(1) })

	flowCtx, flowCancel := context.WithCancel(ctx)
	defer flowCancel()
	flow := &udpFlow{
		key: udpFlowKey{
			src: netip.MustParseAddrPort("10.0.0.2:40001"),
			dst: netip.MustParseAddrPort("1.1.1.1:53"),
		},
		cancel: flowCancel,
	}
	flow.touch()
	proxy.udpWg.Add(1)
	proxy.dialAndRunUDPFlow(flowCtx, flow, netip.MustParseAddrPort("1.1.1.1:53"))

	// Reaching here proves the process survived the panic.
	if got := pumpDead.Load(); got == 0 {
		t.Fatal("expected panicking flow to report pump death (FAILED path)")
	}
	if !bridge.startupFailed.Load() {
		t.Fatal("expected startupFailed set after flow panic")
	}
}

// TestDiscoStalePresentInStatsJSON proves the additive v2 discoStale field is
// always serialized and tracks disco freshness honestly.
func TestDiscoStalePresentInStatsJSON(t *testing.T) {
	bridge := &TunBridge{
		sessionID: 9,
		token:     &ParsedToken{RegionID: 1},
		transport: "DERP_RELAY",
	}

	raw, err := json.Marshal(bridge.GetStats())
	if err != nil {
		t.Fatalf("marshal stats: %v", err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("unmarshal stats: %v", err)
	}
	v, ok := decoded["discoStale"]
	if !ok {
		t.Fatal("expected discoStale field present in stats JSON")
	}
	if v != true {
		t.Fatalf("expected discoStale=true before any successful disco, got %v", v)
	}
	if decoded["version"] != float64(2) {
		t.Fatalf("schema must stay v2, got %v", decoded["version"])
	}

	bridge.discoFresh.Store(true)
	raw, err = json.Marshal(bridge.GetStats())
	if err != nil {
		t.Fatalf("marshal stats: %v", err)
	}
	decoded = nil
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("unmarshal stats: %v", err)
	}
	if v, ok := decoded["discoStale"]; !ok || v != false {
		t.Fatalf("expected discoStale=false after fresh disco, got %v (present=%v)", v, ok)
	}
}
