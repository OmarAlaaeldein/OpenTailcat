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
)

// mockTunnelClient implements TunnelClient for test injection
type mockTunnelClient struct {
	mu          sync.Mutex
	dialUDPFn   func(ctx context.Context, dst netip.AddrPort) (net.Conn, error)
	dialTCPFn   func(ctx context.Context, dst netip.AddrPort) (net.Conn, error)
	discoPingFn func(ctx context.Context) (*ipnstate.PingResult, error)
	closed      bool
}

func (m *mockTunnelClient) DialUDP(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
	m.mu.Lock()
	fn := m.dialUDPFn
	m.mu.Unlock()
	if fn != nil {
		return fn(ctx, dst)
	}
	return nil, errors.New("mock dialUDP not configured")
}

func (m *mockTunnelClient) DialTCP(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
	m.mu.Lock()
	fn := m.dialTCPFn
	m.mu.Unlock()
	if fn != nil {
		return fn(ctx, dst)
	}
	return nil, errors.New("mock dialTCP not configured")
}

func (m *mockTunnelClient) Close() error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.closed = true
	return nil
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
	b.ipv6Egress.Store(true)

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

// TestBehavioralFlowLimitsAndBackpressure verifies the device-wide UDP flow
// cap. Every app shares the single TUN address, so a full table must evict
// the least recently active flow and admit the new one instead of refusing
// it (which used to block all UDP, DNS included, for up to 40 s).
func TestBehavioralFlowLimitsAndBackpressure(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	var activeDials atomic.Int32
	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			activeDials.Add(1)
			<-ctx.Done()
			return nil, ctx.Err()
		},
	}

	b := &TunBridge{ctx: ctx, cancel: cancel, client: mockClient, token: &ParsedToken{RegionID: 1}}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	b.netstack = proxy

	srcAddr := netip.MustParseAddr("100.64.0.2") // the real, shared TUN address
	dstAP := netip.MustParseAddrPort("1.1.1.1:443")

	for i := 0; i < maxActiveUDPFlows; i++ {
		srcAP := netip.AddrPortFrom(srcAddr, uint16(10000+i))
		proxy.inject(buildIPv4UDPPacket(srcAP, dstAP, []byte(fmt.Sprintf("flow-%d", i))), false)
	}
	waitAtomic32(t, &activeDials, int32(maxActiveUDPFlows), 5*time.Second, "DialUDP up to the cap")

	proxy.udpMu.Lock()
	total := proxy.udpActiveTotal
	proxy.udpMu.Unlock()
	if total != maxActiveUDPFlows {
		t.Fatalf("expected %d active flows at the cap, got %d", maxActiveUDPFlows, total)
	}

	oldest := udpFlowKey{src: netip.AddrPortFrom(srcAddr, 10000), dst: dstAP}
	newest := udpFlowKey{src: netip.AddrPortFrom(srcAddr, 30000), dst: dstAP}
	proxy.inject(buildIPv4UDPPacket(newest.src, dstAP, []byte("over-cap")), false)
	waitAtomic32(t, &activeDials, int32(maxActiveUDPFlows+1), 2*time.Second, "DialUDP for the flow over the cap")

	if got := b.udpEvictions.Load(); got != 1 {
		t.Fatalf("udpEvictions = %d, want 1", got)
	}
	if got := b.queueExhaustion.Load(); got != 0 {
		t.Fatalf("queueExhaustion = %d, want 0 (the new flow must be admitted)", got)
	}
	proxy.udpMu.Lock()
	total = proxy.udpActiveTotal
	_, oldestPresent := proxy.udpFlows[oldest]
	_, newestPresent := proxy.udpFlows[newest]
	proxy.udpMu.Unlock()
	if total != maxActiveUDPFlows {
		t.Fatalf("expected %d active flows after eviction, got %d", maxActiveUDPFlows, total)
	}
	if oldestPresent || !newestPresent {
		t.Fatalf("expected the least recently active flow evicted and the new one admitted (oldest=%v newest=%v)", oldestPresent, newestPresent)
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

	// The failed dial rolls back on the flow goroutine; poll instead of a
	// fixed sleep, which flaked under -race on a loaded machine.
	var total int
	for deadline := time.Now().Add(3 * time.Second); ; {
		proxy.udpMu.Lock()
		total = proxy.udpActiveTotal
		proxy.udpMu.Unlock()
		if total == 0 || time.Now().After(deadline) {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}

	if total != 0 {
		t.Errorf("Expected udpActiveTotal = 0 after dial failure rollback, got %d", total)
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
	proxy.udpMu.Unlock()

	if total != 0 {
		t.Fatalf("Counter did not reach exactly zero after Close(): got %d", total)
	}
	if flowsLen != 0 {
		t.Fatalf("Flow map not empty after Close(): %d flows remaining", flowsLen)
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
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) && tunneledDialCount.Load() < 1 {
		time.Sleep(10 * time.Millisecond)
	}
	if tunneledDialCount.Load() != 1 {
		t.Errorf("Expected exactly 1 tunneled DialUDP call, got %d", tunneledDialCount.Load())
	}
}

// TestMTUBoundaryDatagramSize checks the tunnel's UDP payload boundary on the
// proxy path (datagrams reassembled from IP fragments arrive here): 1232 B,
// Tailcat's MaxUDPPayload, is forwarded; 1233 B is dropped and counted
// instead of being silently lost inside the tunnel.
func TestMTUBoundaryDatagramSize(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	clientConn, remoteEcho := newPairedDatagramConns()
	defer clientConn.Close()
	defer remoteEcho.Close()

	received := make(chan int, 4)
	go func() {
		buf := make([]byte, 65535)
		for {
			n, err := remoteEcho.Read(buf)
			if err != nil {
				return
			}
			received <- n
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

	srcAP := netip.MustParseAddrPort("100.64.0.2:12345")
	dstAP := netip.MustParseAddrPort("1.1.1.1:3478")

	tooBig := make([]byte, maxTunnelUDPPayload+1)
	_, _ = rand.Read(tooBig)
	proxy.inject(buildIPv4UDPPacket(srcAP, dstAP, tooBig), false)

	maxPayload := make([]byte, maxTunnelUDPPayload)
	_, _ = rand.Read(maxPayload)
	proxy.inject(buildIPv4UDPPacket(srcAP, dstAP, maxPayload), false)

	packet := proxy.link.ReadContext(ctx)
	if packet == nil {
		t.Fatal("Expected max payload response packet")
	}
	view := packet.ToView()
	reply := append([]byte(nil), view.AsSlice()...)
	view.Release()
	packet.DecRef()

	if want := 20 + 8 + maxTunnelUDPPayload; len(reply) != want {
		t.Errorf("Expected %d byte reply packet, got %d", want, len(reply))
	}
	select {
	case n := <-received:
		if n != maxTunnelUDPPayload {
			t.Errorf("Remote received %d bytes first, want %d (the oversized datagram must not be forwarded)", n, maxTunnelUDPPayload)
		}
	default:
		t.Fatal("remote received nothing")
	}
	select {
	case n := <-received:
		t.Fatalf("remote received an extra %d byte datagram", n)
	default:
	}
	if got := b.mtuExceeded.Load(); got != 1 {
		t.Fatalf("mtuExceeded = %d, want 1", got)
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

		select {
		case <-closeDone:
		case <-time.After(2 * time.Second):
			close(releaseDial)
			t.Fatal("Close did not return after cancelling a pending DialUDP")
		}
		close(releaseDial)
		<-injectDone
		cancel()

		proxy.udpMu.Lock()
		total := proxy.udpActiveTotal
		flows := len(proxy.udpFlows)
		proxy.udpMu.Unlock()
		if total != 0 || flows != 0 {
			t.Fatalf("shutdown leaked UDP accounting: total=%d flows=%d", total, flows)
		}
	}
}

func TestUDPDialDoesNotBlockInject(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var started atomic.Int32
	release := make(chan struct{})
	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			started.Add(1)
			select {
			case <-release:
			case <-ctx.Done():
				return nil, ctx.Err()
			}
			c1, _ := newPairedDatagramConns()
			return c1, nil
		},
	}
	bridge := &TunBridge{ctx: ctx, cancel: cancel, client: mockClient, token: &ParsedToken{RegionID: 1}}
	proxy, err := newNetstackProxy(bridge)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	bridge.netstack = proxy

	query := []byte("udp-block-test")
	proxy.inject(buildIPv4UDPPacket(netip.MustParseAddrPort("10.0.0.2:40001"), netip.MustParseAddrPort("1.1.1.1:53"), query), false)
	proxy.inject(buildIPv4UDPPacket(netip.MustParseAddrPort("10.0.0.2:40002"), netip.MustParseAddrPort("1.1.1.1:53"), query), false)

	deadline := time.Now().Add(500 * time.Millisecond)
	for time.Now().Before(deadline) && started.Load() < 2 {
		time.Sleep(5 * time.Millisecond)
	}
	if started.Load() < 2 {
		t.Fatalf("second UDP flow blocked behind DialUDP, started=%d", started.Load())
	}
	close(release)
}

func waitAtomic32(t *testing.T, v *atomic.Int32, want int32, timeout time.Duration, what string) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if v.Load() >= want {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s (got %d, want %d)", what, v.Load(), want)
}
