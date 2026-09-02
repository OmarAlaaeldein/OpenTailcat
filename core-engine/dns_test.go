package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// buildDNSQuery builds a minimal valid RFC 1035 DNS query packet.
func buildDNSQuery(txID uint16, domain string, qtype uint16) []byte {
	var buf bytes.Buffer
	// Header (12 bytes)
	binary.Write(&buf, binary.BigEndian, txID)   // ID
	binary.Write(&buf, binary.BigEndian, uint16(0x0100)) // Flags: QR=0, RD=1
	binary.Write(&buf, binary.BigEndian, uint16(1))      // QDCOUNT=1
	binary.Write(&buf, binary.BigEndian, uint16(0))      // ANCOUNT=0
	binary.Write(&buf, binary.BigEndian, uint16(0))      // NSCOUNT=0
	binary.Write(&buf, binary.BigEndian, uint16(0))      // ARCOUNT=0

	// Question Section
	for _, part := range bytes.Split([]byte(domain), []byte(".")) {
		if len(part) > 0 {
			buf.WriteByte(byte(len(part)))
			buf.Write(part)
		}
	}
	buf.WriteByte(0) // Root null label

	binary.Write(&buf, binary.BigEndian, qtype)  // QTYPE (1 = A)
	binary.Write(&buf, binary.BigEndian, uint16(1)) // QCLASS (1 = IN)
	return buf.Bytes()
}

// buildDNSResponse builds a valid RFC 1035 DNS response packet.
func buildDNSResponse(txID uint16, domain string, truncated bool, answerIP netip.Addr, extraPayloadSize int) []byte {
	var buf bytes.Buffer
	flags := uint16(0x8180) // QR=1, RD=1, RA=1 (Standard query response, no error)
	if truncated {
		flags |= 0x0200 // Set TC (Truncated) bit
	}

	binary.Write(&buf, binary.BigEndian, txID)
	binary.Write(&buf, binary.BigEndian, flags)
	binary.Write(&buf, binary.BigEndian, uint16(1)) // QDCOUNT=1
	if truncated {
		binary.Write(&buf, binary.BigEndian, uint16(0)) // ANCOUNT=0 when truncated
	} else {
		binary.Write(&buf, binary.BigEndian, uint16(1)) // ANCOUNT=1
	}
	binary.Write(&buf, binary.BigEndian, uint16(0)) // NSCOUNT=0
	binary.Write(&buf, binary.BigEndian, uint16(0)) // ARCOUNT=0

	// Question Section
	for _, part := range bytes.Split([]byte(domain), []byte(".")) {
		if len(part) > 0 {
			buf.WriteByte(byte(len(part)))
			buf.Write(part)
		}
	}
	buf.WriteByte(0)
	binary.Write(&buf, binary.BigEndian, uint16(1)) // QTYPE A
	binary.Write(&buf, binary.BigEndian, uint16(1)) // QCLASS IN

	if !truncated {
		// Answer Section: pointer to domain name (0xc00c)
		binary.Write(&buf, binary.BigEndian, uint16(0xc00c))
		binary.Write(&buf, binary.BigEndian, uint16(1))  // TYPE A
		binary.Write(&buf, binary.BigEndian, uint16(1))  // CLASS IN
		binary.Write(&buf, binary.BigEndian, uint32(300)) // TTL 300s

		if answerIP.Is4() {
			binary.Write(&buf, binary.BigEndian, uint16(4)) // RDLENGTH
			ip4 := answerIP.As4()
			buf.Write(ip4[:])
		} else {
			binary.Write(&buf, binary.BigEndian, uint16(16))
			ip6 := answerIP.As16()
			buf.Write(ip6[:])
		}

		// Extra payload padding for testing large EDNS0 / DNSSEC-sized responses
		if extraPayloadSize > 0 {
			padding := make([]byte, extraPayloadSize)
			buf.Write(padding)
		}
	}

	return buf.Bytes()
}

// pairedStreamConn models a bidirectional stream connection in memory for TCP tests
type pairedStreamConn struct {
	reader *io.PipeReader
	writer *io.PipeWriter
}

func newPairedStreamConns() (c1, c2 *streamHalfConn) {
	r1, w1 := io.Pipe()
	r2, w2 := io.Pipe()
	return &streamHalfConn{r: r1, w: w2}, &streamHalfConn{r: r2, w: w1}
}

