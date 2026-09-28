package engine

import (
	"context"
	"io"
	"net"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// pipeDial returns gateway connections that accept and discard every write.
func pipeDial(context.Context, netip.AddrPort) (net.Conn, error) {
	c, s := net.Pipe()
	go func() {
		_, _ = io.Copy(io.Discard, s)
		_ = s.Close()
	}()
	return c, nil
}

func TestDNSQueriesCountForwardedQueriesOnly(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	b := &TunBridge{
		sessionID: 1,
		token:     &ParsedToken{RegionID: 1},
		client:    &mockTunnelClient{dialUDPFn: pipeDial, dialTCPFn: pipeDial},
		mtu:       1280,
		ctx:       ctx,
		cancel:    cancel,
	}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	b.netstack = proxy
	defer proxy.Close()

	// Two queries on one UDP/53 flow are two queries.
	dnsSrc := netip.MustParseAddrPort("100.64.0.2:40001")
	resolver := netip.MustParseAddrPort("1.1.1.1:53")
	b.handleOutboundPacket(buildIPv4UDPPacket(dnsSrc, resolver, []byte("query-1")))
	b.handleOutboundPacket(buildIPv4UDPPacket(dnsSrc, resolver, []byte("query-2")))
	waitAtomic(t, &b.dnsQueries, 2, 2*time.Second, "two forwarded UDP DNS queries")

	// Other UDP is not DNS.
	b.handleOutboundPacket(buildIPv4UDPPacket(
		netip.MustParseAddrPort("100.64.0.2:40002"),
		netip.MustParseAddrPort("1.1.1.1:443"),
		[]byte("quic"),
	))

	// A TCP/53 connection counts once (after its handshake and gateway dial),
	// however many segments it carries. The app side is a real stack so the
	// handshake completes as it does on a device.
	app := newAppStack(t, ctx, proxy)
	conn, err := gonet.DialContextTCP(ctx, app, tcpip.FullAddress{
		NIC:  1,
		Addr: tcpip.AddrFrom4([4]byte{1, 1, 1, 1}),
		Port: 53,
	}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatalf("app dial TCP/53: %v", err)
	}
	defer conn.Close()
	for i := 0; i < 3; i++ {
		if _, err := conn.Write([]byte{0, 5, 'q', 'u', 'e', 'r', 'y'}); err != nil {
			t.Fatalf("write TCP/53: %v", err)
		}
	}
	waitAtomic(t, &b.dnsQueries, 3, 2*time.Second, "the proxied TCP/53 connection")

	time.Sleep(200 * time.Millisecond)
	if got := b.dnsQueries.Load(); got != 3 {
		t.Fatalf("dnsQueries = %d, want 3 (2 UDP queries + 1 TCP/53 connection)", got)
	}
	if got := b.GetStats().DNSQueries; got != 3 {
		t.Fatalf("stats dnsQueries = %d, want 3", got)
	}
}

func TestLinkQueueDropsAreCounted(t *testing.T) {
	var drops atomic.Int64
	link := &countingLink{Endpoint: channel.New(1, 1280, ""), drops: &drops}
	defer link.Close()

	var pkts stack.PacketBufferList
	for i := 0; i < 3; i++ {
		pkts.PushBack(stack.NewPacketBuffer(stack.PacketBufferOptions{
			Payload: buffer.MakeWithData([]byte{0x45, 0, 0, 20}),
		}))
	}
	n, _ := link.WritePackets(pkts)
	pkts.Reset()
	if n != 1 || drops.Load() != 2 {
		t.Fatalf("wrote %d and counted %d drops; want 1 written and 2 drops for a queue of 1", n, drops.Load())
	}
}
