package engine

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

// mockTunnelClient implements TunnelClient for test injection
type mockTunnelClient struct {
	mu          sync.Mutex
	dialUDPFn   func(ctx context.Context, dst netip.AddrPort) (net.Conn, error)
	dialTCPFn   func(ctx context.Context, dst netip.AddrPort) (net.Conn, error)
	discoPingFn func(ctx context.Context) (*ipnstate.PingResult, error)
	statusFn    func() *ipnstate.Status
	nodeKey     key.NodePublic
	derpMap     *tailcfg.DERPMap
	serverCaps  uint8
	closed      bool
}

func (m *mockTunnelClient) DialUDP(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.dialUDPFn != nil {
		return m.dialUDPFn(ctx, dst)
	}
	return nil, errors.New("mock dialUDP not configured")
}

func (m *mockTunnelClient) DialTCP(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.dialTCPFn != nil {
		return m.dialTCPFn(ctx, dst)
	}
	return nil, errors.New("mock dialTCP not configured")
}

func (m *mockTunnelClient) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.closed = true
	return nil
}

func (m *mockTunnelClient) Status() *ipnstate.Status {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.statusFn != nil {
		return m.statusFn()
	}
	return nil
}

func (m *mockTunnelClient) ServerNodeKey() key.NodePublic {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.nodeKey
}

func (m *mockTunnelClient) DERPMap() *tailcfg.DERPMap {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.derpMap
}

func (m *mockTunnelClient) DiscoPing(ctx context.Context) (*ipnstate.PingResult, error) {
	m.mu.Lock()
	fn := m.discoPingFn
	m.mu.Unlock()
	if fn != nil {
		return fn(ctx)
	}
	return nil, errors.New("mock disco ping not configured")
}

// pairedDatagramConn connects local and remote ends in memory for datagram tests
type pairedDatagramConn struct {
	in      chan []byte
	out     chan []byte
	closed  atomic.Bool
	closeMu sync.Once
}

func newPairedDatagramConns() (local *pairedDatagramConn, remote *pairedDatagramConn) {
	c1 := make(chan []byte, 128)
	c2 := make(chan []byte, 128)
	local = &pairedDatagramConn{in: c1, out: c2}
	remote = &pairedDatagramConn{in: c2, out: c1}
	return local, remote
}

func (p *pairedDatagramConn) Read(b []byte) (int, error) {
	if p.closed.Load() {
		return 0, net.ErrClosed
	}
	pkt, ok := <-p.in
	if !ok || p.closed.Load() {
		return 0, net.ErrClosed
	}
	copy(b, pkt)
	return len(pkt), nil
}

func (p *pairedDatagramConn) Write(b []byte) (int, error) {
	if p.closed.Load() {
		return 0, net.ErrClosed
	}
	pkt := append([]byte(nil), b...)
	select {
	case p.out <- pkt:
		return len(b), nil
	default:
		return len(b), nil
	}
}

func (p *pairedDatagramConn) Close() error {
	p.closeMu.Do(func() {
		p.closed.Store(true)
		close(p.out)
	})
	return nil
}

func (p *pairedDatagramConn) LocalAddr() net.Addr {
	return &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 1000}
}
func (p *pairedDatagramConn) RemoteAddr() net.Addr {
	return &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: 2000}
}
func (p *pairedDatagramConn) SetDeadline(t time.Time) error      { return nil }
func (p *pairedDatagramConn) SetReadDeadline(t time.Time) error  { return nil }
func (p *pairedDatagramConn) SetWriteDeadline(t time.Time) error { return nil }