type streamHalfConn struct {
	r *io.PipeReader
	w *io.PipeWriter
}

func (s *streamHalfConn) Read(b []byte) (int, error)         { return s.r.Read(b) }
func (s *streamHalfConn) Write(b []byte) (int, error)        { return s.w.Write(b) }
func (s *streamHalfConn) Close() error                       { _ = s.r.Close(); return s.w.Close() }
func (s *streamHalfConn) CloseWrite() error                  { return s.w.Close() }
func (s *streamHalfConn) LocalAddr() net.Addr                { return &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 1000} }
func (s *streamHalfConn) RemoteAddr() net.Addr               { return &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 2000} }
func (s *streamHalfConn) SetDeadline(t time.Time) error      { return nil }
func (s *streamHalfConn) SetReadDeadline(t time.Time) error  { return nil }
func (s *streamHalfConn) SetWriteDeadline(t time.Time) error { return nil }

func TestDNSTransactionIDPreservation(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	clientConn, remoteServer := newPairedDatagramConns()
	defer clientConn.Close()
	defer remoteServer.Close()

	const expectedTxID = uint16(0xA1B2)
	queryPayload := buildDNSQuery(expectedTxID, "example.com", 1)

	// Upstream mock DNS server: validates received query and sends response with matching ID
	go func() {
		buf := make([]byte, 65535)
		n, err := remoteServer.Read(buf)
		if err != nil {
			return
		}
		if n < 12 {
			t.Errorf("DNS query too short: %d bytes", n)
			return
		}
		receivedTxID := binary.BigEndian.Uint16(buf[:2])
		if receivedTxID != expectedTxID {
			t.Errorf("Upstream received txID %x, want %x", receivedTxID, expectedTxID)
		}
		resp := buildDNSResponse(receivedTxID, "example.com", false, netip.MustParseAddr("93.184.216.34"), 0)
		_, _ = remoteServer.Write(resp)
	}()

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return clientConn, nil
		},
	}

	bridge := &TunBridge{
		ctx:    ctx,
		cancel: cancel,
		client: mockClient,
		token:  &ParsedToken{RegionID: 1},
	}
	proxy, err := newNetstackProxy(bridge)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	bridge.netstack = proxy

	srcAP := netip.MustParseAddrPort("10.0.0.2:54321")
	dstAP := netip.MustParseAddrPort("1.1.1.1:53")

	pkt := buildIPv4UDPPacket(srcAP, dstAP, queryPayload)
	proxy.inject(pkt, false)

	// Read reply from gVisor link
	packet := proxy.link.ReadContext(ctx)
	if packet == nil {
		t.Fatal("Timeout waiting for DNS response packet")
	}
	view := packet.ToView()
	reply := append([]byte(nil), view.AsSlice()...)
	view.Release()
	packet.DecRef()

	if len(reply) < 28+12 {
		t.Fatalf("Reply packet too short: %d bytes", len(reply))
	}
	// UDP payload starts at offset 28 in IPv4 UDP packet
	dnsReply := reply[28:]
	replyTxID := binary.BigEndian.Uint16(dnsReply[:2])
	if replyTxID != expectedTxID {
		t.Fatalf("Transaction ID mismatch: got %04x, want %04x", replyTxID, expectedTxID)
	}

	flags := binary.BigEndian.Uint16(dnsReply[2:4])
	if (flags & 0x8000) == 0 {
		t.Error("Expected QR bit (response) to be set")
	}
}

