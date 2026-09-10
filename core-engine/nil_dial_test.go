package engine

import (
	"context"
	"encoding/binary"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"
)

// A gateway dial that returns (nil, nil) must fail just the flow, never the
// session: previously the nil connection reached track/copy/Close and panicked
// as "tcp proxy panic: invalid memory address or nil pointer dereference",
// tearing down a healthy tunnel on flow close.
func TestTCPNilDialDoesNotFailSession(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	mockClient := &mockTunnelClient{
		dialTCPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return nil, nil
		},
	}
	b := &TunBridge{ctx: ctx, cancel: cancel, client: mockClient, token: &ParsedToken{RegionID: 1}}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy

	srcAP := netip.MustParseAddrPort("10.0.0.2:45678")
	dstAP := netip.MustParseAddrPort("93.184.216.34:443")
	proxy.inject(buildIPv4TCPSyn(srcAP, dstAP), false)
	// CreateEndpoint performs the 3-way handshake inline: answer the SYN-ACK
	// or proxyTCP stays parked regardless of the dial result.
	completeTCPHandshake(t, ctx, proxy, srcAP, dstAP)
	// acceptTCP runs on gVisor's dispatcher; poll for flow teardown.
	deadline := time.Now().Add(5 * time.Second)
	for proxy.tcpActive.Load() != 0 && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}

	if b.startupFailed.Load() {
		t.Fatal("nil TCP dial marked the session failed; want flow-local failure only")
	}
	if got := proxy.tcpActive.Load(); got != 0 {
		t.Fatalf("tcpActive = %d after nil dial, want 0", got)
	}
}

// completeTCPHandshake reads the SYN-ACK for a SYN injected from srcAP and
// injects the answering ACK so a forwarder request can create its endpoint.
func completeTCPHandshake(t *testing.T, ctx context.Context, proxy *netstackProxy, srcAP, dstAP netip.AddrPort) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		packet := proxy.link.ReadContext(ctx)
		if packet == nil {
			t.Fatal("link closed while waiting for SYN-ACK")
		}
		view := packet.ToView()
		raw := append([]byte(nil), view.AsSlice()...)
		view.Release()
		packet.DecRef()
		seq, ok := parseTCPSynAck(raw, srcAP, dstAP)
		if !ok {
			continue
		}
		// Our SYN used sequence 1; acknowledge the server sequence.
		proxy.inject(buildIPv4TCPAck(srcAP, dstAP, 2, seq+1), false)
		return
	}
	t.Fatal("timed out waiting for SYN-ACK")
}

func parseTCPSynAck(raw []byte, srcAP, dstAP netip.AddrPort) (uint32, bool) {
	if len(raw) < 40 || raw[0]>>4 != 4 || raw[9] != 6 {
		return 0, false
	}
	ihl := int(raw[0]&0x0f) * 4
	if len(raw) < ihl+20 {
		return 0, false
	}
	srcIP, _ := netip.AddrFromSlice(raw[12:16])
	dstIP, _ := netip.AddrFromSlice(raw[16:20])
	if srcIP != dstAP.Addr() || dstIP != srcAP.Addr() {
		return 0, false
	}
	l4 := raw[ihl:]
	if binary.BigEndian.Uint16(l4[0:2]) != dstAP.Port() ||
		binary.BigEndian.Uint16(l4[2:4]) != srcAP.Port() {
		return 0, false
	}
	if l4[13]&0x12 != 0x12 { // SYN+ACK
		return 0, false
	}
	return binary.BigEndian.Uint32(l4[4:8]), true
}

func buildIPv4TCPAck(srcAP, dstAP netip.AddrPort, seq, ack uint32) []byte {
	pkt := make([]byte, 40)
	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], 40)
	pkt[8] = 64
	pkt[9] = 6
	src := srcAP.Addr().As4()
	dst := dstAP.Addr().As4()
	copy(pkt[12:16], src[:])
	copy(pkt[16:20], dst[:])
	binary.BigEndian.PutUint16(pkt[10:12], ipv4Checksum(pkt[:20]))
	binary.BigEndian.PutUint16(pkt[20:22], srcAP.Port())
	binary.BigEndian.PutUint16(pkt[22:24], dstAP.Port())
	binary.BigEndian.PutUint32(pkt[24:28], seq)
	binary.BigEndian.PutUint32(pkt[28:32], ack)
	pkt[32] = 5 << 4
	pkt[33] = 0x10 // ACK
	binary.BigEndian.PutUint16(pkt[34:36], 65535)
	pseudo := make([]byte, 12)
	copy(pseudo[0:4], src[:])
	copy(pseudo[4:8], dst[:])
	pseudo[9] = 6
	binary.BigEndian.PutUint16(pseudo[10:12], 20)
	binary.BigEndian.PutUint16(pkt[36:38], checksum(pseudo, pkt[20:]))
	return pkt
}