// TestCompleteIPv4TUNRoundTrip verifies that an IPv4 UDP packet injected into the TUN
// passes through gVisor, dials the injected TunnelClient, receives an echo response,
// and returns with valid IPv4 and UDP headers and matching payload.
func TestCompleteIPv4TUNRoundTrip(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	clientConn, remoteEcho := newPairedDatagramConns()
	defer clientConn.Close()
	defer remoteEcho.Close()

	// Remote echo server echoing incoming datagrams back
	go func() {
		buf := make([]byte, 65535)
		for {
			n, err := remoteEcho.Read(buf)
			if err != nil {
				return
			}
			_, _ = remoteEcho.Write(buf[:n])
		}
	}()

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return clientConn, nil
		},
	}

	b := &TunBridge{
		ctx:    ctx,
		cancel: cancel,
		client: mockClient,
		token:  &ParsedToken{RegionID: 1},
	}

	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy

	srcAP := netip.MustParseAddrPort("10.0.0.2:45678")
	dstAP := netip.MustParseAddrPort("1.1.1.1:53")
	payload := []byte("hello-tunneled-ipv4-udp")

	pkt := buildIPv4UDPPacket(srcAP, dstAP, payload)
	proxy.inject(pkt, false)

	// Read reply packet directly from proxy's link endpoint
	packet := proxy.link.ReadContext(ctx)
	if packet == nil {
		t.Fatal("Expected reply packet from netstack link endpoint, got nil")
	}
	view := packet.ToView()
	reply := append([]byte(nil), view.AsSlice()...)
	view.Release()
	packet.DecRef()

	if len(reply) < 28 {
		t.Fatalf("Reply too short (%d bytes)", len(reply))
	}

	// Verify IPv4 header
	if reply[0]>>4 != 4 {
		t.Errorf("Expected IPv4 version 4, got %d", reply[0]>>4)
	}
	replySrcIP, _ := netip.AddrFromSlice(reply[12:16])
	replyDstIP, _ := netip.AddrFromSlice(reply[16:20])
	if replySrcIP != dstAP.Addr() || replyDstIP != srcAP.Addr() {
		t.Errorf("Address mismatch: src=%v dst=%v (want src=%v dst=%v)", replySrcIP, replyDstIP, dstAP.Addr(), srcAP.Addr())
	}

	// Verify UDP header & payload
	udpHeader := reply[20:]
	replySrcPort := binary.BigEndian.Uint16(udpHeader[0:2])
	replyDstPort := binary.BigEndian.Uint16(udpHeader[2:4])
	if replySrcPort != dstAP.Port() || replyDstPort != srcAP.Port() {
		t.Errorf("Port mismatch: src=%d dst=%d (want src=%d dst=%d)", replySrcPort, replyDstPort, dstAP.Port(), srcAP.Port())
	}
	gotPayload := udpHeader[8:]
	if !bytes.Equal(gotPayload, payload) {
		t.Errorf("Payload mismatch: got %q, want %q", string(gotPayload), string(payload))
	}
}

// TestCompleteIPv6TUNRoundTrip verifies that an IPv6 UDP datagram injected into the TUN
// passes through gVisor, dials the injected TunnelClient, and returns with a valid IPv6 UDP header.
func TestCompleteIPv6TUNRoundTrip(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	clientConn, remoteEcho := newPairedDatagramConns()
	defer clientConn.Close()
	defer remoteEcho.Close()

	go func() {
		buf := make([]byte, 65535)
		for {
			n, err := remoteEcho.Read(buf)
			if err != nil {
				return
			}
			_, _ = remoteEcho.Write(buf[:n])
		}
	}()

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return clientConn, nil
		},
	}

	b := &TunBridge{
		ctx:    ctx,
		cancel: cancel,
		client: mockClient,
		token:  &ParsedToken{RegionID: 1},
	}

	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy

	srcV6 := netip.MustParseAddrPort("[fd7a:115c:a1e0::2]:55555")
	dstV6 := netip.MustParseAddrPort("[2606:4700:4700::1111]:53")
	payload := []byte("ipv6-tunneled-udp-roundtrip")

	pkt := buildIPv6UDPPacket(srcV6, dstV6, payload)
	proxy.inject(pkt, true)

	packet := proxy.link.ReadContext(ctx)
	if packet == nil {
		t.Fatal("Expected reply packet from netstack link endpoint, got nil")
	}
	view := packet.ToView()
	reply := append([]byte(nil), view.AsSlice()...)
	view.Release()
	packet.DecRef()

	if len(reply) < 48 {
		t.Fatalf("IPv6 reply too short (%d bytes)", len(reply))
	}
	if reply[0]>>4 != 6 {
		t.Errorf("Expected IPv6 version 6, got %d", reply[0]>>4)
	}

	udpHeader := reply[40:]
	gotPayload := udpHeader[8:]
	if !bytes.Equal(gotPayload, payload) {
		t.Errorf("Payload mismatch: got %q, want %q", string(gotPayload), string(payload))
	}
}

