package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"tailscale.com/ipn/ipnstate"
)

// echoDatagramConn answers every datagram written to it with the same bytes,
// like a resolver answering each query at once.
type echoDatagramConn struct {
	ch     chan []byte
	closed chan struct{}
	once   sync.Once
}

func newEchoDatagramConn() *echoDatagramConn {
	return &echoDatagramConn{ch: make(chan []byte, 16), closed: make(chan struct{})}
}

func (c *echoDatagramConn) Read(b []byte) (int, error) {
	select {
	case p := <-c.ch:
		return copy(b, p), nil
	case <-c.closed:
		return 0, net.ErrClosed
	}
}

func (c *echoDatagramConn) Write(b []byte) (int, error) {
	select {
	case <-c.closed:
		return 0, net.ErrClosed
	case c.ch <- append([]byte(nil), b...):
	default:
	}
	return len(b), nil
}

func (c *echoDatagramConn) Close() error {
	c.once.Do(func() { close(c.closed) })
	return nil
}
func (c *echoDatagramConn) LocalAddr() net.Addr                { return &net.UDPAddr{} }
func (c *echoDatagramConn) RemoteAddr() net.Addr               { return &net.UDPAddr{} }
func (c *echoDatagramConn) SetDeadline(t time.Time) error      { return nil }
func (c *echoDatagramConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *echoDatagramConn) SetWriteDeadline(t time.Time) error { return nil }

func readLinkPacket(t *testing.T, ctx context.Context, proxy *netstackProxy) []byte {
	t.Helper()
	packet := proxy.link.ReadContext(ctx)
	if packet == nil {
		t.Fatal("no packet from netstack before deadline")
	}
	view := packet.ToView()
	raw := append([]byte(nil), view.AsSlice()...)
	view.Release()
	packet.DecRef()
	return raw
}

// A burst of lookups from the one TUN address (each a fresh source port,
// each answered at once) must never be refused. Before the fix query 129
// onward got ICMP port-unreachable for 30-40 s.
func TestUDPBurstFromSharedTUNAddressIsNeverRefused(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	b := &TunBridge{ctx: ctx, cancel: cancel, token: &ParsedToken{RegionID: 1}, client: &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return newEchoDatagramConn(), nil
		},
	}}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy

	const queries = maxActiveUDPFlows + 476
	dst := netip.MustParseAddrPort("1.1.1.1:53")
	for i := 0; i < queries; i++ {
		src := netip.AddrPortFrom(netip.MustParseAddr("100.64.0.2"), uint16(20000+i))
		proxy.inject(buildIPv4UDPPacket(src, dst, buildDNSQuery(uint16(i), "example.com", 1)), false)
		reply := readLinkPacket(t, ctx, proxy)
		if reply[9] != 17 {
			t.Fatalf("query %d: got IP protocol %d, want a UDP answer (1 = ICMP refusal)", i, reply[9])
		}
		if got := binary.BigEndian.Uint16(reply[22:24]); got != src.Port() {
			t.Fatalf("query %d: answer to port %d, want %d", i, got, src.Port())
		}
	}
	if got := b.queueExhaustion.Load(); got != 0 {
		t.Fatalf("queueExhaustion = %d, want 0", got)
	}
	if got := b.udpEvictions.Load(); got != queries-maxActiveUDPFlows {
		t.Fatalf("udpEvictions = %d, want %d", got, queries-maxActiveUDPFlows)
	}
	proxy.udpMu.Lock()
	total := proxy.udpActiveTotal
	proxy.udpMu.Unlock()
	if total > maxActiveUDPFlows {
		t.Fatalf("udpActiveTotal = %d exceeds the cap %d", total, maxActiveUDPFlows)
	}
}

