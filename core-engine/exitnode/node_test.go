package exitnode

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	"tailscale.com/types/key"
)

// fakeClock is a controllable time source.
type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func newClock() *fakeClock { return &fakeClock{t: time.Unix(1_800_000_000, 0)} }
func (c *fakeClock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}
func (c *fakeClock) advance(d time.Duration) {
	c.mu.Lock()
	c.t = c.t.Add(d)
	c.mu.Unlock()
}

func testNode(t *testing.T, mutate func(*Config)) (*Node, *fakeClock) {
	t.Helper()
	clk := newClock()
	cfg := Config{TTL: time.Hour, now: clk.now, AllowLocalDestinations: true}
	if mutate != nil {
		mutate(&cfg)
	}
	n := newNode(cfg)
	t.Cleanup(func() { n.Close() })
	return n, clk
}

func k() key.NodePublic { return key.NewNode().Public() }

// ---- admission -------------------------------------------------------------

func TestApprovalIsTheDefault(t *testing.T) {
	var cfg Config
	if cfg.Admission != AdmitApproval {
		t.Fatalf("zero-value Admission = %v, want AdmitApproval", cfg.Admission)
	}
}

func TestUnknownDeviceIsPendingUntilApproved(t *testing.T) {
	n, _ := testNode(t, nil)
	dev := k()

	if n.admit(dev) {
		t.Fatal("unknown device admitted without approval")
	}
	if got := n.Stats().Pending; len(got) != 1 || got[0].Key != dev.String() {
		t.Fatalf("pending = %+v, want exactly the unknown device", got)
	}
	// The device retries about once a second; it must stay refused and must
	// not multiply its entry.
	for range 5 {
		if n.admit(dev) {
			t.Fatal("retry admitted without approval")
		}
	}
	if got := len(n.Stats().Pending); got != 1 {
		t.Fatalf("pending entries after retries = %d, want 1", got)
	}

	if err := n.Approve(dev); err != nil {
		t.Fatal(err)
	}
	if !n.admit(dev) {
		t.Fatal("approved device refused")
	}
	if got := len(n.Stats().Pending); got != 0 {
		t.Fatalf("pending after approve = %d, want 0", got)
	}
}

func TestPreApprovedDeviceIsAdmittedImmediately(t *testing.T) {
	dev := k()
	n, _ := testNode(t, func(c *Config) { c.Approved = []key.NodePublic{dev} })
	if !n.admit(dev) {
		t.Fatal("pre-approved device refused")
	}
	if n.admit(k()) {
		t.Fatal("a different device was admitted")
	}
}

func TestDenyStopsTheRequestReappearing(t *testing.T) {
	n, _ := testNode(t, nil)
	dev := k()
	n.admit(dev)
	n.Deny(dev)
	for range 3 {
		if n.admit(dev) {
			t.Fatal("denied device admitted")
		}
	}
	if got := len(n.Stats().Pending); got != 0 {
		t.Fatalf("denied device is listed as pending (%d)", got)
	}
}

func TestApproveRevivesADeniedDevice(t *testing.T) {
	n, _ := testNode(t, nil)
	dev := k()
	n.Deny(dev)
	if err := n.Approve(dev); err != nil {
		t.Fatal(err)
	}
	if !n.admit(dev) {
		t.Fatal("device approved after deny was refused")
	}
}

func TestRevokeWithdrawsAdmission(t *testing.T) {
	n, _ := testNode(t, nil)
	dev := k()
	n.Approve(dev)
	if !n.Revoke(dev) {
		t.Fatal("Revoke reported nothing to revoke")
	}
	if n.admit(dev) {
		t.Fatal("revoked device was re-admitted")
	}
	if got := len(n.Stats().Pending); got != 0 {
		t.Fatalf("revoked device re-listed as pending (%d)", got)
	}
}

func TestPendingIsBoundedAndAgesOut(t *testing.T) {
	n, clk := testNode(t, nil)
	for range maxPending + 10 {
		clk.advance(time.Second)
		n.admit(k())
	}
	if got := len(n.Stats().Pending); got != maxPending {
		t.Fatalf("pending = %d, want cap %d", got, maxPending)
	}
	clk.advance(pendingTTL + time.Second)
	if got := len(n.Stats().Pending); got != 0 {
		t.Fatalf("stale pending entries survived: %d", got)
	}
}

func TestOpenAdmissionAdmitsAnyone(t *testing.T) {
	n, _ := testNode(t, func(c *Config) { c.Admission = AdmitOpen })
	if !n.admit(k()) {
		t.Fatal("open node refused a device")
	}
}