// TestZeroLengthDatagramRoundTrip verifies that zero-length UDP datagrams are preserved end to end.
func TestZeroLengthDatagramRoundTrip(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	clientConn, remoteEcho := newPairedDatagramConns()
	defer clientConn.Close()
	defer remoteEcho.Close()

	var receivedZeroLength atomic.Bool
	go func() {
		buf := make([]byte, 65535)
		for {
			n, err := remoteEcho.Read(buf)
			if err != nil {
				return
			}
			if n == 0 {
				receivedZeroLength.Store(true)
			}
			_, _ = remoteEcho.Write(buf[:n])
		}
	}()

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return clientConn, nil
		},
	}

	b := &TunBridge{
		ctx:    ctx,
		cancel: cancel,
		client: mockClient,
		token:  &ParsedToken{RegionID: 1},
	}

	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy

	srcAP := netip.MustParseAddrPort("10.0.0.2:45678")
	dstAP := netip.MustParseAddrPort("1.1.1.1:53")
	pktZero := buildIPv4UDPPacket(srcAP, dstAP, []byte{})

	proxy.inject(pktZero, false)

	packet := proxy.link.ReadContext(ctx)
	if packet == nil {
		t.Fatal("Expected zero-length reply packet from netstack link endpoint, got nil")
	}
	view := packet.ToView()
	reply := append([]byte(nil), view.AsSlice()...)
	view.Release()
	packet.DecRef()

	if len(reply) != 28 { // 20 IP + 8 UDP + 0 payload
		t.Errorf("Expected 28-byte zero payload packet, got %d bytes", len(reply))
	}
	if !receivedZeroLength.Load() {
		t.Errorf("Remote echo server did not receive zero-length datagram")
	}
}