func TestUDPFlowIdleTimeoutsFollowConntrackClasses(t *testing.T) {
	flow := func(isDNS bool, sent, received int64) *udpFlow {
		f := &udpFlow{isDNS: isDNS}
		f.sent.Store(sent)
		f.received.Store(received)
		return f
	}
	cases := []struct {
		name string
		flow *udpFlow
		want time.Duration
	}{
		{"dns all answered", flow(true, 2, 2), udpDNSAnsweredIdle},
		{"dns query outstanding", flow(true, 2, 1), udpDNSUnansweredIdle},
		{"dns before first query read", flow(true, 0, 0), udpDNSUnansweredIdle},
		{"unreplied", flow(false, 5, 0), udpUnrepliedIdle},
		{"replied", flow(false, 5, 1), udpRepliedIdle},
	}
	for _, c := range cases {
		if got := c.flow.idleTimeout(); got != c.want {
			t.Errorf("%s: idleTimeout = %v, want %v", c.name, got, c.want)
		}
	}
	if udpRepliedIdle < 2*time.Minute {
		t.Errorf("replied flows must keep their mapping at least 2 min (RFC 4787 REQ-5), got %v", udpRepliedIdle)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	b := &TunBridge{ctx: ctx, cancel: cancel, client: &mockTunnelClient{}, token: &ParsedToken{RegionID: 1}}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()

	now := time.Now()
	answeredDNS := flow(true, 1, 1)
	answeredDNS.key.src = netip.MustParseAddrPort("100.64.0.2:1")
	answeredDNS.lastActive.Store(now.Add(-3 * time.Second).UnixNano())
	quic := flow(false, 10, 10)
	quic.key.src = netip.MustParseAddrPort("100.64.0.2:2")
	quic.lastActive.Store(now.Add(-60 * time.Second).UnixNano())
	proxy.udpMu.Lock()
	proxy.udpFlows[answeredDNS.key] = answeredDNS
	proxy.udpFlows[quic.key] = quic
	proxy.udpMu.Unlock()

	expired := proxy.expiredUDPFlows(now)
	if len(expired) != 1 || expired[0] != answeredDNS {
		t.Fatalf("expected only the answered DNS flow to expire, got %d flows", len(expired))
	}
}

// newPipeBridge returns a bridge whose TUN writes can be read from r.
func newPipeBridge(t *testing.T, mtu int, client TunnelClient) (*TunBridge, *os.File, func()) {
	t.Helper()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	b := &TunBridge{ctx: ctx, cancel: cancel, client: client, token: &ParsedToken{RegionID: 1}, mtu: mtu, tunFile: w}
	b.ipv6Egress.Store(true)
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	b.netstack = proxy
	return b, r, func() {
		proxy.Close()
		cancel()
		_ = w.Close()
		_ = r.Close()
	}
}

func readTunWrite(r *os.File, timeout time.Duration) []byte {
	_ = r.SetReadDeadline(time.Now().Add(timeout))
	buf := make([]byte, 4096)
	n, err := r.Read(buf)
	if err != nil {
		return nil
	}
	return buf[:n]
}

func clearIPv4DF(pkt []byte) {
	pkt[6] &^= 0x40
	pkt[10], pkt[11] = 0, 0
	binary.BigEndian.PutUint16(pkt[10:12], ipv4Checksum(pkt[:20]))
}

func countingUDPClient(dials *atomic.Int32) *mockTunnelClient {
	return &mockTunnelClient{dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
		dials.Add(1)
		return newEchoDatagramConn(), nil
	}}
}

