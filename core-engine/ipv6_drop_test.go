package engine

import (
	"context"
	"encoding/binary"
	"errors"
	"net"
	"net/netip"
	"os"
	"sync/atomic"
	"testing"
	"time"
)

func TestIPv6TCPDroppedNoDial(t *testing.T) {
	bridge, dialTCP, dialUDP, cleanup := newIPv6DropTestBridge(t)
	defer cleanup()

	pkt := buildIPv6TCPSyn(
		netip.MustParseAddrPort("[fd7a:115c:a1e0::2]:54321"),
		netip.MustParseAddrPort("[2606:4700:4700::1111]:443"),
	)
	assertIPv6Dropped(t, bridge, pkt, dialTCP, dialUDP)
}

func TestIPv6UDPDroppedNoDial(t *testing.T) {
	bridge, dialTCP, dialUDP, cleanup := newIPv6DropTestBridge(t)
	defer cleanup()

	pkt := buildIPv6UDPPacket(
		netip.MustParseAddrPort("[fd7a:115c:a1e0::2]:54321"),
		netip.MustParseAddrPort("[2606:4700:4700::1111]:443"),
		[]byte("ipv6-udp"),
	)
	assertIPv6Dropped(t, bridge, pkt, dialTCP, dialUDP)
}

func TestIPv6UDPDNSDroppedNoDial(t *testing.T) {
	bridge, dialTCP, dialUDP, cleanup := newIPv6DropTestBridge(t)
	defer cleanup()

	pkt := buildIPv6UDPPacket(
		netip.MustParseAddrPort("[fd7a:115c:a1e0::2]:54321"),
		netip.MustParseAddrPort("[2606:4700:4700::1111]:53"),
		[]byte("dns-query"),
	)
	assertIPv6Dropped(t, bridge, pkt, dialTCP, dialUDP)
	if bridge.dnsQueries.Load() != 0 {
		t.Fatalf("expected 0 dnsQueries for dropped IPv6 UDP/53, got %d", bridge.dnsQueries.Load())
	}
}

func TestIPv6ICMPEchoDroppedNoReply(t *testing.T) {
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe: %v", err)
	}
	defer r.Close()
	defer w.Close()

	bridge, dialTCP, dialUDP, cleanup := newIPv6DropTestBridge(t)
	defer cleanup()
	bridge.tunFile = w

	src := netip.MustParseAddr("fd7a:115c:a1e0::2")
	dst := netip.MustParseAddr("2001:4860:4860::8888")
	pkt := buildIPv6ICMPEcho(src, dst, []byte("ping6"))
	assertIPv6Dropped(t, bridge, pkt, dialTCP, dialUDP)

	_ = r.SetReadDeadline(time.Now().Add(100 * time.Millisecond))
	buf := make([]byte, 4096)
	n, readErr := r.Read(buf)
	if n > 0 {
		t.Fatalf("expected no TUN write for dropped ICMPv6 echo, got %d bytes", n)
	}
	if readErr == nil {
		t.Fatal("expected TUN read timeout with no ICMPv6 reply")
	}
}

func TestIPv6ExtensionHeaderDroppedNoDial(t *testing.T) {
	bridge, dialTCP, dialUDP, cleanup := newIPv6DropTestBridge(t)
	defer cleanup()

	pkt := buildIPv6HopByHop(
		netip.MustParseAddr("fd7a:115c:a1e0::2"),
		netip.MustParseAddr("2606:4700:4700::1111"),
	)
	assertIPv6Dropped(t, bridge, pkt, dialTCP, dialUDP)
}

func TestIPv6TruncatedMalformedNotPolicy(t *testing.T) {
	bridge, dialTCP, dialUDP, cleanup := newIPv6DropTestBridge(t)
	defer cleanup()

	pkt := make([]byte, 30)
	pkt[0] = 0x60
	bridge.handleOutboundPacket(pkt)

	if bridge.malformedIP.Load() != 1 {
		t.Fatalf("expected 1 malformedIP, got %d", bridge.malformedIP.Load())
	}
	if bridge.policyRejections.Load() != 0 {
		t.Fatalf("expected 0 policyRejections for truncated IPv6, got %d", bridge.policyRejections.Load())
	}
	if dialTCP.Load() != 0 || dialUDP.Load() != 0 {
		t.Fatalf("expected zero dials, tcp=%d udp=%d", dialTCP.Load(), dialUDP.Load())
	}
}