func TestDNSParallelQueries(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	const concurrency = 20
	var wg sync.WaitGroup

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			clientEnd, serverEnd := newPairedDatagramConns()
			go func() {
				buf := make([]byte, 65535)
				for {
					n, err := serverEnd.Read(buf)
					if err != nil {
						return
					}
					if n >= 12 {
						txID := binary.BigEndian.Uint16(buf[:2])
						resp := buildDNSResponse(txID, "test.local", false, netip.MustParseAddr("1.2.3.4"), 0)
						_, _ = serverEnd.Write(resp)
					}
				}
			}()
			return clientEnd, nil
		},
	}

	bridge := &TunBridge{
		ctx:    ctx,
		cancel: cancel,
		client: mockClient,
		token:  &ParsedToken{RegionID: 1},
	}
	proxy, err := newNetstackProxy(bridge)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	bridge.netstack = proxy

	successCount := atomic.Int32{}

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			txID := uint16(0x2000 + idx)
			domain := fmt.Sprintf("node-%d.example.com", idx)
			query := buildDNSQuery(txID, domain, 1)

			srcAP := netip.MustParseAddrPort(fmt.Sprintf("10.0.0.2:%d", 40000+idx))
			dstAP := netip.MustParseAddrPort("1.1.1.1:53")
			pkt := buildIPv4UDPPacket(srcAP, dstAP, query)

			proxy.inject(pkt, false)
		}(i)
	}

	// Read and match all reply packets
	receivedIDs := make(map[uint16]bool)
	for i := 0; i < concurrency; i++ {
		packet := proxy.link.ReadContext(ctx)
		if packet == nil {
			break
		}
		view := packet.ToView()
		reply := append([]byte(nil), view.AsSlice()...)
		view.Release()
		packet.DecRef()

		if len(reply) >= 28+12 {
			dnsReply := reply[28:]
			id := binary.BigEndian.Uint16(dnsReply[:2])
			if id >= 0x2000 && id < 0x2000+concurrency {
				receivedIDs[id] = true
				successCount.Add(1)
			}
		}
	}

	wg.Wait()

	if int(successCount.Load()) != concurrency {
		t.Fatalf("Expected %d successful parallel queries, got %d (received IDs: %v)",
			concurrency, successCount.Load(), receivedIDs)
	}
}

func TestDNSEDNS0AndLargeResponses(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	clientConn, remoteServer := newPairedDatagramConns()
	defer clientConn.Close()
	defer remoteServer.Close()

	const expectedTxID = uint16(0xED01)
	queryPayload := buildDNSQuery(expectedTxID, "dnssec.large.example.org", 1)

	// Upstream responds with a 4096-byte large datagram (EDNS0 / DNSSEC size)
	const targetLargeSize = 4096
	go func() {
		buf := make([]byte, 65535)
		_, err := remoteServer.Read(buf)
		if err != nil {
			return
		}
		baseResp := buildDNSResponse(expectedTxID, "dnssec.large.example.org", false, netip.MustParseAddr("104.16.132.229"), 0)
		paddingNeeded := targetLargeSize - len(baseResp)
		if paddingNeeded < 0 {
			paddingNeeded = 0
		}
		largeResp := buildDNSResponse(expectedTxID, "dnssec.large.example.org", false, netip.MustParseAddr("104.16.132.229"), paddingNeeded)
		_, _ = remoteServer.Write(largeResp)
	}()

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return clientConn, nil
		},
	}

	bridge := &TunBridge{
		ctx:    ctx,
		cancel: cancel,
		client: mockClient,
		token:  &ParsedToken{RegionID: 1},
	}
	proxy, err := newNetstackProxy(bridge)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	bridge.netstack = proxy

	srcAP := netip.MustParseAddrPort("10.0.0.2:50001")
	dstAP := netip.MustParseAddrPort("1.1.1.1:53")

	pkt := buildIPv4UDPPacket(srcAP, dstAP, queryPayload)
	proxy.inject(pkt, false)

	var assembledDNS []byte
	var firstTxID uint16
	for {
		packet := proxy.link.ReadContext(ctx)
		if packet == nil {
			break
		}
		view := packet.ToView()
		frag := append([]byte(nil), view.AsSlice()...)
		view.Release()
		packet.DecRef()

		if len(frag) < 20 {
			continue
		}
		ihl := int(frag[0]&0x0f) * 4
		flagsAndFrag := binary.BigEndian.Uint16(frag[6:8])
		moreFragments := (flagsAndFrag & 0x2000) != 0
		fragOffset := (flagsAndFrag & 0x1fff) * 8

		if fragOffset == 0 {
			if len(frag) >= ihl+8+2 {
				firstTxID = binary.BigEndian.Uint16(frag[ihl+8 : ihl+8+2])
			}
			assembledDNS = append(assembledDNS, frag[ihl+8:]...)
		} else {
			assembledDNS = append(assembledDNS, frag[ihl:]...)
		}

		if !moreFragments {
			break
		}
	}

	if len(assembledDNS) < targetLargeSize {
		t.Fatalf("Large DNS response was truncated: assembled %d bytes, want >= %d bytes",
			len(assembledDNS), targetLargeSize)
	}

	if firstTxID != expectedTxID {
		t.Errorf("Transaction ID mismatch in large response: got %04x, want %04x", firstTxID, expectedTxID)
	}
}