// Chrome's 1250 B QUIC Initial fits the 1280 TUN MTU but not the tunnel.
func TestOversizeIPv4UDPIsRefusedWithFragNeeded(t *testing.T) {
	var dials atomic.Int32
	b, r, cleanup := newPipeBridge(t, 1280, countingUDPClient(&dials))
	defer cleanup()

	src := netip.MustParseAddrPort("100.64.0.2:41041")
	dst := netip.MustParseAddrPort("74.125.197.95:443")

	quic := buildIPv4UDPPacket(src, dst, make([]byte, 1250)) // DF set
	b.handleOutboundPacket(quic)
	reply := readTunWrite(r, time.Second)
	if reply == nil {
		t.Fatal("expected ICMP Fragmentation Needed on the TUN")
	}
	if reply[9] != 1 || reply[20] != 3 || reply[21] != 4 {
		t.Fatalf("expected ICMP 3/4, got proto %d type %d code %d", reply[9], reply[20], reply[21])
	}
	if mtu := binary.BigEndian.Uint16(reply[26:28]); mtu != 1260 {
		t.Fatalf("next-hop MTU = %d, want 1260", mtu)
	}
	if !bytes.Equal(reply[28:48], quic[:20]) {
		t.Fatal("Fragmentation Needed must quote the offending IP header")
	}

	noDF := buildIPv4UDPPacket(src, dst, make([]byte, 1250))
	clearIPv4DF(noDF)
	b.handleOutboundPacket(noDF)
	if extra := readTunWrite(r, 100*time.Millisecond); extra != nil {
		t.Fatalf("a datagram without DF must be dropped silently, got %d byte reply", len(extra))
	}
	if got := b.mtuExceeded.Load(); got != 2 {
		t.Fatalf("mtuExceeded = %d, want 2", got)
	}
	if got := dials.Load(); got != 0 {
		t.Fatalf("oversized datagrams must not dial, got %d dials", got)
	}

	b.handleOutboundPacket(buildIPv4UDPPacket(src, dst, make([]byte, maxTunnelUDPPayload)))
	waitAtomic32(t, &dials, 1, 2*time.Second, "DialUDP for a 1232 B datagram")
}

func TestOversizeIPv6UDPGetsPacketTooBigWithinMinMTU(t *testing.T) {
	var dials atomic.Int32
	b, r, cleanup := newPipeBridge(t, 1500, countingUDPClient(&dials))
	defer cleanup()

	pkt := buildIPv6UDPPacket(
		netip.MustParseAddrPort("[fd7a:115c:a1e0::2]:5000"),
		netip.MustParseAddrPort("[2606:4700::1]:443"),
		make([]byte, 1400),
	)
	b.handleOutboundPacket(pkt)
	reply := readTunWrite(r, time.Second)
	if reply == nil {
		t.Fatal("expected ICMPv6 Packet Too Big on the TUN")
	}
	if reply[6] != 58 || reply[40] != 2 {
		t.Fatalf("expected ICMPv6 type 2, got next header %d type %d", reply[6], reply[40])
	}
	if mtu := binary.BigEndian.Uint32(reply[44:48]); mtu != 1280 {
		t.Fatalf("PTB MTU = %d, want 1280", mtu)
	}
	if len(reply) > ipv6MinMTU {
		t.Fatalf("PTB is %d bytes; ICMPv6 errors must fit in %d (RFC 4443 2.4)", len(reply), ipv6MinMTU)
	}
	if dials.Load() != 0 {
		t.Fatal("oversized IPv6 datagram must not dial")
	}
}

func TestICMPErrorsAreRateLimited(t *testing.T) {
	b, r, cleanup := newPipeBridge(t, 1280, &mockTunnelClient{})
	defer cleanup()

	pkt := buildIPv4UDPPacket(netip.MustParseAddrPort("100.64.0.2:1"), netip.MustParseAddrPort("1.1.1.1:443"), make([]byte, 1250))
	go func() {
		for i := 0; i < maxICMPErrorsPerSecond*3; i++ {
			b.handleOutboundPacket(pkt)
		}
	}()
	replies := 0
	for readTunWrite(r, 200*time.Millisecond) != nil {
		replies++
	}
	// The loop may straddle one second boundary.
	if replies > 2*maxICMPErrorsPerSecond {
		t.Fatalf("%d ICMP errors written; limit is %d per second", replies, maxICMPErrorsPerSecond)
	}
	if replies == 0 {
		t.Fatal("expected some ICMP errors")
	}
}