// TestQUICFramedInitialDatagram verifies that structurally framed QUIC v1 Initial datagrams with
// TLS ClientHello frames are routed transparently through the proxy stack.
func TestQUICFramedInitialDatagram(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	clientConn, remoteEcho := newPairedDatagramConns()
	defer clientConn.Close()
	defer remoteEcho.Close()

	// Construct genuine QUIC v1 Initial Packet:
	// Long Header: Header Form (1) | Fixed Bit (1) | Long Packet Type: Initial (00) | Reserved (00) | Packet Number Length (00) -> 0xC0
	// Version: 0x00000001 (QUIC version 1)
	// DCIL / SCIL, Connection IDs, Token Length, Length, Packet Number, Payload (CRYPTO frame)
	quicInitial := []byte{
		0xc0,                   // Header byte: Long header, Initial packet
		0x00, 0x00, 0x00, 0x01, // QUIC Version 1
		0x08,                                           // DCIL: 8 bytes
		0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, // Destination Connection ID
		0x08,                                           // SCIL: 8 bytes
		0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, // Source Connection ID
		0x00,       // Token Length (0)
		0x40, 0x20, // Length (varint 32 bytes)
		0x00, 0x01, // Packet number
		0x06,       // Frame Type: CRYPTO
		0x00, 0x1c, // Offset + Length
		0x01, 0x00, 0x00, 0x18, 0x03, 0x03, // TLS ClientHello prefix
	}
	// Pad to 1200 bytes per QUIC RFC 9000 minimum Initial size
	quicPktPayload := make([]byte, 1200)
	copy(quicPktPayload, quicInitial)

	go func() {
		buf := make([]byte, 65535)
		for {
			n, err := remoteEcho.Read(buf)
			if err != nil {
				return
			}
			// Echo reply with Server Initial / Handshake simulation
			resp := append([]byte(nil), buf[:n]...)
			if len(resp) > 0 {
				resp[0] = 0xc2 // Initial -> Handshake type
			}
			_, _ = remoteEcho.Write(resp)
		}
	}()

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return clientConn, nil
		},
	}

	b := &TunBridge{
		ctx:    ctx,
		cancel: cancel,
		client: mockClient,
		token:  &ParsedToken{RegionID: 1},
	}

	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy

	srcAP := netip.MustParseAddrPort("10.0.0.2:4433")
	dstAP := netip.MustParseAddrPort("1.1.1.1:443")

	pkt := buildIPv4UDPPacket(srcAP, dstAP, quicPktPayload)
	proxy.inject(pkt, false)

	packet := proxy.link.ReadContext(ctx)
	if packet == nil {
		t.Fatal("Expected QUIC response from netstack link endpoint")
	}
	view := packet.ToView()
	reply := append([]byte(nil), view.AsSlice()...)
	view.Release()
	packet.DecRef()

	gotPayload := reply[28:]
	if len(gotPayload) != len(quicPktPayload) {
		t.Fatalf("QUIC payload length mismatch: got %d, want %d", len(gotPayload), len(quicPktPayload))
	}
	if gotPayload[0] != 0xc2 {
		t.Errorf("QUIC response header byte mismatch: got %x, want 0xc2", gotPayload[0])
	}
}

// TestBehavioralFlowLimitsAndBackpressure verifies that active flow limits reject packets
// at both the per-source (128) and global (1024) limits without leaking state.
func TestBehavioralFlowLimitsAndBackpressure(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	var activeDials atomic.Int32
	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			activeDials.Add(1)
			c1, _ := newPairedDatagramConns()
			return c1, nil
		},
	}

	b := &TunBridge{ctx: ctx, cancel: cancel, client: mockClient, token: &ParsedToken{RegionID: 1}}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy

	srcAddr := netip.MustParseAddr("10.0.0.2")
	dstAP := netip.MustParseAddrPort("1.1.1.1:53")

	// 1. Inject maxActiveUDPFlowsPerSource (128) flows from source 10.0.0.2
	for i := 0; i < maxActiveUDPFlowsPerSource; i++ {
		srcAP := netip.AddrPortFrom(srcAddr, uint16(10000+i))
		pkt := buildIPv4UDPPacket(srcAP, dstAP, []byte(fmt.Sprintf("flow-%d", i)))
		proxy.inject(pkt, false)
	}

	time.Sleep(100 * time.Millisecond)

	proxy.udpMu.Lock()
	activeCount := proxy.udpActivePerSource[srcAddr]
	proxy.udpMu.Unlock()

	if activeCount != maxActiveUDPFlowsPerSource {
		t.Fatalf("Expected %d active flows for source, got %d", maxActiveUDPFlowsPerSource, activeCount)
	}

	// 2. The 129th flow from the same source MUST be rejected (backpressure drop)
	initialDials := activeDials.Load()
	pktRejected := buildIPv4UDPPacket(netip.AddrPortFrom(srcAddr, 25000), dstAP, []byte("overflow-flow"))
	proxy.inject(pktRejected, false)
	time.Sleep(50 * time.Millisecond)

	if activeDials.Load() != initialDials {
		t.Errorf("129th flow from source was not rejected; dial count increased from %d to %d", initialDials, activeDials.Load())
	}

	// 3. Fill the global limit to 1024 across 8 distinct sources (8 * 128 = 1024)
	for srcIdx := 3; srcIdx <= 9; srcIdx++ {
		curSrc := netip.MustParseAddr(fmt.Sprintf("10.0.0.%d", srcIdx))
		for i := 0; i < maxActiveUDPFlowsPerSource; i++ {
			srcAP := netip.AddrPortFrom(curSrc, uint16(10000+i))
			pkt := buildIPv4UDPPacket(srcAP, dstAP, []byte(fmt.Sprintf("global-flow-%d-%d", srcIdx, i)))
			proxy.inject(pkt, false)
		}
	}

	time.Sleep(200 * time.Millisecond)

	proxy.udpMu.Lock()
	total := proxy.udpActiveTotal
	proxy.udpMu.Unlock()

	if total != maxActiveUDPFlowsTotal {
		t.Fatalf("Expected %d total active flows at global limit, got %d", maxActiveUDPFlowsTotal, total)
	}

	// 4. Flow from a 9th source (10.0.0.10) MUST be rejected by global limit
	dialsBeforeGlobalOverflow := activeDials.Load()
	pktGlobalRejected := buildIPv4UDPPacket(netip.MustParseAddrPort("10.0.0.10:10000"), dstAP, []byte("global-overflow"))
	proxy.inject(pktGlobalRejected, false)
	time.Sleep(50 * time.Millisecond)

	if activeDials.Load() != dialsBeforeGlobalOverflow {
		t.Errorf("Global limit overflow flow was not rejected; dial count increased from %d to %d", dialsBeforeGlobalOverflow, activeDials.Load())
	}
}