func TestIPv4TCPUDPICMPStillHandled(t *testing.T) {
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("os.Pipe: %v", err)
	}
	defer r.Close()
	defer w.Close()

	bridge, dialTCP, dialUDP, cleanup := newIPv6DropTestBridge(t)
	defer cleanup()
	bridge.tunFile = w

	tcpPkt := buildIPv4TCPSyn(
		netip.MustParseAddrPort("100.64.0.2:54321"),
		netip.MustParseAddrPort("1.1.1.1:443"),
	)
	bridge.handleOutboundPacket(tcpPkt)
	if bridge.tcpPackets.Load() != 1 {
		t.Fatalf("expected 1 ipv4 tcpPacket, got %d", bridge.tcpPackets.Load())
	}
	waitAtomic(t, dialTCP, 1, 2*time.Second, "DialTCP for IPv4 TCP")

	udpPkt := buildIPv4UDPPacket(
		netip.MustParseAddrPort("100.64.0.2:54321"),
		netip.MustParseAddrPort("1.1.1.1:53"),
		[]byte("ipv4-dns"),
	)
	bridge.handleOutboundPacket(udpPkt)
	if bridge.udpPackets.Load() != 1 {
		t.Fatalf("expected 1 ipv4 udpPacket, got %d", bridge.udpPackets.Load())
	}
	if bridge.dnsQueries.Load() != 1 {
		t.Fatalf("expected 1 ipv4 dnsQuery, got %d", bridge.dnsQueries.Load())
	}
	waitAtomic(t, dialUDP, 1, 2*time.Second, "DialUDP for IPv4 UDP")

	icmpPkt := buildIPv4ICMPEcho(
		netip.MustParseAddr("100.64.0.2"),
		netip.MustParseAddr("1.1.1.1"),
		[]byte("ping4"),
	)
	bridge.handleOutboundPacket(icmpPkt)

	_ = r.SetReadDeadline(time.Now().Add(time.Second))
	buf := make([]byte, 4096)
	n, readErr := r.Read(buf)
	if readErr != nil {
		t.Fatalf("expected IPv4 ICMP echo reply on TUN, read: %v", readErr)
	}
	if n < 28 {
		t.Fatalf("IPv4 ICMP reply too short: %d", n)
	}
	if buf[0]>>4 != 4 {
		t.Fatalf("expected IPv4 reply, version=%d", buf[0]>>4)
	}
	ihl := int(buf[0]&0x0f) * 4
	if buf[9] != 1 {
		t.Fatalf("expected ICMP protocol 1, got %d", buf[9])
	}
	if buf[ihl] != 0 {
		t.Fatalf("expected ICMP echo reply type 0, got %d", buf[ihl])
	}

	if bridge.policyRejections.Load() != 0 {
		t.Fatalf("expected 0 policyRejections for IPv4 traffic, got %d", bridge.policyRejections.Load())
	}
	if dialTCP.Load() != 1 {
		t.Fatalf("expected 1 DialTCP, got %d", dialTCP.Load())
	}
	if dialUDP.Load() != 1 {
		t.Fatalf("expected 1 DialUDP, got %d", dialUDP.Load())
	}
}

func newIPv6DropTestBridge(t *testing.T) (*TunBridge, *atomic.Int64, *atomic.Int64, func()) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	dialTCP := new(atomic.Int64)
	dialUDP := new(atomic.Int64)
	mockClient := &mockTunnelClient{
		dialTCPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			dialTCP.Add(1)
			return nil, errors.New("ipv6-drop-test: tcp unused")
		},
		dialUDPFn: func(ctx context.Context, dst netip.AddrPort) (net.Conn, error) {
			dialUDP.Add(1)
			return nil, errors.New("ipv6-drop-test: udp unused")
		},
	}
	bridge := &TunBridge{
		sessionID: 1,
		token:     &ParsedToken{RegionID: 1},
		client:    mockClient,
		mtu:       1280,
		ctx:       ctx,
		cancel:    cancel,
	}
	netstack, err := newNetstackProxy(bridge)
	if err != nil {
		cancel()
		t.Fatalf("newNetstackProxy: %v", err)
	}
	bridge.netstack = netstack
	return bridge, dialTCP, dialUDP, func() {
		netstack.Close()
		cancel()
	}
}

func assertIPv6Dropped(t *testing.T, bridge *TunBridge, pkt []byte, dialTCP, dialUDP *atomic.Int64) {
	t.Helper()
	before := bridge.policyRejections.Load()
	tcpBefore := bridge.tcpPackets.Load()
	udpBefore := bridge.udpPackets.Load()
	dialTCPBefore := dialTCP.Load()
	dialUDPBefore := dialUDP.Load()

	bridge.handleOutboundPacket(pkt)
	time.Sleep(150 * time.Millisecond)

	if dialTCP.Load() != dialTCPBefore || dialUDP.Load() != dialUDPBefore {
		t.Fatalf("expected zero DialTCP/DialUDP, tcp=%d udp=%d", dialTCP.Load()-dialTCPBefore, dialUDP.Load()-dialUDPBefore)
	}
	if got := bridge.policyRejections.Load(); got != before+1 {
		t.Fatalf("expected policyRejections %d, got %d", before+1, got)
	}
	if bridge.tcpPackets.Load() != tcpBefore {
		t.Fatalf("expected tcpPackets unchanged at %d, got %d", tcpBefore, bridge.tcpPackets.Load())
	}
	if bridge.udpPackets.Load() != udpBefore {
		t.Fatalf("expected udpPackets unchanged at %d, got %d", udpBefore, bridge.udpPackets.Load())
	}
	stats := bridge.GetStats()
	if stats.DropCounters.PolicyRejections != before+1 {
		t.Fatalf("GetStats policyRejections=%d, want %d", stats.DropCounters.PolicyRejections, before+1)
	}
}