// fragmentIPv4 splits an unfragmented IPv4 packet into two fragments.
func fragmentIPv4(pkt []byte, firstPayload int) [][]byte {
	data := pkt[20:]
	build := func(part []byte, offset int, more bool) []byte {
		f := make([]byte, 20+len(part))
		copy(f, pkt[:20])
		copy(f[20:], part)
		binary.BigEndian.PutUint16(f[2:4], uint16(len(f)))
		flags := uint16(offset / 8)
		if more {
			flags |= 0x2000
		}
		binary.BigEndian.PutUint16(f[4:6], 0x4242)
		binary.BigEndian.PutUint16(f[6:8], flags)
		f[10], f[11] = 0, 0
		binary.BigEndian.PutUint16(f[10:12], ipv4Checksum(f[:20]))
		return f
	}
	return [][]byte{build(data[:firstPayload], 0, true), build(data[firstPayload:], firstPayload, false)}
}

// A datagram the kernel fragmented locally is reassembled by gVisor and
// must be dropped before the tunnel; later small datagrams still flow.
func TestReassembledOversizeUDPIsDroppedNotForwarded(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	local, remote := newPairedDatagramConns()
	defer remote.Close()
	b, _, cleanup := newPipeBridge(t, 1280, &mockTunnelClient{dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
		return local, nil
	}})
	defer cleanup()

	src := netip.MustParseAddrPort("100.64.0.2:7000")
	dst := netip.MustParseAddrPort("198.51.100.7:9000")
	big := buildIPv4UDPPacket(src, dst, bytes.Repeat([]byte{0xab}, 2000))
	clearIPv4DF(big)
	for _, frag := range fragmentIPv4(big, 1224) {
		b.handleOutboundPacket(frag)
	}
	b.handleOutboundPacket(buildIPv4UDPPacket(src, dst, []byte("small")))

	got := make(chan []byte, 1)
	go func() {
		buf := make([]byte, 4096)
		n, err := remote.Read(buf)
		if err == nil {
			got <- append([]byte(nil), buf[:n]...)
		}
	}()
	select {
	case p := <-got:
		if string(p) != "small" {
			t.Fatalf("first datagram through the tunnel is %d bytes, want the 5 byte one", len(p))
		}
	case <-ctx.Done():
		t.Fatal("small datagram never reached the tunnel")
	}
	if got := b.mtuExceeded.Load(); got != 1 {
		t.Fatalf("mtuExceeded = %d, want 1", got)
	}
}

// A local echo reply would claim any host is reachable (the review saw
// 0.05 ms replies from unroutable TEST-NET-3), so IPv4 echo is dropped.
func TestIPv4ICMPEchoIsDroppedNotAnswered(t *testing.T) {
	b, r, cleanup := newPipeBridge(t, 1280, &mockTunnelClient{})
	defer cleanup()

	echo := buildIPv4ICMPEcho(netip.MustParseAddr("100.64.0.2"), netip.MustParseAddr("203.0.113.1"), make([]byte, 64))
	b.handleOutboundPacket(echo)
	if reply := readTunWrite(r, 200*time.Millisecond); reply != nil {
		t.Fatalf("an echo request must not be answered locally, got %d bytes", len(reply))
	}
	if got := b.policyRejections.Load(); got != 1 {
		t.Fatalf("policyRejections = %d, want 1", got)
	}
}

// dnsRedirectBridge records the destination of every tunnel dial.
func dnsRedirectBridge(t *testing.T, cfg DNSConfig) (*TunBridge, chan netip.AddrPort, func()) {
	t.Helper()
	dialed := make(chan netip.AddrPort, 8)
	client := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			dialed <- dst
			return newEchoDatagramConn(), nil
		},
		dialTCPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			dialed <- dst
			return nil, errors.New("tcp unused")
		},
	}
	ctx, cancel := context.WithCancel(context.Background())
	b := &TunBridge{ctx: ctx, cancel: cancel, client: client, token: &ParsedToken{RegionID: 1}, mtu: 1280}
	b.ipv6Egress.Store(false) // the live gateway has no IPv6 WAN
	b.SetDNSConfig(cfg)
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	b.netstack = proxy
	return b, dialed, func() { proxy.Close(); cancel() }
}