func TestApproveRejectsEmptyKey(t *testing.T) {
	n, _ := testNode(t, nil)
	if err := n.Approve(key.NodePublic{}); err == nil {
		t.Fatal("approved the zero key")
	}
}

func TestParseDeviceKey(t *testing.T) {
	want := k()
	for _, s := range []string{want.String(), strings.TrimPrefix(want.String(), "nodekey:"), "  " + want.String() + "\n"} {
		got, err := ParseDeviceKey(s)
		if err != nil || got != want {
			t.Errorf("ParseDeviceKey(%q) = %v, %v", s, got, err)
		}
	}
	for _, s := range []string{"", "nodekey:", "zz", "nodekey:" + strings.Repeat("0", 64), strings.Repeat("a", 63)} {
		if _, err := ParseDeviceKey(s); err == nil {
			t.Errorf("ParseDeviceKey(%q) accepted invalid input", s)
		}
	}
}

// ---- expiry and lifecycle ---------------------------------------------------

func TestExpiryIsEnforcedByTheNode(t *testing.T) {
	dev := k()
	n, clk := testNode(t, func(c *Config) { c.Admission = AdmitOpen; c.Approved = []key.NodePublic{dev} })
	if !n.admit(dev) {
		t.Fatal("refused before expiry")
	}
	clk.advance(time.Hour + time.Second)
	if n.admit(dev) {
		t.Fatal("approved device admitted after expiry")
	}
	if n.admit(k()) {
		t.Fatal("open node admitted a new device after expiry")
	}
	if n.onTCPForward(netip.MustParseAddrPort("8.8.8.8:443")) != nil {
		t.Fatal("TCP forward handler offered after expiry")
	}
	if n.onUDPForward(netip.MustParseAddrPort("8.8.8.8:53")) != nil {
		t.Fatal("UDP forward handler offered after expiry")
	}
	if got, ok := n.TimeRemaining(); !ok || got != 0 {
		t.Fatalf("TimeRemaining = %v after expiry", got)
	}
}

func TestASessionWithoutATimeLimitRunsUntilClosed(t *testing.T) {
	dev := k()
	n, clk := testNode(t, func(c *Config) { c.TTL = 0; c.NoExpiry = true; c.Approved = []key.NodePublic{dev} })
	n.arm()
	if n.timer != nil {
		t.Fatal("a session without a time limit armed an expiry timer")
	}
	clk.advance(30 * 24 * time.Hour)
	if !n.admit(dev) {
		t.Fatal("an approved device was refused a month in")
	}
	if n.onTCPForward(netip.MustParseAddrPort("127.0.0.1:443")) == nil {
		t.Fatal("forwarding stopped without an expiry")
	}
	if _, ok := n.TimeRemaining(); ok {
		t.Fatal("TimeRemaining reported a limit")
	}
	if !n.ExpiresAt().IsZero() {
		t.Fatalf("ExpiresAt = %v, want the zero time", n.ExpiresAt())
	}
	if s := n.Stats(); s.ExpiresUnixSec != 0 || s.State != StateRunning {
		t.Fatalf("stats = %s expires %d, want RUNNING with expiresUnixSec 0", s.State, s.ExpiresUnixSec)
	}
	// Approval still applies: a new device waits.
	if n.admit(k()) {
		t.Fatal("an unknown device was admitted without approval")
	}
	if err := n.Close(); err != nil {
		t.Fatal(err)
	}
	if n.admit(dev) {
		t.Fatal("admitted after Close")
	}
}

func TestExpiryTimerStopsTheNode(t *testing.T) {
	n := newNode(Config{TTL: 30 * time.Millisecond})
	n.arm()
	select {
	case <-n.Done():
	case <-time.After(5 * time.Second):
		t.Fatal("node did not stop itself at expiry")
	}
	if got := n.State(); got != StateExpired {
		t.Fatalf("state = %s, want EXPIRED", got)
	}
}

func TestCloseIsIdempotentAndFinal(t *testing.T) {
	n := newNode(Config{TTL: time.Hour})
	n.arm()
	for range 3 {
		if err := n.Close(); err != nil {
			t.Fatal(err)
		}
	}
	if got := n.State(); got != StateStopped {
		t.Fatalf("state = %s, want STOPPED", got)
	}
	if err := n.Approve(k()); err == nil {
		t.Fatal("Approve succeeded on a stopped node")
	}
	if n.admit(k()) {
		t.Fatal("stopped node admitted a device")
	}
	select {
	case <-n.Done():
	default:
		t.Fatal("Done not closed after Close")
	}
}

func TestCloseDoesNotChangeAnExpiredState(t *testing.T) {
	n := newNode(Config{TTL: 20 * time.Millisecond})
	n.arm()
	<-n.Done()
	if err := n.Close(); err != nil {
		t.Fatal(err)
	}
	if got := n.State(); got != StateExpired {
		t.Fatalf("state = %s after Close of an expired node, want EXPIRED", got)
	}
}

