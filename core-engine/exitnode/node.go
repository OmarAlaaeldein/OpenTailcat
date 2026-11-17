// Package exitnode turns the device into a Tailcat exit gateway: it mints a
// session token, admits guests, and forwards their TCP and UDP flows to the
// Internet over the device's own network.
//
// It is the inverse of the client engine (package engine) and is deliberately
// a separate package. The engine's safety invariant 2 forbids ordinary OS
// sockets because every flow it carries is supposed to stay inside the tunnel
// (core-engine/no_os_socket_test.go). An exit node's whole job is to dial out
// over device routes, so it lives here, where that rule does not apply, rather
// than in an allowlist entry that would weaken the engine's guarantee. All OS
// sockets in this package are created in dial.go.
//
// Security model, in order of importance:
//
//   - A token is a bearer credential for the node's keys, so it is not enough
//     to admit a guest. By default (AdmitApproval) an unknown device is parked
//     as "pending" and refused until the host approves that device key.
//     AdmitOpen admits every holder of the token and exists only as an
//     explicit, time-boxed mode.
//   - Every session uses a fresh node key and preshared key, discarded on
//     Close. Stopping the node therefore revokes every token it ever issued.
//   - The node enforces its own expiry. A client-side expiry check is not
//     enforcement. A session may instead have no time limit (NoExpiry); it
//     then lasts until Close, and only with AdmitApproval.
//   - Destinations on the host's own machine and networks are refused (see
//     policy.go) unless a test opts in.
//   - Nothing here logs keys, tokens, or destinations.
package exitnode

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

// Admission selects how unknown devices are treated.
type Admission int

const (
	// AdmitApproval parks an unknown device as pending until the host approves
	// its key. This is the zero value, so an unconfigured node is the safe one.
	AdmitApproval Admission = iota
	// AdmitOpen admits any device that holds the token, until it expires. It
	// always has a time limit: Start refuses AdmitOpen with NoExpiry.
	AdmitOpen
)

const (
	// MaxTTL bounds a timed session. A share meant to outlast it uses NoExpiry,
	// which requires approval admission.
	MaxTTL = 24 * time.Hour

	DefaultMaxTCP = 256
	DefaultMaxUDP = 256

	maxPending = 16
	maxDenied  = 64
	// pendingTTL is how long an unapproved device stays listed after its last
	// attempt. Rejected clients retry about once a second, so a live request
	// never ages out.
	pendingTTL = 2 * time.Minute

	tcpDialTimeout = 15 * time.Second
	udpDialTimeout = 10 * time.Second
	closeTimeout   = 5 * time.Second
)

// State is the node's lifecycle state.
type State string

const (
	StateRunning State = "RUNNING"
	StateExpired State = "EXPIRED"
	StateStopped State = "STOPPED"
)

// Dialer opens the outbound side of a forwarded flow. The default dials over
// the device's own routes.
type Dialer interface {
	DialContext(ctx context.Context, network, address string) (net.Conn, error)
}

// Config describes one sharing session.
type Config struct {
	// TTL is how long the session lasts: more than zero and at most MaxTTL,
	// unless NoExpiry is set, in which case it must be zero.
	TTL time.Duration

	// NoExpiry makes the session last until Close. Allowed only with
	// AdmitApproval, so a leaked token alone never grants standing access.
	NoExpiry bool

	// Admission defaults to AdmitApproval.
	Admission Admission

	// Approved pre-approves device keys.
	Approved []key.NodePublic

	// MaxTCP and MaxUDP cap concurrent forwarded flows. Zero uses the default.
	MaxTCP, MaxUDP int

	// RegionID picks the DERP region; zero picks the nearest. Region, when
	// set, is used as-is and no DERP map is fetched.
	RegionID tailcfg.DERPRegionID
	Region   *tailcfg.DERPRegion

	// DERPMapURL overrides where the DERP map is fetched from.
	DERPMapURL string

	// AllowLocalDestinations lets guests reach the host's loopback and private
	// networks. It exists for tests and is not exposed through the bridge.
	AllowLocalDestinations bool

	// Dialer and Logf are for tests; the defaults are the OS dialer and no
	// logging.
	Dialer Dialer
	Logf   func(format string, args ...any)

	// now is replaceable in tests.
	now func() time.Time
}