func expectDial(t *testing.T, dialed chan netip.AddrPort, want netip.AddrPort) {
	t.Helper()
	select {
	case got := <-dialed:
		if got != want {
			t.Fatalf("dialed %v, want %v", got, want)
		}
	case <-time.After(2 * time.Second):
		t.Fatalf("no dial; want %v", want)
	}
}

func expectNoDial(t *testing.T, dialed chan netip.AddrPort) {
	t.Helper()
	select {
	case got := <-dialed:
		t.Fatalf("unexpected dial to %v", got)
	case <-time.After(100 * time.Millisecond):
	}
}

func TestIPv6DNSServerIsRedirectedBeforeIPv6EgressGate(t *testing.T) {
	v6Src := netip.MustParseAddrPort("[fd7a:115c:a1e0::2]:5353")
	v6Resolver := netip.MustParseAddrPort("[2606:4700:4700::1111]:53")
	forcedV4 := netip.MustParseAddrPort("1.1.1.1:53")
	query := buildDNSQuery(1, "example.com", 1)

	t.Run("forced IPv4 resolver carries IPv6 queries", func(t *testing.T) {
		b, dialed, cleanup := dnsRedirectBridge(t, DNSConfig{Policy: "FORCED_RESOLVER", ForcedDNS: forcedV4})
		defer cleanup()
		b.netstack.inject(buildIPv6UDPPacket(v6Src, v6Resolver, query), true)
		expectDial(t, dialed, forcedV4)
		b.netstack.inject(buildIPv6TCPSyn(netip.MustParseAddrPort("[fd7a:115c:a1e0::2]:40000"), v6Resolver), true)
		expectDial(t, dialed, forcedV4)
		if got := b.policyRejections.Load(); got != 0 {
			t.Fatalf("policyRejections = %d, want 0", got)
		}
	})

	t.Run("profile IPv6 resolver is refused at once", func(t *testing.T) {
		b, dialed, cleanup := dnsRedirectBridge(t, DNSConfig{Policy: "PROFILE_RESOLVER"})
		defer cleanup()
		b.netstack.inject(buildIPv6UDPPacket(v6Src, v6Resolver, query), true)
		expectNoDial(t, dialed)
		if got := b.policyRejections.Load(); got != 1 {
			t.Fatalf("policyRejections = %d, want 1", got)
		}
	})

	t.Run("forced IPv6 resolver refuses IPv4 queries at once", func(t *testing.T) {
		b, dialed, cleanup := dnsRedirectBridge(t, DNSConfig{Policy: "FORCED_RESOLVER", ForcedDNS: v6Resolver})
		defer cleanup()
		b.netstack.inject(buildIPv4UDPPacket(netip.MustParseAddrPort("100.64.0.2:5353"), netip.MustParseAddrPort("8.8.8.8:53"), query), false)
		expectNoDial(t, dialed)
		if got := b.policyRejections.Load(); got != 1 {
			t.Fatalf("policyRejections = %d, want 1", got)
		}
	})
}

func TestNAT64DestinationNeedsNoIPv6Egress(t *testing.T) {
	if isPublicIPv6Destination(netip.MustParseAddrPort("[64:ff9b::101:101]:443")) {
		t.Fatal("NAT64 addresses leave the gateway as IPv4 and must not need IPv6 egress")
	}
	if !isPublicIPv6Destination(netip.MustParseAddrPort("[2606:4700::1]:443")) {
		t.Fatal("public IPv6 must still need IPv6 egress")
	}
}