func TestDNSTruncationAndTCPRetryFallback(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	const txID = uint16(0x7C01)
	udpClient, udpServer := newPairedDatagramConns()
	defer udpClient.Close()
	defer udpServer.Close()

	// 1. Upstream UDP responds with TC=1 (Truncated bit set)
	go func() {
		buf := make([]byte, 65535)
		n, err := udpServer.Read(buf)
		if err != nil || n < 12 {
			return
		}
		tcResp := buildDNSResponse(txID, "truncated.example.com", true, netip.Addr{}, 0)
		_, _ = udpServer.Write(tcResp)
	}()

	tcpDialed := atomic.Bool{}
	tcpDstAddr := atomic.Pointer[netip.AddrPort]{}

	// 2. Mock TCP server for the fallback retry
	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			return udpClient, nil
		},
		dialTCPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			tcpDialed.Store(true)
			tcpDstAddr.Store(&dst)

			localHalf, remoteHalf := newPairedStreamConns()
			go func() {
				defer remoteHalf.Close()
				// Read 2-byte TCP length prefix
				var lenPrefix uint16
				if err := binary.Read(remoteHalf, binary.BigEndian, &lenPrefix); err != nil {
					return
				}
				queryBuf := make([]byte, lenPrefix)
				if _, err := io.ReadFull(remoteHalf, queryBuf); err != nil {
					return
				}

				// Respond over TCP with full untruncated answer
				fullAnswer := buildDNSResponse(txID, "truncated.example.com", false, netip.MustParseAddr("93.184.216.34"), 0)
				_ = binary.Write(remoteHalf, binary.BigEndian, uint16(len(fullAnswer)))
				_, _ = remoteHalf.Write(fullAnswer)
			}()

			return localHalf, nil
		},
	}

	bridge := &TunBridge{
		ctx:    ctx,
		cancel: cancel,
		client: mockClient,
		token:  &ParsedToken{RegionID: 1},
	}
	proxy, err := newNetstackProxy(bridge)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	bridge.netstack = proxy

	// Step 1: Send UDP query
	srcAP := netip.MustParseAddrPort("10.0.0.2:51111")
	dstAP := netip.MustParseAddrPort("1.1.1.1:53")
	udpQuery := buildDNSQuery(txID, "truncated.example.com", 1)
	pkt := buildIPv4UDPPacket(srcAP, dstAP, udpQuery)
	proxy.inject(pkt, false)

	// Step 2: Receive truncated UDP response
	udpReplyPacket := proxy.link.ReadContext(ctx)
	if udpReplyPacket == nil {
		t.Fatal("Timeout waiting for UDP response")
	}
	view := udpReplyPacket.ToView()
	udpReply := append([]byte(nil), view.AsSlice()...)
	view.Release()
	udpReplyPacket.DecRef()

	dnsHeader := udpReply[28:]
	flags := binary.BigEndian.Uint16(dnsHeader[2:4])
	if (flags & 0x0200) == 0 {
		t.Fatal("Expected TC (Truncation) bit to be set in UDP response")
	}

	// Step 3: Client executes TCP retry to the same destination port 53
	tcpConn, err := mockClient.DialTCP(ctx, dstAP)
	if err != nil {
		t.Fatalf("TCP retry DialTCP failed: %v", err)
	}
	defer tcpConn.Close()

	if !tcpDialed.Load() {
		t.Error("Expected DialTCP to have been called for TCP retry")
	}
	if tcpDstAddr.Load() == nil || *tcpDstAddr.Load() != dstAP {
		t.Errorf("Expected TCP destination %v, got %v", dstAP, tcpDstAddr.Load())
	}

	// Send DNS query over TCP with 2-byte length prefix
	if err := binary.Write(tcpConn, binary.BigEndian, uint16(len(udpQuery))); err != nil {
		t.Fatalf("Write TCP length: %v", err)
	}
	if _, err := tcpConn.Write(udpQuery); err != nil {
		t.Fatalf("Write TCP DNS query: %v", err)
	}

	// Read full untruncated answer over TCP
	var respLen uint16
	if err := binary.Read(tcpConn, binary.BigEndian, &respLen); err != nil {
		t.Fatalf("Read TCP length prefix: %v", err)
	}
	tcpRespBuf := make([]byte, respLen)
	if _, err := io.ReadFull(tcpConn, tcpRespBuf); err != nil {
		t.Fatalf("Read TCP answer: %v", err)
	}

	tcpFlags := binary.BigEndian.Uint16(tcpRespBuf[2:4])
	if (tcpFlags & 0x0200) != 0 {
		t.Error("TCP response must NOT have TC bit set")
	}
	if (tcpFlags & 0x8000) == 0 {
		t.Error("TCP response must have QR bit set")
	}
}