// Node is a running exit node. All methods are safe for concurrent use.
type Node struct {
	cfg     Config
	srv     *tailcat.Server
	dialer  Dialer
	now     func() time.Time
	started time.Time
	// expires is zero when the session has no time limit.
	expires time.Time

	// peerKey resolves a forwarded flow's remote address to the device key.
	// It is srv.PeerKey in production.
	peerKey func(net.Addr) (key.NodePublic, bool)

	ctx    context.Context
	cancel context.CancelFunc

	mu      sync.Mutex
	state   State
	allowed map[key.NodePublic]struct{}
	denied  map[key.NodePublic]struct{}
	pending map[key.NodePublic]*pendingPeer
	peers   map[key.NodePublic]*peerStats
	timer   *time.Timer

	tcpSlots, udpSlots chan struct{}

	closeOnce sync.Once
	closeErr  error
	done      chan struct{}

	activeTCP, activeUDP        atomic.Int64
	totalTCP, totalUDP          atomic.Uint64
	rejPolicy, rejCap, rejDial  atomic.Uint64
	udpOversizeDrop             atomic.Uint64
	bytesFromPeers, bytesToPeer atomic.Uint64
}

type pendingPeer struct {
	firstSeen, lastSeen time.Time
}

// peerStats are the counters for one admitted device.
type peerStats struct {
	firstSeen  time.Time
	lastActive atomic.Int64 // unix seconds
	fromPeer   atomic.Uint64
	toPeer     atomic.Uint64
	activeTCP  atomic.Int64
	activeUDP  atomic.Int64
	flows      atomic.Uint64
}

// Start brings up a node with a fresh identity and returns once it is
// reachable. ctx bounds startup (DERP selection and connection) only;
// cancelling it afterwards does not stop the node, Close does.
func Start(ctx context.Context, cfg Config) (*Node, error) {
	if err := cfg.validate(); err != nil {
		return nil, err
	}
	n := newNode(cfg)

	srv := &tailcat.Server{
		Key:            key.NewNode(),
		PresharedKey:   tailcat.NewPresharedKey(),
		Logf:           n.logf,
		Region:         cfg.Region,
		RegionID:       cfg.RegionID,
		DERPMapURL:     cfg.DERPMapURL,
		AllowClient:    n.admit,
		OnTCPForward:   n.onTCPForward,
		OnUDPForward:   n.onUDPForward,
		UDPIdleTimeout: tailcat.DefaultUDPIdleTimeout,
		// OnTCP and OnUDP stay nil on purpose: the node exposes no services of
		// its own, so a guest dialing the node itself gets a reset.
	}
	if err := srv.StartContext(ctx); err != nil {
		n.cancel()
		_ = srv.Close()
		return nil, fmt.Errorf("exitnode: start: %w", err)
	}
	n.srv = srv
	n.peerKey = srv.PeerKey
	n.arm()
	return n, nil
}

func (cfg Config) validate() error {
	if cfg.Admission != AdmitApproval && cfg.Admission != AdmitOpen {
		return errors.New("exitnode: unknown admission mode")
	}
	if cfg.NoExpiry {
		if cfg.TTL != 0 {
			return errors.New("exitnode: a session without a time limit takes no TTL")
		}
		if cfg.Admission != AdmitApproval {
			return errors.New("exitnode: a session without a time limit requires approval admission")
		}
		return nil
	}
	if cfg.TTL <= 0 {
		return errors.New("exitnode: TTL is required")
	}
	if cfg.TTL > MaxTTL {
		return fmt.Errorf("exitnode: TTL exceeds %v", MaxTTL)
	}
	return nil
}