// newAppStack wires a second gVisor stack, standing in for Android apps, to
// the proxy's link so tests can use real TCP semantics.
func newAppStack(t *testing.T, ctx context.Context, proxy *netstackProxy) *stack.Stack {
	t.Helper()
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	ep := channel.New(1024, 1280, "")
	if err := s.CreateNIC(1, ep); err != nil {
		t.Fatalf("CreateNIC: %v", err)
	}
	addr := tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4([4]byte{100, 64, 0, 2}).WithPrefix(),
	}
	if err := s.AddProtocolAddress(1, addr, stack.AddressProperties{}); err != nil {
		t.Fatalf("AddProtocolAddress: %v", err)
	}
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	copyPacket := func(pkt *stack.PacketBuffer) []byte {
		view := pkt.ToView()
		raw := append([]byte(nil), view.AsSlice()...)
		view.Release()
		pkt.DecRef()
		return raw
	}
	go func() {
		for {
			pkt := ep.ReadContext(ctx)
			if pkt == nil {
				return
			}
			proxy.inject(copyPacket(pkt), false)
		}
	}()
	go func() {
		for {
			pkt := proxy.link.ReadContext(ctx)
			if pkt == nil {
				return
			}
			in := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(copyPacket(pkt))})
			ep.InjectInbound(ipv4.ProtocolNumber, in)
			in.DecRef()
		}
	}()
	t.Cleanup(func() { s.Close(); s.Wait() })
	return s
}