func waitAtomic(t *testing.T, v *atomic.Int64, want int64, timeout time.Duration, what string) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if v.Load() >= want {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s (got %d, want %d)", what, v.Load(), want)
}

func buildIPv6TCPSyn(srcAP, dstAP netip.AddrPort) []byte {
	pkt := make([]byte, 60)
	pkt[0] = 0x60
	binary.BigEndian.PutUint16(pkt[4:6], 20)
	pkt[6] = 6
	pkt[7] = 64
	src := srcAP.Addr().As16()
	dst := dstAP.Addr().As16()
	copy(pkt[8:24], src[:])
	copy(pkt[24:40], dst[:])
	binary.BigEndian.PutUint16(pkt[40:42], srcAP.Port())
	binary.BigEndian.PutUint16(pkt[42:44], dstAP.Port())
	binary.BigEndian.PutUint32(pkt[44:48], 1)
	pkt[52] = 5 << 4
	pkt[53] = 0x02
	binary.BigEndian.PutUint16(pkt[54:56], 65535)
	pseudo := make([]byte, 40)
	copy(pseudo[0:16], src[:])
	copy(pseudo[16:32], dst[:])
	binary.BigEndian.PutUint32(pseudo[32:36], 20)
	pseudo[39] = 6
	chk := checksum(pseudo, pkt[40:])
	binary.BigEndian.PutUint16(pkt[56:58], chk)
	return pkt
}

func buildIPv4TCPSyn(srcAP, dstAP netip.AddrPort) []byte {
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
	binary.BigEndian.PutUint32(pkt[24:28], 1)
	pkt[32] = 5 << 4
	pkt[33] = 0x02
	binary.BigEndian.PutUint16(pkt[34:36], 65535)
	pseudo := make([]byte, 12)
	copy(pseudo[0:4], src[:])
	copy(pseudo[4:8], dst[:])
	pseudo[9] = 6
	binary.BigEndian.PutUint16(pseudo[10:12], 20)
	chk := checksum(pseudo, pkt[20:])
	binary.BigEndian.PutUint16(pkt[36:38], chk)
	return pkt
}

func buildIPv6ICMPEcho(src, dst netip.Addr, payload []byte) []byte {
	icmpLen := 8 + len(payload)
	pkt := make([]byte, 40+icmpLen)
	pkt[0] = 0x60
	binary.BigEndian.PutUint16(pkt[4:6], uint16(icmpLen))
	pkt[6] = 58
	pkt[7] = 64
	srcB := src.As16()
	dstB := dst.As16()
	copy(pkt[8:24], srcB[:])
	copy(pkt[24:40], dstB[:])
	pkt[40] = 128
	copy(pkt[48:], payload)
	pseudo := make([]byte, 40)
	copy(pseudo[0:16], srcB[:])
	copy(pseudo[16:32], dstB[:])
	binary.BigEndian.PutUint32(pseudo[32:36], uint32(icmpLen))
	pseudo[39] = 58
	chk := checksum(pseudo, pkt[40:])
	binary.BigEndian.PutUint16(pkt[42:44], chk)
	return pkt
}

func buildIPv4ICMPEcho(src, dst netip.Addr, payload []byte) []byte {
	icmpLen := 8 + len(payload)
	pkt := make([]byte, 20+icmpLen)
	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], uint16(len(pkt)))
	pkt[8] = 64
	pkt[9] = 1
	srcB := src.As4()
	dstB := dst.As4()
	copy(pkt[12:16], srcB[:])
	copy(pkt[16:20], dstB[:])
	binary.BigEndian.PutUint16(pkt[10:12], ipv4Checksum(pkt[:20]))
	pkt[20] = 8
	copy(pkt[28:], payload)
	chk := checksum(pkt[20:])
	binary.BigEndian.PutUint16(pkt[22:24], chk)
	return pkt
}

func buildIPv6HopByHop(src, dst netip.Addr) []byte {
	pkt := make([]byte, 48)
	pkt[0] = 0x60
	binary.BigEndian.PutUint16(pkt[4:6], 8)
	pkt[6] = 0
	pkt[7] = 64
	srcB := src.As16()
	dstB := dst.As16()
	copy(pkt[8:24], srcB[:])
	copy(pkt[24:40], dstB[:])
	pkt[40] = 59
	return pkt
}