// TestFlowReservationRollbackOnDialFailure verifies that dial failures cleanly
// roll back reserved counter quotas.
func TestFlowReservationRollbackOnDialFailure(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return nil, errors.New("gateway rejected UDP dial (unreachable)")
		},
	}

	b := &TunBridge{ctx: ctx, cancel: cancel, client: mockClient, token: &ParsedToken{RegionID: 1}}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy

	srcAP := netip.MustParseAddrPort("10.0.0.2:12345")
	dstAP := netip.MustParseAddrPort("1.1.1.1:53")

	pkt := buildIPv4UDPPacket(srcAP, dstAP, []byte("fail-dial-test"))
	proxy.inject(pkt, false)
	time.Sleep(50 * time.Millisecond)

	proxy.udpMu.Lock()
	total := proxy.udpActiveTotal
	srcCount := proxy.udpActivePerSource[srcAP.Addr()]
	proxy.udpMu.Unlock()

	if total != 0 {
		t.Errorf("Expected udpActiveTotal = 0 after dial failure rollback, got %d", total)
	}
	if srcCount != 0 {
		t.Errorf("Expected per-source count = 0 after dial failure rollback, got %d", srcCount)
	}
}

// TestBehavioralConcurrentClose verifies that active pumping flows terminate cleanly
// on proxy Close() without leaks, double-decrements, or negative accounting, reaching exactly 0.
func TestBehavioralConcurrentClose(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	var activeConns []*pairedDatagramConn
	var connMu sync.Mutex

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			c1, c2 := newPairedDatagramConns()
			connMu.Lock()
			activeConns = append(activeConns, c2)
			connMu.Unlock()
			return c1, nil
		},
	}

	b := &TunBridge{ctx: ctx, cancel: cancel, client: mockClient, token: &ParsedToken{RegionID: 1}}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	b.netstack = proxy

	// Start 10 active pumping flows
	dstAP := netip.MustParseAddrPort("1.1.1.1:53")
	for i := 0; i < 10; i++ {
		srcAP := netip.AddrPortFrom(netip.MustParseAddr("10.0.0.2"), uint16(30000+i))
		pkt := buildIPv4UDPPacket(srcAP, dstAP, []byte(fmt.Sprintf("pump-data-%d", i)))
		proxy.inject(pkt, false)
	}
	time.Sleep(50 * time.Millisecond)

	// Pump continuous responses from remote ends
	stopPump := make(chan struct{})
	var pumpWg sync.WaitGroup
	connMu.Lock()
	for _, c := range activeConns {
		remote := c
		pumpWg.Add(1)
		go func() {
			defer pumpWg.Done()
			buf := make([]byte, 100)
			for {
				select {
				case <-stopPump:
					return
				default:
					_, _ = remote.Write([]byte("reply-datagram"))
					_, _ = remote.Read(buf)
					time.Sleep(1 * time.Millisecond)
				}
			}
		}()
	}
	connMu.Unlock()

	// Close proxy concurrently while traffic is pumping
	time.Sleep(20 * time.Millisecond)
	proxy.Close()
	close(stopPump)
	pumpWg.Wait()

	// Assert counters reach EXACTLY zero and all flow maps are empty
	proxy.udpMu.Lock()
	total := proxy.udpActiveTotal
	flowsLen := len(proxy.udpFlows)
	perSourceLen := len(proxy.udpActivePerSource)
	proxy.udpMu.Unlock()

	if total != 0 {
		t.Fatalf("Counter did not reach exactly zero after Close(): got %d", total)
	}
	if flowsLen != 0 {
		t.Fatalf("Flow map not empty after Close(): %d flows remaining", flowsLen)
	}
	if perSourceLen != 0 {
		t.Fatalf("Per-source map not empty after Close(): %d sources remaining", perSourceLen)
	}
}