func TestConcurrentCloseAndExpiry(t *testing.T) {
	for range 50 {
		n := newNode(Config{TTL: time.Millisecond})
		n.arm()
		var wg sync.WaitGroup
		for range 4 {
			wg.Add(1)
			go func() { defer wg.Done(); n.Close() }()
		}
		wg.Wait()
	}
}

func TestStartValidatesConfig(t *testing.T) {
	for name, cfg := range map[string]Config{
		"no ttl":     {},
		"negative":   {TTL: -time.Second},
		"too long":   {TTL: MaxTTL + time.Second},
		"bad policy": {TTL: time.Hour, Admission: 7},
		// A session without a time limit names no TTL and never admits openly.
		"no limit with ttl":   {NoExpiry: true, TTL: time.Hour},
		"no limit open":       {NoExpiry: true, Admission: AdmitOpen},
		"no limit open + ttl": {NoExpiry: true, TTL: time.Hour, Admission: AdmitOpen},
	} {
		if n, err := Start(context.Background(), cfg); err == nil {
			n.Close()
			t.Errorf("%s: Start accepted %+v", name, cfg)
		}
	}
}

func TestTokenRequiresAStartedNode(t *testing.T) {
	n := newNode(Config{TTL: time.Hour})
	defer n.Close()
	if _, err := n.Token(); err == nil {
		t.Fatal("Token succeeded on a node with no server")
	}
}

// ---- forwarding -------------------------------------------------------------

// echoServer accepts connections on loopback and echoes until EOF, then
// half-closes so the half-close path is exercised too.
func echoServer(t *testing.T) netip.AddrPort {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() {
				io.Copy(c, c)
				c.(*net.TCPConn).CloseWrite()
				c.Close()
			}()
		}
	}()
	return l.Addr().(*net.TCPAddr).AddrPort()
}

