package engine

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"sync/atomic"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
)

// serveDNSOverTCP is a resolver on the gateway side of DialTCP. It reads
// batch queries before answering any, answers them in reverse order, then
// either keeps the connection open or closes it.
func serveDNSOverTCP(conn net.Conn, batch int, closeAfter bool) {
	defer conn.Close()
	var queries [][]byte
	var hdr [2]byte
	for len(queries) < batch {
		if _, err := io.ReadFull(conn, hdr[:]); err != nil {
			return
		}
		q := make([]byte, binary.BigEndian.Uint16(hdr[:]))
		if _, err := io.ReadFull(conn, q); err != nil {
			return
		}
		queries = append(queries, q)
	}
	for i := len(queries) - 1; i >= 0; i-- {
		resp := append([]byte(nil), queries[i]...)
		resp[2] |= 0x80 // QR: this is an answer
		frame := binary.BigEndian.AppendUint16(nil, uint16(len(resp)))
		if _, err := conn.Write(append(frame, resp...)); err != nil {
			return
		}
	}
	if !closeAfter {
		_, _ = io.Copy(io.Discard, conn)
	}
}

// newDNSOverTCPHarness returns a tcpOnly bridge whose gateway resolver is
// serve, and an app UDP socket connected to 1.1.1.1:53.
func newDNSOverTCPHarness(t *testing.T, serve func(net.Conn)) (*gonet.UDPConn, *atomic.Int64) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	t.Cleanup(cancel)
	dials := new(atomic.Int64)
	b := &TunBridge{ctx: ctx, cancel: cancel, token: &ParsedToken{RegionID: 1}, client: &mockTunnelClient{
		dialTCPFn: func(context.Context, netip.AddrPort) (net.Conn, error) {
			dials.Add(1)
			c, s := net.Pipe()
			go serve(s)
			return c, nil
		},
	}}
	b.tcpOnly.Store(true)
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	t.Cleanup(proxy.Close)
	b.netstack = proxy
	app := newAppStack(t, ctx, proxy)
	conn, err := gonet.DialUDP(app, nil, &tcpip.FullAddress{
		NIC:  1,
		Addr: tcpip.AddrFrom4([4]byte{1, 1, 1, 1}),
		Port: 53,
	}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatalf("app UDP socket: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	return conn, dials
}

func readDNSAnswerIDs(t *testing.T, conn *gonet.UDPConn, n int) map[uint16]bool {
	t.Helper()
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	got := make(map[uint16]bool)
	buf := make([]byte, 1500)
	for len(got) < n {
		m, err := conn.Read(buf)
		if err != nil {
			t.Fatalf("answer %d of %d: %v", len(got)+1, n, err)
		}
		if m < 12 || buf[2]&0x80 == 0 {
			t.Fatalf("got a non-answer of %d bytes", m)
		}
		got[binary.BigEndian.Uint16(buf[:2])] = true
	}
	return got
}

// The A and AAAA lookups of one getaddrinfo (and retries) share one
// connection and are answered even when the resolver needs them all first.
func TestDNSOverTCPPipelinesQueriesOnOneConnection(t *testing.T) {
	const batch = 3
	conn, dials := newDNSOverTCPHarness(t, func(c net.Conn) { serveDNSOverTCP(c, batch, false) })
	for id := uint16(1); id <= batch; id++ {
		if _, err := conn.Write(buildDNSQuery(id, "pipe.example", 1)); err != nil {
			t.Fatalf("send query %d: %v", id, err)
		}
	}
	got := readDNSAnswerIDs(t, conn, batch)
	for id := uint16(1); id <= batch; id++ {
		if !got[id] {
			t.Fatalf("no answer for query %d (got %v)", id, got)
		}
	}
	if n := dials.Load(); n != 1 {
		t.Fatalf("gateway dials = %d, want 1 for one socket's queries", n)
	}
}

// A resolver that closes the connection after answering gets a fresh dial
// for the next query instead of ending the flow.
func TestDNSOverTCPRedialsAfterResolverCloses(t *testing.T) {
	conn, dials := newDNSOverTCPHarness(t, func(c net.Conn) { serveDNSOverTCP(c, 1, true) })
	for id := uint16(1); id <= 3; id++ {
		if _, err := conn.Write(buildDNSQuery(id, "redial.example", 1)); err != nil {
			t.Fatalf("send query %d: %v", id, err)
		}
		if got := readDNSAnswerIDs(t, conn, 1); !got[id] {
			t.Fatalf("query %d: answer ids %v", id, got)
		}
	}
	if n := dials.Load(); n != 3 {
		t.Fatalf("gateway dials = %d, want 3 (one per closed connection)", n)
	}
}