// TestTunnelClientPathSelection proves that application datagrams are handled exclusively
// through the userspace netstack / injected TunnelClient.
func TestTunnelClientPathSelection(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var tunneledDialCount atomic.Int32
	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			tunneledDialCount.Add(1)
			c1, _ := newPairedDatagramConns()
			return c1, nil
		},
	}

	b := &TunBridge{ctx: ctx, cancel: cancel, client: mockClient, token: &ParsedToken{RegionID: 1}}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy

	// Inject packet addressed to public DNS 8.8.8.8:53
	srcAP := netip.MustParseAddrPort("10.0.0.2:54321")
	dstAP := netip.MustParseAddrPort("8.8.8.8:53")
	pkt := buildIPv4UDPPacket(srcAP, dstAP, []byte("isolated-tunneled-query"))

	proxy.inject(pkt, false)
	time.Sleep(50 * time.Millisecond)

	if tunneledDialCount.Load() != 1 {
		t.Errorf("Expected exactly 1 tunneled DialUDP call, got %d", tunneledDialCount.Load())
	}
}

// TestMTUBoundaryDatagramSize verifies handling of UDP datagrams at the 1280-byte MTU boundary.
func TestMTUBoundaryDatagramSize(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	clientConn, remoteEcho := newPairedDatagramConns()
	defer clientConn.Close()
	defer remoteEcho.Close()

	// 1252 byte payload fits within 1280 MTU (1280 - 20 IPv4 - 8 UDP = 1252)
	maxPayload := make([]byte, 1252)
	_, _ = rand.Read(maxPayload)

	var receivedLen atomic.Int32
	go func() {
		buf := make([]byte, 65535)
		for {
			n, err := remoteEcho.Read(buf)
			if err != nil {
				return
			}
			receivedLen.Store(int32(n))
			_, _ = remoteEcho.Write(buf[:n])
		}
	}()

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return clientConn, nil
		},
	}

	b := &TunBridge{ctx: ctx, cancel: cancel, client: mockClient, token: &ParsedToken{RegionID: 1}}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy

	srcAP := netip.MustParseAddrPort("10.0.0.2:12345")
	dstAP := netip.MustParseAddrPort("1.1.1.1:53")

	pkt := buildIPv4UDPPacket(srcAP, dstAP, maxPayload)
	proxy.inject(pkt, false)

	packet := proxy.link.ReadContext(ctx)
	if packet == nil {
		t.Fatal("Expected max payload response packet")
	}
	view := packet.ToView()
	reply := append([]byte(nil), view.AsSlice()...)
	view.Release()
	packet.DecRef()

	if len(reply) != 1280 {
		t.Errorf("Expected 1280 byte MTU packet, got %d", len(reply))
	}
	if int(receivedLen.Load()) != len(maxPayload) {
		t.Errorf("Remote received %d bytes, want %d", receivedLen.Load(), len(maxPayload))
	}
}

