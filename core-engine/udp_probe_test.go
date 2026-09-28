package engine

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"
)

// fakeUDPNet stands in for Client.DialUDP: each dial returns one end of a
// pipe whose other end answers with answer[dst] (nil or missing = silent).
type fakeUDPNet struct {
	answer  map[netip.AddrPort]func(req []byte, n int) []byte
	dialErr bool
}

func (f *fakeUDPNet) dial(_ context.Context, dst netip.AddrPort) (net.Conn, error) {
	if f.dialErr {
		return nil, errors.New("dial refused")
	}
	client, server := net.Pipe()
	respond := f.answer[dst]
	go func() {
		defer server.Close()
		buf := make([]byte, 1500)
		for n := 1; ; n++ {
			m, err := server.Read(buf)
			if err != nil {
				return
			}
			if respond == nil {
				continue
			}
			if resp := respond(buf[:m], n); resp != nil {
				if _, err := server.Write(resp); err != nil {
					return
				}
			}
		}
	}()
	return client, nil
}

// stunReply is a binding success for req (no attributes).
func stunReply(req []byte, _ int) []byte {
	resp := make([]byte, stunHeaderLen)
	binary.BigEndian.PutUint16(resp[0:2], stunBindingOK)
	binary.BigEndian.PutUint32(resp[4:8], stunMagicCookie)
	copy(resp[stunTxIDOffset:], req[stunTxIDOffset:stunHeaderLen])
	return resp
}

func TestProbeGatewayUDPNeedsAGenericUDPAnswer(t *testing.T) {
	cf, google := udpProbeTargets[0], udpProbeTargets[1]
	dns := netip.MustParseAddrPort("1.1.1.1:53")
	echo := func(req []byte, _ int) []byte { return append([]byte(nil), req...) }
	otherTx := func(req []byte, n int) []byte {
		resp := stunReply(req, n)
		resp[stunTxIDOffset] ^= 0xff
		return resp
	}
	secondOnly := func(req []byte, n int) []byte {
		if n < 2 {
			return nil // the first request is lost, as on a lossy relay
		}
		return stunReply(req, n)
	}
	type answers = map[netip.AddrPort]func([]byte, int) []byte
	cases := []struct {
		name    string
		net     *fakeUDPNet
		timeout time.Duration
		want    bool
	}{
		{"cloudflare answers", &fakeUDPNet{answer: answers{cf: stunReply}}, time.Second, true},
		{"only google answers", &fakeUDPNet{answer: answers{google: stunReply}}, time.Second, true},
		{"first request lost", &fakeUDPNet{answer: answers{cf: secondOnly}}, 3 * time.Second, true},
		{"gateway answers DNS only", &fakeUDPNet{answer: answers{dns: echo}}, 500 * time.Millisecond, false},
		{"echoes are not STUN answers", &fakeUDPNet{answer: answers{cf: echo, google: echo}}, 500 * time.Millisecond, false},
		{"another transaction id", &fakeUDPNet{answer: answers{cf: otherTx, google: otherTx}}, 500 * time.Millisecond, false},
		{"dial fails", &fakeUDPNet{dialErr: true}, 500 * time.Millisecond, false},
	}
	for _, c := range cases {
		ctx, cancel := context.WithTimeout(context.Background(), c.timeout)
		start := time.Now()
		got := probeGatewayUDP(ctx, c.net.dial)
		cancel()
		if got != c.want {
			t.Errorf("%s: probeGatewayUDP = %v, want %v", c.name, got, c.want)
		}
		if elapsed := time.Since(start); elapsed > c.timeout+500*time.Millisecond {
			t.Errorf("%s: probe took %v, past its %v deadline", c.name, elapsed, c.timeout)
		}
	}
}

// countingProbeClient is a prepared client whose UDP probe result is scripted
// and whose probes are counted.
type countingProbeClient struct {
	mockTunnelClient
	udpOK  bool
	probes atomic.Int32
}

func (c *countingProbeClient) SupportsUDP(context.Context) bool {
	c.probes.Add(1)
	return c.udpOK
}

func waitForCondition(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestUnansweredUDPFlowsRelatchTcpOnly(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client := &countingProbeClient{udpOK: false}
	b := &TunBridge{ctx: ctx, cancel: cancel, client: client}

	for i := 0; i < udpUnansweredRelatch-1; i++ {
		b.noteUDPFlowEnded(1, 0)
	}
	b.noteUDPFlowEnded(3, 2) // an answered flow ends the run
	for i := 0; i < udpUnansweredRelatch-1; i++ {
		b.noteUDPFlowEnded(1, 0)
	}
	b.noteUDPFlowEnded(0, 0) // a flow that never sent says nothing
	if client.probes.Load() != 0 || b.udpRelatching.Load() {
		t.Fatal("probed before udpUnansweredRelatch unanswered flows in a row")
	}

	b.noteUDPFlowEnded(1, 0)
	waitForCondition(t, "tcpOnly after a failed probe", b.tcpOnly.Load)
	if got := client.probes.Load(); got != 1 {
		t.Fatalf("probes = %d, want 1", got)
	}
	if !b.GetStats().TcpOnly {
		t.Fatal("stats do not show the re-latched tcpOnly")
	}
}

func TestUnansweredUDPFlowsKeepUDPWhenProbeSucceeds(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client := &countingProbeClient{udpOK: true}
	b := &TunBridge{ctx: ctx, cancel: cancel, client: client}
	for i := 0; i < udpUnansweredRelatch; i++ {
		b.noteUDPFlowEnded(1, 0)
	}
	waitForCondition(t, "the probe to finish", func() bool {
		return client.probes.Load() == 1 && !b.udpRelatching.Load()
	})
	if b.tcpOnly.Load() {
		t.Fatal("tcpOnly set although the gateway still forwards UDP")
	}
}

func TestIdleGCReportsUnansweredFlows(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	client := &countingProbeClient{udpOK: false}
	b := &TunBridge{ctx: ctx, cancel: cancel, client: client, token: &ParsedToken{RegionID: 1}}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()

	now := time.Now()
	proxy.udpMu.Lock()
	for i := 0; i < udpUnansweredRelatch; i++ {
		f := &udpFlow{}
		f.key.src = netip.MustParseAddrPort(fmt.Sprintf("100.64.0.2:%d", 40000+i))
		f.sent.Store(2)
		f.lastActive.Store(now.Add(-udpUnrepliedIdle - time.Second).UnixNano())
		proxy.udpFlows[f.key] = f
	}
	proxy.udpMu.Unlock()

	proxy.expireIdleUDPFlows(now)
	waitForCondition(t, "tcpOnly after unanswered flows expired", b.tcpOnly.Load)
}

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