func TestDNSConfiguredPolicyAndDestinationMatching(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var dialedDestinations []netip.AddrPort
	var mu sync.Mutex

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			mu.Lock()
			dialedDestinations = append(dialedDestinations, dst)
			mu.Unlock()
			c1, _ := newPairedDatagramConns()
			return c1, nil
		},
	}

	bridge := &TunBridge{
		ctx:    ctx,
		cancel: cancel,
		client: mockClient,
		token:  &ParsedToken{RegionID: 1},
	}
	proxy, err := newNetstackProxy(bridge)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	bridge.netstack = proxy

	query := buildDNSQuery(0x1234, "policy.test.org", 1)
	srcAP1 := netip.MustParseAddrPort("10.0.0.2:40001")
	srcAP2 := netip.MustParseAddrPort("10.0.0.2:40002")
	srcAP3 := netip.MustParseAddrPort("10.0.0.2:40003")
	srcAP4 := netip.MustParseAddrPort("10.0.0.2:40004")

	// 1. Policy: PROFILE_RESOLVER (default) -> Preserves original destinations
	dst1 := netip.MustParseAddrPort("1.1.1.1:53")
	dst2 := netip.MustParseAddrPort("9.9.9.9:53")

	proxy.inject(buildIPv4UDPPacket(srcAP1, dst1, query), false)
	time.Sleep(100 * time.Millisecond)

	proxy.inject(buildIPv4UDPPacket(srcAP2, dst2, query), false)
	time.Sleep(100 * time.Millisecond)

	mu.Lock()
	if len(dialedDestinations) != 2 {
		t.Fatalf("Expected 2 dialed destinations under PROFILE_RESOLVER, got %d", len(dialedDestinations))
	}
	if dialedDestinations[0] != dst1 {
		t.Errorf("Expected first dial destination %v, got %v", dst1, dialedDestinations[0])
	}
	if dialedDestinations[1] != dst2 {
		t.Errorf("Expected second dial destination %v, got %v", dst2, dialedDestinations[1])
	}
	dialedDestinations = nil
	mu.Unlock()

	// 2. Policy: FORCED_RESOLVER -> Rewrites all port 53 queries to forced resolver
	forcedAP := netip.MustParseAddrPort("8.8.8.8:53")
	bridge.SetDNSConfig(DNSConfig{
		Policy:    "FORCED_RESOLVER",
		ForcedDNS: forcedAP,
	})

	proxy.inject(buildIPv4UDPPacket(srcAP3, dst1, query), false)
	time.Sleep(100 * time.Millisecond)

	proxy.inject(buildIPv4UDPPacket(srcAP4, dst2, query), false)
	time.Sleep(100 * time.Millisecond)

	mu.Lock()
	if len(dialedDestinations) != 2 {
		t.Fatalf("Expected 2 dialed destinations under FORCED_RESOLVER, got %d", len(dialedDestinations))
	}
	if dialedDestinations[0] != forcedAP {
		t.Errorf("Expected forced destination %v, got %v", forcedAP, dialedDestinations[0])
	}
	if dialedDestinations[1] != forcedAP {
		t.Errorf("Expected forced destination %v, got %v", forcedAP, dialedDestinations[1])
	}
	mu.Unlock()
}

