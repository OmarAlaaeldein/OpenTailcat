package exitnode

import (
	"context"
	"crypto/tls"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/derp/derpserver"
	"tailscale.com/net/stun"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
)

// devDERP runs a DERP relay and STUN responder on loopback, so a client and an
// exit node can meet without the public Internet. Test-only: it is the same
// recipe the upstream CLI uses for `--dev-derp`.
func devDERP(t *testing.T) *tailcfg.DERPRegion {
	t.Helper()
	d := derpserver.New(key.NewNode(), logger.Discard)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewUnstartedServer(derpserver.Handler(d))
	srv.Listener = ln
	srv.Config.TLSNextProto = make(map[string]func(*http.Server, *tls.Conn, http.Handler))
	srv.StartTLS()
	t.Cleanup(srv.Close)

	uln, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { uln.Close() })
	go func() {
		var buf [1500]byte
		for {
			nr, src, err := uln.ReadFromUDPAddrPort(buf[:])
			if err != nil {
				return
			}
			if txid, err := stun.ParseBindingRequest(buf[:nr]); err == nil {
				uln.WriteToUDPAddrPort(stun.Response(txid, src), src)
			}
		}
	}()
	return &tailcfg.DERPRegion{
		RegionID: 1, RegionCode: "D",
		Nodes: []*tailcfg.DERPNode{{
			Name: "t1", RegionID: 1, HostName: "T", IPv4: "127.0.0.1", IPv6: "none",
			STUNPort:         uln.LocalAddr().(*net.UDPAddr).Port,
			DERPPort:         ln.Addr().(*net.TCPAddr).Port,
			InsecureForTests: true,
		}},
	}
}

func startE2ENode(t *testing.T, region *tailcfg.DERPRegion, mutate func(*Config)) *Node {
	t.Helper()
	cfg := Config{TTL: time.Hour, Region: region, AllowLocalDestinations: true}
	if mutate != nil {
		mutate(&cfg)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	n, err := Start(ctx, cfg)
	if err != nil {
		t.Fatalf("Start: %v", err)
	}
	t.Cleanup(func() { n.Close() })
	return n
}

// echoTCP is a loopback destination the node forwards to.
func echoTCP(t *testing.T) netip.AddrPort {
	t.Helper()
	return echoServer(t)
}

// TestEndToEndApprovalThenForward drives the real thing: a tailcat client
// holding only the token is refused until the host approves its device key,
// then moves TCP and UDP through the node to loopback destinations.
func TestEndToEndApprovalThenForward(t *testing.T) {
	region := devDERP(t)
	n := startE2ENode(t, region, nil)

	token, err := n.Token()
	if err != nil {
		t.Fatal(err)
	}
	info, err := tailcat.ParseAddr(tailcat.Addr(token))
	if err != nil {
		t.Fatalf("minted token does not parse: %v", err)
	}
	if info.PresharedKey.IsZero() {
		t.Fatal("minted token carries no preshared key")
	}

	devKey := key.NewNode()
	client := &tailcat.Client{Server: tailcat.Addr(token), Key: devKey}
	t.Cleanup(func() { client.Close() })
	dst := echoTCP(t)

	// Phase 1: the token alone is not enough. The client keeps retrying, which
	// is what makes the device appear as a pending request.
	short, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	if conn, err := client.DialTCP(short, dst); err == nil {
		conn.Close()
		cancel()
		t.Fatal("unapproved device connected with the token alone")
	}
	cancel()
	pending := n.Stats().Pending
	if len(pending) != 1 || pending[0].Key != devKey.Public().String() {
		t.Fatalf("pending = %+v, want the connecting device %s", pending, devKey.Public())
	}

	// Phase 2: approve, and the same client succeeds on its next retry.
	if err := n.Approve(devKey.Public()); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	conn, err := client.DialTCP(ctx, dst)
	if err != nil {
		t.Fatalf("approved device could not dial: %v", err)
	}
	msg := []byte("through the phone")
	if _, err := conn.Write(msg); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, len(msg))
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("read echo: %v", err)
	}
	if string(got) != string(msg) {
		t.Fatalf("echo = %q, want %q", got, msg)
	}
	conn.Close()

	s := n.Stats()
	if s.TotalTCP < 1 || s.BytesFromGuests < uint64(len(msg)) || s.BytesToGuests < uint64(len(msg)) {
		t.Fatalf("counters did not move for real traffic: %+v", s)
	}
	if len(s.Pending) != 0 {
		t.Fatalf("approved device still pending: %+v", s.Pending)
	}

	// UDP through the same session.
	uconn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer uconn.Close()
	go func() {
		buf := make([]byte, 1500)
		for {
			nr, a, err := uconn.ReadFromUDP(buf)
			if err != nil {
				return
			}
			uconn.WriteToUDP(buf[:nr], a)
		}
	}()
	pc, err := client.DialUDP(ctx, uconn.LocalAddr().(*net.UDPAddr).AddrPort())
	if err != nil {
		t.Fatalf("DialUDP: %v", err)
	}
	defer pc.Close()
	deadline := time.Now().Add(15 * time.Second)
	reply := make([]byte, 64)
	for {
		pc.Write([]byte("ping"))
		pc.SetReadDeadline(time.Now().Add(time.Second))
		if nr, err := pc.Read(reply); err == nil {
			if string(reply[:nr]) != "ping" {
				t.Fatalf("udp echo = %q", reply[:nr])
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("no UDP reply through the node")
		}
	}
	if n.Stats().TotalUDP < 1 {
		t.Fatalf("udp flow not counted: %+v", n.Stats())
	}
}

// TestEndToEndHostNetworkIsRefused checks the destination policy through the
// real data path: with the default config, a connected guest cannot reach the
// host's loopback.
func TestEndToEndHostNetworkIsRefused(t *testing.T) {
	region := devDERP(t)
	devKey := key.NewNode()
	n := startE2ENode(t, region, func(c *Config) {
		c.AllowLocalDestinations = false
		c.Approved = []key.NodePublic{devKey.Public()}
	})
	token, _ := n.Token()
	client := &tailcat.Client{Server: tailcat.Addr(token), Key: devKey}
	t.Cleanup(func() { client.Close() })

	dst := echoTCP(t) // 127.0.0.1: must stay unreachable
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if conn, err := client.DialTCP(ctx, dst); err == nil {
		conn.Close()
		t.Fatal("guest reached the host's loopback through the node")
	}
	if got := n.Stats().PolicyRejections; got < 1 {
		t.Fatalf("policyRejections = %d, want >= 1", got)
	}
}

// TestEndToEndStopRevokesTheToken: after Close the node is gone, so a token it
// issued cannot be used again.
func TestEndToEndStopRevokesTheToken(t *testing.T) {
	region := devDERP(t)
	devKey := key.NewNode()
	n := startE2ENode(t, region, func(c *Config) { c.Approved = []key.NodePublic{devKey.Public()} })
	token, _ := n.Token()
	if err := n.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := n.Token(); err == nil {
		t.Fatal("Token still available after Close")
	}
	client := &tailcat.Client{Server: tailcat.Addr(token), Key: devKey}
	t.Cleanup(func() { client.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Second)
	defer cancel()
	if conn, err := client.DialTCP(ctx, echoTCP(t)); err == nil {
		conn.Close()
		t.Fatal("a stopped node still forwarded a flow")
	}
}