// newNode builds the node without a network server. Tests drive the handlers
// of a node built this way directly.
func newNode(cfg Config) *Node {
	if cfg.MaxTCP <= 0 {
		cfg.MaxTCP = DefaultMaxTCP
	}
	if cfg.MaxUDP <= 0 {
		cfg.MaxUDP = DefaultMaxUDP
	}
	now := cfg.now
	if now == nil {
		now = time.Now
	}
	var d Dialer = cfg.Dialer
	if d == nil {
		d = osDialer()
	}
	ctx, cancel := context.WithCancel(context.Background())
	n := &Node{
		cfg:      cfg,
		dialer:   d,
		now:      now,
		ctx:      ctx,
		cancel:   cancel,
		state:    StateRunning,
		allowed:  map[key.NodePublic]struct{}{},
		denied:   map[key.NodePublic]struct{}{},
		pending:  map[key.NodePublic]*pendingPeer{},
		peers:    map[key.NodePublic]*peerStats{},
		tcpSlots: make(chan struct{}, cfg.MaxTCP),
		udpSlots: make(chan struct{}, cfg.MaxUDP),
		done:     make(chan struct{}),
	}
	for _, k := range cfg.Approved {
		n.allowed[k] = struct{}{}
	}
	n.started = now()
	if !cfg.NoExpiry {
		n.expires = n.started.Add(cfg.TTL)
	}
	n.peerKey = func(net.Addr) (key.NodePublic, bool) { return key.NodePublic{}, false }
	return n
}

// arm starts the expiry timer. It is separate from newNode so a node whose
// server failed to start never leaves a timer behind.
func (n *Node) arm() {
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.state != StateRunning || n.expires.IsZero() {
		return
	}
	n.timer = time.AfterFunc(n.expires.Sub(n.now()), func() { n.shutdown(StateExpired) })
}

func (n *Node) logf(format string, args ...any) {
	if n.cfg.Logf != nil {
		n.cfg.Logf(format, args...)
	}
}

// Token returns the shareable "tc..." string for this session. It carries the
// node's keys, so it is a credential: show it, never log it.
func (n *Node) Token() (string, error) {
	if n.srv == nil {
		return "", errors.New("exitnode: not started")
	}
	if n.State() != StateRunning {
		return "", errors.New("exitnode: session is over")
	}
	return string(n.srv.TailcatAddr()), nil
}

// State reports the lifecycle state.
func (n *Node) State() State {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.state
}

// ExpiresAt is when the node stops itself; the zero time when it has no
// time limit.
func (n *Node) ExpiresAt() time.Time { return n.expires }

// Done is closed once the node has stopped, for any reason.
func (n *Node) Done() <-chan struct{} { return n.done }

// Close stops the node and discards its keys, which revokes every token it
// issued. It is idempotent and returns within a bounded time.
func (n *Node) Close() error {
	n.shutdown(StateStopped)
	<-n.done
	return n.closeErr
}

func (n *Node) shutdown(final State) {
	n.closeOnce.Do(func() {
		n.mu.Lock()
		n.state = final
		if n.timer != nil {
			n.timer.Stop()
		}
		n.mu.Unlock()

		// Cancelling first makes in-flight dials and proxies unwind while the
		// server is torn down.
		n.cancel()
		if n.srv != nil {
			errc := make(chan error, 1)
			go func() { errc <- n.srv.Close() }()
			select {
			case err := <-errc:
				n.closeErr = err
			case <-time.After(closeTimeout):
				n.closeErr = errors.New("exitnode: server close timed out")
			}
		}
		close(n.done)
	})
}

func (n *Node) expired() bool {
	return !n.expires.IsZero() && !n.now().Before(n.expires)
}

func (n *Node) running() bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	return n.state == StateRunning && !n.expired()
}

// ---- admission --------------------------------------------------------------