func TestUDPNilDialDoesNotFailSession(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return nil, nil
		},
	}
	b := &TunBridge{ctx: ctx, cancel: cancel, client: mockClient, token: &ParsedToken{RegionID: 1}}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy

	srcAP := netip.MustParseAddrPort("10.0.0.2:23456")
	dstAP := netip.MustParseAddrPort("1.1.1.1:53")
	proxy.inject(buildIPv4UDPPacket(srcAP, dstAP, []byte("nil-dial-test")), false)
	time.Sleep(300 * time.Millisecond)

	if b.startupFailed.Load() {
		t.Fatal("nil UDP dial marked the session failed; want flow-local failure only")
	}
	proxy.udpMu.Lock()
	total := proxy.udpActiveTotal
	proxy.udpMu.Unlock()
	if total != 0 {
		t.Fatalf("udpActiveTotal = %d after nil dial, want 0", total)
	}
}

func TestDNSOverTCPNilDialDoesNotFailSession(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	dialed := make(chan struct{}, 1)
	mockClient := &mockTunnelClient{
		dialTCPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			select {
			case dialed <- struct{}{}:
			default:
			}
			return nil, nil
		},
	}
	b := &TunBridge{ctx: ctx, cancel: cancel, client: mockClient, token: &ParsedToken{RegionID: 1}}
	b.tcpOnly.Store(true)
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy

	query := buildDNSQuery(0x1234, "nil.example", 1)
	srcAP := netip.MustParseAddrPort("10.0.0.2:45001")
	dstAP := netip.MustParseAddrPort("1.1.1.1:53")
	proxy.inject(buildIPv4UDPPacket(srcAP, dstAP, query), false)

	select {
	case <-dialed:
	case <-time.After(2 * time.Second):
		t.Fatal("DNS-over-TCP dial did not start")
	}
	time.Sleep(200 * time.Millisecond)

	if b.startupFailed.Load() {
		t.Fatal("nil DNS-over-TCP dial marked the session failed; want flow-local failure only")
	}
}

func TestIsNilConn(t *testing.T) {
	var nilIface net.Conn
	if !isNilConn(nilIface) {
		t.Fatal("nil interface must be rejected")
	}
	var nilPtr *net.TCPConn
	if !isNilConn(net.Conn(nilPtr)) {
		t.Fatal("typed-nil connection must be rejected")
	}
	c1, c2 := net.Pipe()
	defer c1.Close()
	defer c2.Close()
	if isNilConn(c1) {
		t.Fatal("live connection must be accepted")
	}
}

//go:noinline
func panicSiteHelper() { panic("boom") }

// recoverLikeProduction mirrors recoverFlow/recoverPump nesting so panicSite
// is exercised the same way as on device.
func recoverLikeProduction(site *string) {
	if recover() != nil {
		*site = panicSite()
	}
}

func TestPanicSiteNamesFaultingFunction(t *testing.T) {
	var site string
	func() {
		defer recoverLikeProduction(&site)
		panicSiteHelper()
	}()
	if strings.HasPrefix(site, "runtime.") || site == "unknown" {
		t.Fatalf("panicSite must not report runtime frames, got %q", site)
	}
	if !strings.Contains(site, "panicSiteHelper") {
		t.Fatalf("expected panicSite to name the faulting helper, got %q", site)
	}
}

// typedNilTCPConn is a typed-nil *net.TCPConn stored in a net.Conn interface.
// c != nil is true for this value; Close must not be called on it.
func typedNilTCPConn() net.Conn {
	var p *net.TCPConn
	return p
}

func TestTCPTypedNilCloseDoesNotFailSession(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	mockClient := &mockTunnelClient{
		dialTCPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return typedNilTCPConn(), nil
		},
	}
	b := &TunBridge{ctx: ctx, cancel: cancel, client: mockClient, token: &ParsedToken{RegionID: 1}}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy
	var pumpDead int32
	b.setOnPumpDead(func(error) { pumpDead++ })

	srcAP := netip.MustParseAddrPort("10.0.0.2:45679")
	dstAP := netip.MustParseAddrPort("93.184.216.34:443")
	proxy.inject(buildIPv4TCPSyn(srcAP, dstAP), false)
	completeTCPHandshake(t, ctx, proxy, srcAP, dstAP)
	deadline := time.Now().Add(5 * time.Second)
	for proxy.tcpActive.Load() != 0 && time.Now().Before(deadline) {
		time.Sleep(50 * time.Millisecond)
	}

	if b.startupFailed.Load() || pumpDead != 0 {
		t.Fatalf("typed-nil TCP dial Close must not fail session (startupFailed=%v pumpDead=%d)", b.startupFailed.Load(), pumpDead)
	}
	if got := proxy.tcpActive.Load(); got != 0 {
		t.Fatalf("tcpActive = %d after typed-nil dial, want 0", got)
	}
}

func TestCloseConnIgnoresTypedNil(t *testing.T) {
	// Must not panic.
	closeConn(nil)
	closeConn(typedNilTCPConn())
}