// An app that shuts down its write side after the request (HTTP/1.0,
// netcat, some RPC clients) must still receive the whole response.
func TestTCPHalfCloseStillDeliversResponse(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	tunnelSide, server := newPairedStreamConns()
	b := &TunBridge{ctx: ctx, cancel: cancel, token: &ParsedToken{RegionID: 1}, client: &mockTunnelClient{
		dialTCPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return tunnelSide, nil
		},
	}}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy
	app := newAppStack(t, ctx, proxy)

	const response = "HTTP/1.0 200 OK\r\n\r\nhello after half-close"
	serverDone := make(chan error, 1)
	go func() {
		req, err := io.ReadAll(server) // until the app's FIN
		if err != nil {
			serverDone <- err
			return
		}
		if string(req) != "GET / HTTP/1.0\r\n\r\n" {
			serverDone <- errors.New("server got request " + string(req))
			return
		}
		time.Sleep(50 * time.Millisecond) // answer after the FIN has propagated
		_, err = server.Write([]byte(response))
		_ = server.Close()
		serverDone <- err
	}()

	conn, err := gonet.DialContextTCP(ctx, app, tcpip.FullAddress{
		NIC:  1,
		Addr: tcpip.AddrFrom4([4]byte{93, 184, 216, 34}),
		Port: 80,
	}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatalf("app dial: %v", err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte("GET / HTTP/1.0\r\n\r\n")); err != nil {
		t.Fatalf("write request: %v", err)
	}
	if err := conn.CloseWrite(); err != nil {
		t.Fatalf("CloseWrite: %v", err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	got, err := io.ReadAll(conn)
	if err != nil {
		t.Fatalf("read response: %v (got %q)", err, got)
	}
	if string(got) != response {
		t.Fatalf("response = %q, want %q", got, response)
	}
	if err := <-serverDone; err != nil {
		t.Fatalf("server: %v", err)
	}
}

func dnsQueryWithEDNS(size uint16) []byte {
	q := buildDNSQuery(7, "big.example.org", 16)
	binary.BigEndian.PutUint16(q[10:12], 1) // ARCOUNT
	opt := []byte{0, 0, 41, 0, 0, 0, 0, 0, 0, 0, 0}
	binary.BigEndian.PutUint16(opt[3:5], size)
	return append(q, opt...)
}

func TestTruncateDNSForUDPHonorsClientLimit(t *testing.T) {
	plain := buildDNSQuery(7, "big.example.org", 16)
	small := buildDNSResponse(7, "big.example.org", false, netip.MustParseAddr("192.0.2.1"), 100)
	big := buildDNSResponse(7, "big.example.org", false, netip.MustParseAddr("192.0.2.1"), 1500)

	if got := truncateDNSForUDP(plain, small); !bytes.Equal(got, small) {
		t.Fatal("an answer under 512 B must pass unchanged")
	}
	if got := truncateDNSForUDP(dnsQueryWithEDNS(4096), big); !bytes.Equal(got, big) {
		t.Fatal("an answer within the EDNS size must pass unchanged")
	}
	for name, query := range map[string][]byte{"no EDNS": plain, "EDNS 1232": dnsQueryWithEDNS(1232)} {
		got := truncateDNSForUDP(query, big)
		if got[2]&0x02 == 0 {
			t.Fatalf("%s: TC bit not set", name)
		}
		if binary.BigEndian.Uint16(got[0:2]) != 7 {
			t.Fatalf("%s: transaction ID changed", name)
		}
		if qd, an := binary.BigEndian.Uint16(got[4:6]), binary.BigEndian.Uint16(got[6:8]); qd != 1 || an != 0 {
			t.Fatalf("%s: QDCOUNT/ANCOUNT = %d/%d, want 1/0", name, qd, an)
		}
		if len(got) != len(plain) {
			t.Fatalf("%s: truncated answer is %d bytes, want header + question (%d)", name, len(got), len(plain))
		}
	}
	if got := dnsUDPResponseLimit([]byte{1, 2, 3}); got != dnsClassicUDPSize {
		t.Fatalf("malformed query limit = %d, want 512", got)
	}
}

// A panic inside an exported entry point must come back as an error: an
// unrecovered Go panic aborts the whole Android process.
func TestExportedPrepareRecoversPanicAndResets(t *testing.T) {
	_ = Stop()
	original := newTailcatClient
	newTailcatClient = func(tailcat.ConnBlob) preparedClient { panic("boom") }
	t.Cleanup(func() {
		newTailcatClient = original
		_ = Stop()
	})

	err := Prepare(officialTestToken(t))
	if err == nil || !strings.Contains(err.Error(), "Prepare panic: boom") {
		t.Fatalf("Prepare error = %v, want a contained panic", err)
	}
	if _, st := statsState(t); st != StateStopped {
		t.Fatalf("state after contained panic = %v, want STOPPED", st)
	}

	newTailcatClient = func(tailcat.ConnBlob) preparedClient { return &prepareTestClient{} }
	if err := Prepare(officialTestToken(t)); err != nil {
		t.Fatalf("Prepare after a contained panic: %v", err)
	}
}

// lastDiscoOkUnixSec lets Kotlin detect a dead gateway: it advances only on
// a successful DiscoPing, never on a failed one.
func TestLastDiscoOkAdvancesOnlyOnGatewayReply(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var fail atomic.Bool
	client := &mockTunnelClient{discoPingFn: func(context.Context) (*ipnstate.PingResult, error) {
		if fail.Load() {
			return nil, errors.New("gateway gone")
		}
		return &ipnstate.PingResult{LatencySeconds: 0.02}, nil
	}}
	b := &TunBridge{ctx: ctx, cancel: cancel, client: client, token: &ParsedToken{RegionID: 1}, transport: "DERP_RELAY"}

	b.lastDiscoOK.Store(100)
	b.sampleLiveRTT()
	ok := b.GetStats().LastDiscoOkSec
	if ok < time.Now().Unix()-5 {
		t.Fatalf("lastDiscoOkUnixSec = %d after a successful DiscoPing, want about now", ok)
	}

	b.lastDiscoOK.Store(100)
	fail.Store(true)
	b.sampleLiveRTT()
	stats := b.GetStats()
	if stats.LastDiscoOkSec != 100 || !stats.DiscoStale {
		t.Fatalf("after a failed DiscoPing: lastDiscoOk=%d discoStale=%v, want 100/true", stats.LastDiscoOkSec, stats.DiscoStale)
	}
}

func TestLatencyMsReportsSubMillisecondAsOne(t *testing.T) {
	for _, c := range []struct {
		seconds float64
		want    int64
	}{{0, 0}, {-1, 0}, {0.0004, 1}, {0.0123, 12}, {1.5, 1500}} {
		if got := latencyMs(c.seconds); got != c.want {
			t.Errorf("latencyMs(%v) = %d, want %d", c.seconds, got, c.want)
		}
	}
}
