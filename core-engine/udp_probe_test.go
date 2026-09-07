package engine

import (
	"context"
	"testing"
)

// probeMockClient extends mockTunnelClient with a scripted UDP capability probe.
type probeMockClient struct {
	mockTunnelClient
	udpOK bool
}

func (m *probeMockClient) SupportsUDP(_ context.Context) bool {
	return m.udpOK
}

func TestTcpOnlyDefaultsFalseInStats(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	b := &TunBridge{ctx: ctx, cancel: cancel, client: &mockTunnelClient{}}
	stats := b.GetStats()
	if stats.TcpOnly {
		t.Fatal("expected tcpOnly=false by default in stats")
	}
}

func TestTcpOnlySurfacedInStats(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	b := &TunBridge{ctx: ctx, cancel: cancel, client: &mockTunnelClient{}}
	b.tcpOnly.Store(true)
	stats := b.GetStats()
	if !stats.TcpOnly {
		t.Fatal("expected tcpOnly=true in stats after latch")
	}
}

func TestReprobeUDPClearsStaleTcpOnly(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	b := &TunBridge{ctx: ctx, cancel: cancel, client: &probeMockClient{udpOK: true}}
	b.tcpOnly.Store(true)
	b.reprobeUDP()
	if b.tcpOnly.Load() {
		t.Fatal("expected stale tcpOnly latch to clear after successful UDP re-probe")
	}
	if b.GetStats().TcpOnly {
		t.Fatal("expected stats to reflect cleared tcpOnly latch")
	}
}

func TestReprobeUDPAdmitsFailureKeepsTcpOnly(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	b := &TunBridge{ctx: ctx, cancel: cancel, client: &probeMockClient{udpOK: false}}
	b.tcpOnly.Store(true)
	b.reprobeUDP()
	if !b.tcpOnly.Load() {
		t.Fatal("expected tcpOnly to stay latched after failed UDP re-probe")
	}
}

func TestReprobeUDPWithoutProberKeepsTcpOnly(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	b := &TunBridge{ctx: ctx, cancel: cancel, client: &mockTunnelClient{}}
	b.tcpOnly.Store(true)
	b.reprobeUDP()
	if !b.tcpOnly.Load() {
		t.Fatal("expected tcpOnly to stay latched when client has no UDP prober")
	}
}