// admit is the server's AllowClient hook. It is called about once a second per
// retrying device, so it must stay cheap, and it must not block.
func (n *Node) admit(k key.NodePublic) bool {
	if !n.running() {
		return false
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if _, ok := n.allowed[k]; ok {
		return true
	}
	if n.cfg.Admission == AdmitOpen {
		n.allowed[k] = struct{}{}
		return true
	}
	if _, ok := n.denied[k]; ok {
		return false
	}
	now := n.now()
	n.prunePendingLocked(now)
	if p, ok := n.pending[k]; ok {
		p.lastSeen = now
		return false
	}
	if len(n.pending) >= maxPending {
		n.evictOldestPendingLocked()
	}
	n.pending[k] = &pendingPeer{firstSeen: now, lastSeen: now}
	return false
}

func (n *Node) prunePendingLocked(now time.Time) {
	for k, p := range n.pending {
		if now.Sub(p.lastSeen) > pendingTTL {
			delete(n.pending, k)
		}
	}
}

func (n *Node) evictOldestPendingLocked() {
	var oldest key.NodePublic
	var at time.Time
	first := true
	for k, p := range n.pending {
		if first || p.lastSeen.Before(at) {
			oldest, at, first = k, p.lastSeen, false
		}
	}
	if !first {
		delete(n.pending, oldest)
	}
}

// Approve admits a device. The device's next connection attempt, which it
// makes within about a second, succeeds.
func (n *Node) Approve(k key.NodePublic) error {
	if k.IsZero() {
		return errors.New("exitnode: empty device key")
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if n.state != StateRunning {
		return errors.New("exitnode: session is over")
	}
	delete(n.pending, k)
	delete(n.denied, k)
	n.allowed[k] = struct{}{}
	return nil
}

// Deny refuses a pending device for the rest of the session, so it stops
// appearing as a request.
func (n *Node) Deny(k key.NodePublic) {
	n.mu.Lock()
	defer n.mu.Unlock()
	delete(n.pending, k)
	if len(n.denied) >= maxDenied {
		return // bounded; the device simply reappears as pending
	}
	n.denied[k] = struct{}{}
}

// Revoke removes a device and drops its connection. Admission is withdrawn
// before the disconnect, as the library requires, so it cannot reconnect.
// Established flows stall until their own timeouts; Close is the hard stop.
func (n *Node) Revoke(k key.NodePublic) bool {
	n.mu.Lock()
	_, was := n.allowed[k]
	delete(n.allowed, k)
	delete(n.pending, k)
	if len(n.denied) < maxDenied {
		n.denied[k] = struct{}{}
	}
	n.mu.Unlock()
	dropped := false
	if n.srv != nil {
		dropped = n.srv.DisconnectClient(k)
	}
	return was || dropped
}

// ParseDeviceKey accepts "nodekey:<64 hex>" or bare 64 hex.
func ParseDeviceKey(s string) (key.NodePublic, error) {
	s = strings.TrimSpace(s)
	if !strings.HasPrefix(s, "nodekey:") {
		s = "nodekey:" + s
	}
	var k key.NodePublic
	if err := k.UnmarshalText([]byte(s)); err != nil {
		return key.NodePublic{}, errors.New("exitnode: invalid device key")
	}
	if k.IsZero() {
		return key.NodePublic{}, errors.New("exitnode: invalid device key")
	}
	return k, nil
}

// ---- forwarding -------------------------------------------------------------

func (n *Node) onTCPForward(dst netip.AddrPort) func(net.Conn) {
	if !n.running() {
		return nil
	}
	if err := checkDestination(dst, n.cfg.AllowLocalDestinations); err != nil {
		n.rejPolicy.Add(1)
		return nil // the library answers with a reset
	}
	return func(c net.Conn) { n.serveTCP(c, dst) }
}

func (n *Node) onUDPForward(dst netip.AddrPort) func(tailcat.ConnPacketConn) {
	if !n.running() {
		return nil
	}
	if err := checkDestination(dst, n.cfg.AllowLocalDestinations); err != nil {
		n.rejPolicy.Add(1)
		return nil // the flow is dropped
	}
	return func(c tailcat.ConnPacketConn) { n.serveUDP(c, dst) }
}

// peer returns the counters for the device behind remote, creating them on
// first use. An unresolvable device is accounted under the zero key.
func (n *Node) peer(remote net.Addr) *peerStats {
	k, _ := n.peerKey(remote)
	n.mu.Lock()
	defer n.mu.Unlock()
	p, ok := n.peers[k]
	if !ok {
		p = &peerStats{firstSeen: n.now()}
		n.peers[k] = p
	}
	p.lastActive.Store(n.now().Unix())
	return p
}

func acquire(slots chan struct{}) bool {
	select {
	case slots <- struct{}{}:
		return true
	default:
		return false
	}
}

func release(slots chan struct{}) { <-slots }

func (n *Node) serveTCP(c net.Conn, dst netip.AddrPort) {
	if !acquire(n.tcpSlots) {
		n.rejCap.Add(1)
		c.Close()
		return
	}
	defer release(n.tcpSlots)

	p := n.peer(c.RemoteAddr())
	n.activeTCP.Add(1)
	p.activeTCP.Add(1)
	n.totalTCP.Add(1)
	p.flows.Add(1)
	defer func() { n.activeTCP.Add(-1); p.activeTCP.Add(-1) }()

	ctx, cancel := context.WithTimeout(n.ctx, tcpDialTimeout)
	remote, err := n.dialer.DialContext(ctx, "tcp", dst.String())
	cancel()
	if err != nil {
		// A failed dial closes the flow; success is never synthesized.
		n.rejDial.Add(1)
		c.Close()
		return
	}
	n.proxyTCP(c, remote, p)
}

// proxyTCP copies both directions, forwards half-close, and tears the pair
// down when the node stops.
func (n *Node) proxyTCP(peer, remote net.Conn, p *peerStats) {
	stop := context.AfterFunc(n.ctx, func() { peer.Close(); remote.Close() })
	defer stop()

	var wg sync.WaitGroup
	wg.Add(2)
	cp := func(dst, src net.Conn, total, per *atomic.Uint64) {
		defer wg.Done()
		buf := make([]byte, 32<<10)
		for {
			nr, err := src.Read(buf)
			if nr > 0 {
				nw, werr := dst.Write(buf[:nr])
				total.Add(uint64(nw))
				per.Add(uint64(nw))
				p.lastActive.Store(n.now().Unix())
				if werr != nil {
					err = werr
				}
			}
			if err != nil {
				break
			}
		}
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		} else {
			dst.Close()
		}
	}
	go cp(remote, peer, &n.bytesFromPeers, &p.fromPeer)
	go cp(peer, remote, &n.bytesToPeer, &p.toPeer)
	wg.Wait()
	peer.Close()
	remote.Close()
}

func (n *Node) serveUDP(c tailcat.ConnPacketConn, dst netip.AddrPort) {
	if !acquire(n.udpSlots) {
		n.rejCap.Add(1)
		c.Close()
		return
	}
	defer release(n.udpSlots)

	p := n.peer(c.RemoteAddr())
	n.activeUDP.Add(1)
	p.activeUDP.Add(1)
	n.totalUDP.Add(1)
	p.flows.Add(1)
	defer func() { n.activeUDP.Add(-1); p.activeUDP.Add(-1) }()

	ctx, cancel := context.WithTimeout(n.ctx, udpDialTimeout)
	remote, err := n.dialer.DialContext(ctx, "udp", dst.String())
	cancel()
	if err != nil {
		n.rejDial.Add(1)
		c.Close()
		return
	}
	stop := context.AfterFunc(n.ctx, func() { c.Close(); remote.Close() })
	defer stop()

	done := make(chan struct{}, 2)
	pump := func(dst, src net.Conn, total, per *atomic.Uint64, enforceMTU bool) {
		defer func() { done <- struct{}{} }()
		buf := make([]byte, 2048)
		for {
			nr, err := src.Read(buf)
			if err != nil {
				return
			}
			if enforceMTU && nr > tailcat.MaxUDPPayload {
				// A datagram that cannot fit the tunnel is dropped and
				// counted; it does not end the flow.
				n.udpOversizeDrop.Add(1)
				continue
			}
			nw, err := dst.Write(buf[:nr])
			total.Add(uint64(nw))
			per.Add(uint64(nw))
			p.lastActive.Store(n.now().Unix())
			if err != nil {
				return
			}
		}
	}
	go pump(remote, c, &n.bytesFromPeers, &p.fromPeer, false)
	go pump(c, remote, &n.bytesToPeer, &p.toPeer, true)
	<-done
	c.Close()
	remote.Close()
	<-done
}