// TestAcceptCloseRace verifies that high-concurrency packet injection racing against Close()
// never causes panics, memory corruption, or goroutine leaks on destroyed stacks.
func TestAcceptCloseRace(t *testing.T) {
	for iter := 0; iter < 10; iter++ {
		ctx, cancel := context.WithCancel(context.Background())
		dialStarted := make(chan struct{})
		releaseDial := make(chan struct{})
		var signalDial sync.Once
		mockClient := &mockTunnelClient{
			dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
				signalDial.Do(func() { close(dialStarted) })
				select {
				case <-releaseDial:
				case <-ctx.Done():
					return nil, ctx.Err()
				}
				c1, _ := newPairedDatagramConns()
				return c1, nil
			},
		}

		b := &TunBridge{ctx: ctx, cancel: cancel, client: mockClient, token: &ParsedToken{RegionID: 1}}
		proxy, err := newNetstackProxy(b)
		if err != nil {
			t.Fatalf("newNetstackProxy: %v", err)
		}
		b.netstack = proxy

		injectDone := make(chan struct{})
		go func() {
			defer close(injectDone)
			srcAP := netip.AddrPortFrom(netip.MustParseAddr("10.0.0.2"), uint16(10000+iter))
			dstAP := netip.MustParseAddrPort("1.1.1.1:53")
			proxy.inject(buildIPv4UDPPacket(srcAP, dstAP, []byte("race-pkt")), false)
		}()

		select {
		case <-dialStarted:
		case <-time.After(2 * time.Second):
			t.Fatal("accept did not reach pending DialUDP")
		}

		closeDone := make(chan struct{})
		go func() {
			proxy.Close()
			close(closeDone)
		}()

		// Close must wait for the already-accounted pending accept.
		select {
		case <-closeDone:
			t.Fatal("Close returned while a reserved UDP accept was still pending")
		case <-time.After(10 * time.Millisecond):
		}

		close(releaseDial)
		<-injectDone
		<-closeDone
		cancel()

		proxy.udpMu.Lock()
		total := proxy.udpActiveTotal
		flows := len(proxy.udpFlows)
		sources := len(proxy.udpActivePerSource)
		proxy.udpMu.Unlock()
		if total != 0 || flows != 0 || sources != 0 {
			t.Fatalf("shutdown leaked UDP accounting: total=%d flows=%d sources=%d", total, flows, sources)
		}
	}
}

func TestDialTimeoutForIPv6IsShort(t *testing.T) {
	v6 := netip.MustParseAddrPort("[2606:4700:4700::1111]:443")
	v4 := netip.MustParseAddrPort("1.1.1.1:443")
	if got := dialTimeoutFor(v6, tcpDialTimeout); got != ipv6DialTimeout {
		t.Fatalf("ipv6 tcp timeout %v, want %v", got, ipv6DialTimeout)
	}
	if got := dialTimeoutFor(v4, tcpDialTimeout); got != tcpDialTimeout {
		t.Fatalf("ipv4 tcp timeout %v, want %v", got, tcpDialTimeout)
	}
	if got := dialTimeoutFor(v6, udpDialTimeout); got != ipv6DialTimeout {
		t.Fatalf("ipv6 udp timeout %v, want %v", got, ipv6DialTimeout)
	}
	if got := dialTimeoutFor(v4, udpDialTimeout); got != udpDialTimeout {
		t.Fatalf("ipv4 udp timeout %v, want %v", got, udpDialTimeout)
	}
}