// tcpPair returns two connected TCP conns: the guest's end and the one the
// node receives from the library.
func tcpPair(t *testing.T) (guest, node net.Conn) {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	accepted := make(chan net.Conn, 1)
	go func() {
		c, _ := l.Accept()
		accepted <- c
	}()
	g, err := net.Dial("tcp", l.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	nc := <-accepted
	t.Cleanup(func() { g.Close(); nc.Close() })
	return g, nc
}

func TestTCPForwardProxiesBothWaysAndHalfCloses(t *testing.T) {
	n, _ := testNode(t, nil)
	dst := echoServer(t)

	guest, nodeSide := tcpPair(t)
	h := n.onTCPForward(dst)
	if h == nil {
		t.Fatal("no handler for a permitted destination")
	}
	go h(nodeSide)

	msg := []byte("hello through the node")
	if _, err := guest.Write(msg); err != nil {
		t.Fatal(err)
	}
	// Half-close: the echo server only finishes after it sees our EOF, and the
	// reply must still arrive.
	guest.(*net.TCPConn).CloseWrite()
	guest.SetReadDeadline(time.Now().Add(5 * time.Second))
	got, err := io.ReadAll(guest)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(msg) {
		t.Fatalf("echo = %q, want %q", got, msg)
	}

	// Counters reflect real bytes in both directions.
	waitFor(t, func() bool {
		s := n.Stats()
		return s.BytesFromGuests == uint64(len(msg)) && s.BytesToGuests == uint64(len(msg)) && s.ActiveTCP == 0
	})
	if s := n.Stats(); s.TotalTCP != 1 {
		t.Fatalf("totalTcp = %d, want 1", s.TotalTCP)
	}
}

func TestTCPForwardRefusesHostNetworkByDefault(t *testing.T) {
	n, _ := testNode(t, func(c *Config) { c.AllowLocalDestinations = false })
	for _, dst := range []string{"127.0.0.1:22", "192.168.1.1:80", "169.254.169.254:80", "[::1]:80"} {
		if h := n.onTCPForward(netip.MustParseAddrPort(dst)); h != nil {
			t.Errorf("handler offered for %s", dst)
		}
		if h := n.onUDPForward(netip.MustParseAddrPort(dst)); h != nil {
			t.Errorf("UDP handler offered for %s", dst)
		}
	}
	if got := n.Stats().PolicyRejections; got != 8 {
		t.Fatalf("policyRejections = %d, want 8", got)
	}
}

// failingDialer never reaches the network.
type failingDialer struct{}

func (failingDialer) DialContext(context.Context, string, string) (net.Conn, error) {
	return nil, errors.New("no route")
}

func TestDialFailureClosesTheFlowWithoutSynthesizingSuccess(t *testing.T) {
	n, _ := testNode(t, func(c *Config) { c.Dialer = failingDialer{} })
	guest, nodeSide := tcpPair(t)
	h := n.onTCPForward(netip.MustParseAddrPort("8.8.8.8:443"))
	go h(nodeSide)

	guest.SetReadDeadline(time.Now().Add(5 * time.Second))
	if nr, err := guest.Read(make([]byte, 1)); err != io.EOF || nr != 0 {
		t.Fatalf("guest read = %d, %v; want a clean close with no data", nr, err)
	}
	waitFor(t, func() bool { return n.Stats().DialFailures == 1 })
	if s := n.Stats(); s.BytesFromGuests != 0 || s.BytesToGuests != 0 {
		t.Fatalf("bytes counted for a flow that never connected: %+v", s)
	}
}

// blockingDialer holds every dial open until released.
type blockingDialer struct{ release chan struct{} }

func (d blockingDialer) DialContext(ctx context.Context, _, _ string) (net.Conn, error) {
	select {
	case <-d.release:
		return nil, errors.New("released")
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func TestTCPCapIsEnforced(t *testing.T) {
	bd := blockingDialer{release: make(chan struct{})}
	n, _ := testNode(t, func(c *Config) { c.Dialer = bd; c.MaxTCP = 2 })
	dst := netip.MustParseAddrPort("8.8.8.8:443")

	var guests []net.Conn
	for range 2 {
		g, nc := tcpPair(t)
		guests = append(guests, g)
		go n.onTCPForward(dst)(nc)
	}
	waitFor(t, func() bool { return n.Stats().ActiveTCP == 2 })

	// The third flow is over the cap and is closed at once, not queued.
	g3, nc3 := tcpPair(t)
	go n.onTCPForward(dst)(nc3)
	g3.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := g3.Read(make([]byte, 1)); err != io.EOF {
		t.Fatalf("over-cap flow read err = %v, want EOF", err)
	}
	waitFor(t, func() bool { return n.Stats().CapRejections == 1 })

	// Releasing a slot lets a new flow in.
	close(bd.release)
	waitFor(t, func() bool { return n.Stats().ActiveTCP == 0 })
	_ = guests
}

func TestCloseUnwindsInFlightFlows(t *testing.T) {
	bd := blockingDialer{release: make(chan struct{})}
	n, _ := testNode(t, func(c *Config) { c.Dialer = bd })
	g, nc := tcpPair(t)
	go n.onTCPForward(netip.MustParseAddrPort("8.8.8.8:443"))(nc)
	waitFor(t, func() bool { return n.Stats().ActiveTCP == 1 })

	start := time.Now()
	if err := n.Close(); err != nil {
		t.Fatal(err)
	}
	if time.Since(start) > 2*time.Second {
		t.Fatalf("Close took %v with a flow in flight", time.Since(start))
	}
	g.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := g.Read(make([]byte, 1)); err != io.EOF {
		t.Fatalf("guest flow survived Close: %v", err)
	}
	waitFor(t, func() bool { return n.Stats().ActiveTCP == 0 })
}

func TestPerGuestAccounting(t *testing.T) {
	n, _ := testNode(t, nil)
	a, b := k(), k()
	n.Approve(a)
	n.Approve(b)
	n.peerKey = func(r net.Addr) (key.NodePublic, bool) {
		// The test encodes the guest in the connection's remote port parity.
		if r.(*net.TCPAddr).Port%2 == 0 {
			return a, true
		}
		return b, true
	}
	dst := echoServer(t)
	for range 4 {
		g, nc := tcpPair(t)
		go n.onTCPForward(dst)(nc)
		g.Write([]byte("x"))
		g.(*net.TCPConn).CloseWrite()
		io.ReadAll(g)
	}
	waitFor(t, func() bool { return n.Stats().ActiveTCP == 0 })
	var flows uint64
	for _, gs := range n.Stats().Guests {
		flows += gs.Flows
	}
	if flows != 4 {
		t.Fatalf("per-guest flows sum to %d, want 4", flows)
	}
	if got := len(n.Stats().Guests); got != 2 {
		t.Fatalf("guests listed = %d, want 2 approved guests", got)
	}
}

func TestStatsJSONShape(t *testing.T) {
	n, _ := testNode(t, nil)
	n.admit(k())
	js, err := n.StatsJSON()
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{`"schema":1`, `"state":"RUNNING"`, `"admission":"approval"`, `"pending":[{`, `"guests":[]`} {
		if !strings.Contains(js, want) {
			t.Errorf("stats JSON missing %s: %s", want, js)
		}
	}
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met in time")
		}
		time.Sleep(5 * time.Millisecond)
	}
}