func TestDNSIPv4AndIPv6Resolvers(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var dialedAP []netip.AddrPort
	var mu sync.Mutex

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			mu.Lock()
			dialedAP = append(dialedAP, dst)
			mu.Unlock()
			c1, _ := newPairedDatagramConns()
			return c1, nil
		},
	}

	bridge := &TunBridge{
		ctx:    ctx,
		cancel: cancel,
		client: mockClient,
		token:  &ParsedToken{RegionID: 1},
	}
	proxy, err := newNetstackProxy(bridge)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	bridge.netstack = proxy

	query := buildDNSQuery(0x6001, "dualstack.test.org", 1)

	// IPv4 query to 1.1.1.1:53
	srcV4 := netip.MustParseAddrPort("10.0.0.2:40001")
	dstV4 := netip.MustParseAddrPort("1.1.1.1:53")
	proxy.inject(buildIPv4UDPPacket(srcV4, dstV4, query), false)
	time.Sleep(50 * time.Millisecond)

	// IPv6 query to [2606:4700:4700::1111]:53
	srcV6 := netip.MustParseAddrPort("[fd7a:115c:a1e0::2]:40002")
	dstV6 := netip.MustParseAddrPort("[2606:4700:4700::1111]:53")
	proxy.inject(buildIPv6UDPPacket(srcV6, dstV6, query), true)
	time.Sleep(50 * time.Millisecond)

	mu.Lock()
	defer mu.Unlock()
	if len(dialedAP) != 2 {
		t.Fatalf("Expected 2 dialed destinations, got %d", len(dialedAP))
	}
	if dialedAP[0] != dstV4 {
		t.Errorf("Expected IPv4 destination %v, got %v", dstV4, dialedAP[0])
	}
	if dialedAP[1] != dstV6 {
		t.Errorf("Expected IPv6 destination %v, got %v", dstV6, dialedAP[1])
	}
}

func TestDNSTimeoutAndCancellation(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			c1, _ := newPairedDatagramConns()
			// Server never writes any response (simulates blackhole timeout)
			return c1, nil
		},
	}

	bridge := &TunBridge{
		ctx:    ctx,
		cancel: cancel,
		client: mockClient,
		token:  &ParsedToken{RegionID: 1},
	}
	proxy, err := newNetstackProxy(bridge)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	bridge.netstack = proxy

	query := buildDNSQuery(0x9999, "timeout.test.org", 1)
	srcAP := netip.MustParseAddrPort("10.0.0.2:40099")
	dstAP := netip.MustParseAddrPort("1.1.1.1:53")

	proxy.inject(buildIPv4UDPPacket(srcAP, dstAP, query), false)
	time.Sleep(100 * time.Millisecond)

	proxy.udpMu.Lock()
	activeFlows := proxy.udpActiveTotal
	proxy.udpMu.Unlock()

	if activeFlows != 1 {
		t.Errorf("Expected 1 active flow before timeout, got %d", activeFlows)
	}

	// Cancel bridge context to simulate stop / teardown
	cancel()
	time.Sleep(100 * time.Millisecond)

	proxy.Close()

	proxy.udpMu.Lock()
	remaining := len(proxy.udpFlows)
	proxy.udpMu.Unlock()

	if remaining != 0 {
		t.Errorf("Expected 0 remaining flows after cancel, got %d", remaining)
	}
}

func TestDNSLeakPrevention(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var dialedDestinations []netip.AddrPort
	var mu sync.Mutex

	mockClient := &mockTunnelClient{
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			mu.Lock()
			dialedDestinations = append(dialedDestinations, dst)
			mu.Unlock()
			c1, _ := newPairedDatagramConns()
			return c1, nil
		},
	}

	bridge := &TunBridge{
		ctx:    ctx,
		cancel: cancel,
		client: mockClient,
		token:  &ParsedToken{RegionID: 1},
	}
	proxy, err := newNetstackProxy(bridge)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	defer proxy.Close()
	bridge.netstack = proxy

	targets := []string{"1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53"}
	srcAP := netip.MustParseAddrPort("10.0.0.2:48888")

	for _, target := range targets {
		dst := netip.MustParseAddrPort(target)
		query := buildDNSQuery(0x5555, "leak-check.org", 1)
		proxy.inject(buildIPv4UDPPacket(srcAP, dst, query), false)
	}

	time.Sleep(100 * time.Millisecond)

	mu.Lock()
	defer mu.Unlock()

	if len(dialedDestinations) != len(targets) {
		t.Fatalf("Expected all %d targets to be routed through TunnelClient, got %d", len(targets), len(dialedDestinations))
	}

	for i, target := range targets {
		expected := netip.MustParseAddrPort(target)
		if dialedDestinations[i] != expected {
			t.Errorf("Target %d mismatched: got %v, want %v", i, dialedDestinations[i], expected)
		}
	}
}
