package engine

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
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
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	netstackNIC       = 1
	netstackMTU       = 1280
	netstackQueueSize = 4096
	tcpMaxInFlight    = 1024
	tcpDialTimeout    = 15 * time.Second
	dnsTCPIOTimeout   = 10 * time.Second
	udpDialTimeout    = 10 * time.Second

	// maxActiveUDPFlows bounds the UDP flow table. Every Android app shares
	// the single TUN source address, so the bound is device-wide: when it is
	// reached the least recently active flow is evicted instead of refusing
	// the new one (a refusal blocked all UDP, DNS included, for up to 40 s).
	maxActiveUDPFlows = 1024
	// UDP idle timeouts, modeled on Linux conntrack. A DNS flow whose queries
	// have all been answered closes quickly so lookup bursts do not pin
	// slots; flows that have seen a reply keep their gateway mapping for
	// Tailcat's 2 min DefaultUDPIdleTimeout (RFC 4787 REQ-5).
	udpDNSAnsweredIdle   = 2 * time.Second
	udpDNSUnansweredIdle = 10 * time.Second
	udpUnrepliedIdle     = 30 * time.Second
	udpRepliedIdle       = 2 * time.Minute
	udpGCInterval        = time.Second

	// maxTunnelUDPPayload is the largest UDP payload the Tailcat tunnel
	// carries (its WireGuard MTU is 1280 over IPv6). Larger datagrams fit
	// the TUN MTU but are lost in the tunnel, so the engine drops them and
	// tells the sender with ICMP Fragmentation Needed / Packet Too Big.
	maxTunnelUDPPayload = tailcat.MaxUDPPayload
	// udpUplinkBufSize holds any app datagram the tunnel can carry; larger
	// ones are refused, so a truncated read is only ever dropped.
	udpUplinkBufSize = 2048
	// udpDownlinkBufSize takes the largest UDP payload, since what the
	// gateway delivers is not bounded by the uplink limit.
	udpDownlinkBufSize = 65535

	// tcpMaxEstablished bounds proxied TCP connections (device-wide, like
	// the UDP table).
	tcpMaxEstablished = 512
	tcpCopyBufSize    = 64 * 1024
	// Local gVisor stack TCP buffers. The stack talks to the kernel over the
	// TUN with sub-millisecond RTT, so small windows keep full throughput
	// while bounding per-connection memory (gVisor defaults to 1 MiB send
	// and up to 4 MiB auto-tuned receive).
	localTCPBufDefault = 128 * 1024
	localTCPBufMax     = 1 << 20

	maxDNSMessageSize = 65535
)

type udpFlowKey struct {
	isIPv6 bool
	src    netip.AddrPort
	dst    netip.AddrPort
}

type udpFlow struct {
	key          udpFlowKey
	isDNS        bool
	localConn    *gonet.UDPConn
	remoteMu     sync.Mutex
	remoteConn   net.Conn
	lastActive   atomic.Int64 // unix timestamp in nanoseconds
	sent         atomic.Int64 // datagrams forwarded app -> gateway
	received     atomic.Int64 // datagrams delivered gateway -> app
	closed       atomic.Bool
	closeOnce    sync.Once
	unregistered atomic.Bool
	cancel       context.CancelFunc
}

func (f *udpFlow) touch() {
	f.lastActive.Store(time.Now().UnixNano())
}

// idleTimeout returns how long the flow may stay silent before the GC
// closes it.
func (f *udpFlow) idleTimeout() time.Duration {
	received := f.received.Load()
	if f.isDNS {
		if received > 0 && received >= f.sent.Load() {
			return udpDNSAnsweredIdle
		}
		return udpDNSUnansweredIdle
	}
	if received > 0 {
		return udpRepliedIdle
	}
	return udpUnrepliedIdle
}

func (f *udpFlow) close() {
	f.closeOnce.Do(func() {
		f.closed.Store(true)
		if f.cancel != nil {
			f.cancel()
		}
		if f.localConn != nil {
			_ = f.localConn.Close()
		}
		f.remoteMu.Lock()
		rc := f.remoteConn
		f.remoteMu.Unlock()
		if rc != nil {
			_ = rc.Close()
		}
	})
}

// netstackProxy terminates Android TCP and UDP flows with gVisor's userspace stack
// and proxies datagrams and byte streams exclusively through Tailcat's WireGuard tunnel.
type netstackProxy struct {
	bridge *TunBridge
	stack  *stack.Stack
	link   *channel.Endpoint

	conns sync.Map // map[net.Conn]struct{}

	// udpMu guards the UDP flow table and serializes the closed transition
	// with every udpWg/tcpWg Add, so Close's Wait never races an Add.
	udpMu          sync.Mutex
	udpFlows       map[udpFlowKey]*udpFlow
	udpActiveTotal int            // reservations: registered flows plus accepts in progress
	udpWg          sync.WaitGroup // pending accepts and active flow pumps

	tcpActive atomic.Int32
	tcpWg     sync.WaitGroup

	closed atomic.Bool
}

// recoverFlow contains a panic in a netstack goroutine. Required pumps
// ("gvisor output", "udp gc") still fail-closed via reportPumpDead. Per-flow
// panics (tcp/udp proxy, dial, copy) are logged only: a single bad flow must
// not tear down a healthy CONNECTED session (typed-nil Close historically did).
func (p *netstackProxy) recoverFlow(name string) {
	if r := recover(); r != nil {
		err := fmt.Errorf("%s panic: %v @ %s", name, r, panicSite())
		log.Printf("Tailcat %s", err.Error())
		if p == nil || p.bridge == nil {
			return
		}
		switch name {
		case "gvisor output", "udp gc":
			p.bridge.reportPumpDead(err)
		default:
			// Flow-local: keep pumps alive.
		}
	}
}

func newNetstackProxy(bridge *TunBridge) (*netstackProxy, error) {
	ipStack := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol,
			ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol,
			udp.NewProtocol,
		},
	})

	sack := tcpip.TCPSACKEnabled(true)
	if err := ipStack.SetTransportProtocolOption(tcp.ProtocolNumber, &sack); err != nil {
		ipStack.Destroy()
		return nil, fmt.Errorf("enable TCP SACK: %v", err)
	}
	rxBuf := tcpip.TCPReceiveBufferSizeRangeOption{Min: tcp.MinBufferSize, Default: localTCPBufDefault, Max: localTCPBufMax}
	if err := ipStack.SetTransportProtocolOption(tcp.ProtocolNumber, &rxBuf); err != nil {
		ipStack.Destroy()
		return nil, fmt.Errorf("set TCP receive buffer range: %v", err)
	}
	txBuf := tcpip.TCPSendBufferSizeRangeOption{Min: tcp.MinBufferSize, Default: localTCPBufDefault, Max: localTCPBufMax}
	if err := ipStack.SetTransportProtocolOption(tcp.ProtocolNumber, &txBuf); err != nil {
		ipStack.Destroy()
		return nil, fmt.Errorf("set TCP send buffer range: %v", err)
	}

	// Prefer the bridge MTU (profile-driven, clamped) over the historical
	// fixed 1280 so a raised profile MTU is not silently truncated here.
	stackMTU := uint32(netstackMTU)
	if bridge != nil && bridge.mtu >= minTunnelMTU {
		stackMTU = uint32(bridge.mtu)
	}
	linkEP := channel.New(netstackQueueSize, stackMTU, "")
	linkEP.LinkEPCapabilities |= stack.CapabilityRXChecksumOffload
	var linkDrops *atomic.Int64
	if bridge != nil {
		linkDrops = &bridge.linkQueueDrops
	}
	if err := ipStack.CreateNIC(netstackNIC, &countingLink{Endpoint: linkEP, drops: linkDrops}); err != nil {
		ipStack.Destroy()
		return nil, fmt.Errorf("create netstack NIC: %v", err)
	}
	ipStack.SetPromiscuousMode(netstackNIC, true)
	ipStack.SetSpoofing(netstackNIC, true)

	v4Default, err := tcpip.NewSubnet(
		tcpip.AddrFromSlice(make([]byte, 4)),
		tcpip.MaskFromBytes(make([]byte, 4)),
	)
	if err != nil {
		ipStack.Destroy()
		return nil, fmt.Errorf("create IPv4 default route: %v", err)
	}
	v6Default, err := tcpip.NewSubnet(
		tcpip.AddrFromSlice(make([]byte, 16)),
		tcpip.MaskFromBytes(make([]byte, 16)),
	)
	if err != nil {
		ipStack.Destroy()
		return nil, fmt.Errorf("create IPv6 default route: %v", err)
	}
	ipStack.SetRouteTable([]tcpip.Route{
		{Destination: v4Default, NIC: netstackNIC},
		{Destination: v6Default, NIC: netstackNIC},
	})

	proxy := &netstackProxy{
		bridge:   bridge,
		stack:    ipStack,
		link:     linkEP,
		udpFlows: make(map[udpFlowKey]*udpFlow),
	}

	tcpForwarder := tcp.NewForwarder(ipStack, 0, tcpMaxInFlight, proxy.acceptTCP)
	ipStack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	udpForwarder := udp.NewForwarder(ipStack, proxy.acceptUDP)
	ipStack.SetTransportProtocolHandler(udp.ProtocolNumber, udpForwarder.HandlePacket)

	return proxy, nil
}

// countingLink is the channel endpoint plus a count of the outbound packets
// it drops because its queue to the TUN writer is full, which
// channel.Endpoint does not report.
type countingLink struct {
	*channel.Endpoint
	drops *atomic.Int64
}

func (l *countingLink) WritePackets(pkts stack.PacketBufferList) (int, tcpip.Error) {
	n, err := l.Endpoint.WritePackets(pkts)
	if dropped := pkts.Len() - n; dropped > 0 && l.drops != nil {
		l.drops.Add(int64(dropped))
	}
	return n, err
}

// udpReceiveBufferDrops returns the datagrams the local stack dropped
// because a flow's socket receive buffer was full.
func (p *netstackProxy) udpReceiveBufferDrops() int64 {
	if p == nil || p.stack == nil {
		return 0
	}
	return int64(p.stack.Stats().UDP.ReceiveBufferErrors.Value())
}

func (p *netstackProxy) inject(pkt []byte, ipv6Packet bool) {
	protocol := header.IPv4ProtocolNumber
	if ipv6Packet {
		protocol = header.IPv6ProtocolNumber
	}
	packet := stack.NewPacketBuffer(stack.PacketBufferOptions{
		Payload: buffer.MakeWithData(pkt),
	})
	p.link.InjectInbound(protocol, packet)
	packet.DecRef()
}

func (p *netstackProxy) writeLoop(ready chan struct{}) error {
	defer p.recoverFlow("gvisor output")
	signalReady(ready)
	for {
		packet := p.link.ReadContext(p.bridge.ctx)
		if packet == nil {
			if p.bridge.closed.Load() || p.bridge.ctx.Err() != nil {
				return nil
			}
			return errors.New("gVisor output pump exited")
		}
		view := packet.ToView()
		out := view.AsSlice()
		if len(out) == 0 {
			view.Release()
			packet.DecRef()
			continue
		}
		n := len(out)
		err := p.bridge.writeTunPacket(out)
		view.Release()
		packet.DecRef()
		if err != nil {
			if p.bridge.closed.Load() || p.bridge.ctx.Err() != nil {
				return nil
			}
			return err
		}
		p.bridge.rxBytes.Add(int64(n))
	}
}

func (p *netstackProxy) resolveDNSDestination(dstAP netip.AddrPort) (netip.AddrPort, bool) {
	if dstAP.Port() != 53 || p.bridge == nil {
		return dstAP, true
	}
	cfg := p.bridge.GetDNSConfig()
	if cfg == nil || cfg.Policy != "FORCED_RESOLVER" {
		return dstAP, true
	}
	if cfg.ForcedDNS.IsValid() && isSafeDNSAddr(cfg.ForcedDNS.Addr()) {
		return cfg.ForcedDNS, true
	}
	return netip.AddrPort{}, false
}

// rejectPublicIPv6WithoutEgress RSTs/drops Internet IPv6 when prepare measured
// no gateway IPv6 WAN. Prevents Happy Eyeballs from latching onto a tunnel TCP
// that later blackholes for ~gateway DialTimeout while IPv4 would have worked.
func (p *netstackProxy) rejectPublicIPv6WithoutEgress(dst netip.AddrPort) bool {
	if p.bridge == nil || p.bridge.ipv6Egress.Load() {
		return false
	}
	if !isPublicIPv6Destination(dst) {
		return false
	}
	p.bridge.policyRejections.Add(1)
	return true
}

// resolveFlowDestination applies the DNS redirect and then the IPv6-egress
// gate to the redirected address, so a query to an IPv6 resolver that is
// redirected to an IPv4 forced resolver still works without gateway IPv6.
// Returns false (and counts a policy rejection) when the flow must be refused.
func (p *netstackProxy) resolveFlowDestination(dst netip.AddrPort) (netip.AddrPort, bool) {
	resolved, ok := p.resolveDNSDestination(dst)
	if !ok {
		p.bridge.policyRejections.Add(1)
		return netip.AddrPort{}, false
	}
	if p.rejectPublicIPv6WithoutEgress(resolved) {
		return netip.AddrPort{}, false
	}
	return resolved, true
}

func (p *netstackProxy) acceptTCP(request *tcp.ForwarderRequest) {
	defer p.recoverFlow("tcp accept")
	if p.bridge == nil || p.bridge.client == nil {
		request.Complete(true)
		return
	}
	id := request.ID()
	dstIP, ok := netip.AddrFromSlice(id.LocalAddress.AsSlice())
	if !ok {
		request.Complete(true)
		return
	}
	resolvedDst, ok := p.resolveFlowDestination(netip.AddrPortFrom(dstIP.Unmap(), id.LocalPort))
	if !ok {
		request.Complete(true) // RST: fail closed, Happy Eyeballs moves on
		return
	}
	if p.tcpActive.Add(1) > tcpMaxEstablished {
		p.tcpActive.Add(-1)
		request.Complete(true)
		p.bridge.queueExhaustion.Add(1)
		return
	}
	// Add under udpMu so Close cannot be inside tcpWg.Wait concurrently.
	p.udpMu.Lock()
	if p.closed.Load() {
		p.udpMu.Unlock()
		p.tcpActive.Add(-1)
		request.Complete(true)
		return
	}
	p.tcpWg.Add(1)
	p.udpMu.Unlock()
	p.proxyTCP(request, resolvedDst)
}

func (p *netstackProxy) proxyTCP(request *tcp.ForwarderRequest, resolvedDst netip.AddrPort) {
	defer p.recoverFlow("tcp proxy")
	defer func() {
		p.tcpActive.Add(-1)
		p.tcpWg.Done()
	}()
	// Read before Complete, which releases the request's segment.
	isDNS := request.ID().LocalPort == 53

	type dialResult struct {
		conn net.Conn
		err  error
	}
	dialed := make(chan dialResult, 1)
	go func() {
		defer p.recoverFlow("tcp dial")
		ctx, cancel := context.WithTimeout(p.bridge.ctx, tcpDialTimeout)
		conn, err := p.bridge.client.DialTCP(ctx, resolvedDst)
		cancel()
		dialed <- dialResult{conn, err}
	}()

	var waitQueue waiter.Queue
	endpoint, tcpErr := request.CreateEndpoint(&waitQueue)
	if tcpErr != nil || endpoint == nil {
		request.Complete(true)
		res := <-dialed
		closeConn(res.conn)
		return
	}
	request.Complete(false)
	local := gonet.NewTCPConn(&waitQueue, endpoint)

	// DialTCP returns once the tunnel connection to the gateway is up, before
	// the gateway dials the destination, so an unreachable or refused host
	// shows up as connected-then-EOF. Refusing properly needs the gateway to
	// dial before completing the tunnel handshake.
	res := <-dialed
	if res.err != nil || isNilConn(res.conn) {
		_ = local.Close()
		closeConn(res.conn)
		return
	}
	remote := res.conn
	if isDNS {
		// DNS over TCP from the app: count the proxied connection, not its
		// segments (the queries inside are not parsed).
		p.bridge.dnsQueries.Add(1)
	}

	p.track(local)
	p.track(remote)
	defer p.untrack(local)
	defer p.untrack(remote)
	defer local.Close()
	defer remote.Close()

	p.copyTCP(local, remote)
}

// copyTCP proxies both directions until both have finished. A clean EOF in
// one direction is passed on as a half-close (FIN) so request/response
// protocols that shut down their write side keep receiving; an error in
// either direction (reset, write failure) or engine shutdown ends both.
func (p *netstackProxy) copyTCP(local *gonet.TCPConn, remote net.Conn) {
	type copyResult struct{ err error }
	results := make(chan copyResult, 2)
	// Dedicated buffers: the two directions must not share one slice.
	bufUp := make([]byte, tcpCopyBufSize)
	bufDown := make([]byte, tcpCopyBufSize)
	go func() {
		err := errors.New("tcp copy panicked")
		defer func() { results <- copyResult{err} }()
		defer p.recoverFlow("tcp copy")
		_, err = io.CopyBuffer(remote, local, bufUp)
		if err == nil {
			if cw, ok := remote.(interface{ CloseWrite() error }); ok {
				_ = cw.CloseWrite()
			} else {
				err = errors.New("remote cannot half-close")
			}
		}
	}()
	go func() {
		err := errors.New("tcp copy panicked")
		defer func() { results <- copyResult{err} }()
		defer p.recoverFlow("tcp copy")
		_, err = io.CopyBuffer(local, remote, bufDown)
		if err == nil {
			_ = local.CloseWrite()
		}
	}()

	for finished := 0; finished < 2; finished++ {
		select {
		case <-p.bridge.ctx.Done():
			return
		case r := <-results:
			if r.err != nil {
				return
			}
		}
	}
}

func (p *netstackProxy) acceptUDP(request *udp.ForwarderRequest) bool {
	if p.bridge == nil || p.bridge.client == nil {
		return false
	}
	id := request.ID()
	srcIP, ok := netip.AddrFromSlice(id.RemoteAddress.AsSlice())
	if !ok {
		return false
	}
	dstIP, ok := netip.AddrFromSlice(id.LocalAddress.AsSlice())
	if !ok {
		return false
	}

	srcAP := netip.AddrPortFrom(srcIP.Unmap(), id.RemotePort)
	dstAP := netip.AddrPortFrom(dstIP.Unmap(), id.LocalPort)

	if p.bridge.tcpOnly.Load() && dstAP.Port() != 53 {
		p.bridge.policyRejections.Add(1)
		return false
	}
	resolvedDst, ok := p.resolveFlowDestination(dstAP)
	if !ok {
		return false
	}

	flowKey := udpFlowKey{
		isIPv6: srcIP.Is6() || dstIP.Is6(),
		src:    srcAP,
		dst:    dstAP,
	}

	// 1. Reserve flow table capacity under mutex without holding during I/O
	p.udpMu.Lock()
	if p.closed.Load() {
		p.udpMu.Unlock()
		return false
	}
	var evicted *udpFlow
	if p.udpActiveTotal >= maxActiveUDPFlows {
		evicted = p.evictLRULocked()
		if evicted == nil {
			// Every slot is an accept still in progress.
			p.udpMu.Unlock()
			p.bridge.queueExhaustion.Add(1)
			return false
		}
	}
	p.udpActiveTotal++
	// Account for this accept before releasing udpMu. Close changes closed while
	// holding the same mutex, so no Add can race with or occur after Wait.
	p.udpWg.Add(1)
	p.udpMu.Unlock()
	if evicted != nil {
		p.bridge.noteUDPFlowEnded(evicted.sent.Load(), evicted.received.Load())
		evicted.close()
	}

	// 2. Perform endpoint creation and tunnel network dial outside the lock
	var wq waiter.Queue
	ep, tcpipErr := request.CreateEndpoint(&wq)
	if tcpipErr != nil {
		p.rollbackReservation()
		p.udpWg.Done()
		return false
	}

	localConn := gonet.NewUDPConn(&wq, ep)

	ctx, cancel := context.WithCancel(p.bridge.ctx)
	flow := &udpFlow{
		key:       flowKey,
		isDNS:     dstAP.Port() == 53,
		localConn: localConn,
		cancel:    cancel,
	}
	flow.touch()

	p.udpMu.Lock()
	if p.closed.Load() {
		p.rollbackReservationLocked()
		p.udpMu.Unlock()
		flow.close()
		p.udpWg.Done()
		return false
	}
	if existing, ok := p.udpFlows[flowKey]; ok && existing != nil && !existing.closed.Load() {
		p.rollbackReservationLocked()
		p.udpMu.Unlock()
		flow.close()
		p.udpWg.Done()
		return false
	}
	p.udpFlows[flowKey] = flow
	p.udpMu.Unlock()
	p.track(localConn)

	if p.bridge.tcpOnly.Load() && resolvedDst.Port() == 53 {
		go p.runDNSOverTCPFlow(ctx, flow, resolvedDst)
		return true
	}

	go p.dialAndRunUDPFlow(ctx, flow, resolvedDst)
	return true
}

func (p *netstackProxy) dialAndRunUDPFlow(ctx context.Context, flow *udpFlow, resolvedDst netip.AddrPort) {
	defer p.recoverFlow("udp dial")
	// This call owns exactly one udpWg credit (added by acceptUDP) on every
	// path that does not hand the flow off to runUDPFlow, which owns the
	// credit thereafter via its own defer. The guard keeps accounting exact
	// even when a panicking client is contained by recoverFlow above.
	handedOff := false
	defer func() {
		if !handedOff {
			p.udpWg.Done()
		}
	}()
	dialCtx, dialCancel := context.WithTimeout(ctx, udpDialTimeout)
	remoteConn, err := p.bridge.client.DialUDP(dialCtx, resolvedDst)
	dialCancel()
	if err != nil || isNilConn(remoteConn) {
		flow.close()
		p.untrack(flow.localConn)
		p.unregisterFlow(flow)
		return
	}
	flow.remoteMu.Lock()
	flow.remoteConn = remoteConn
	closed := flow.closed.Load()
	flow.remoteMu.Unlock()
	p.track(remoteConn)
	if closed {
		_ = remoteConn.Close()
		p.untrack(flow.localConn)
		p.untrack(remoteConn)
		p.unregisterFlow(flow)
		return
	}
	handedOff = true
	p.runUDPFlow(ctx, flow, remoteConn)
}

func (p *netstackProxy) rollbackReservation() {
	p.udpMu.Lock()
	defer p.udpMu.Unlock()
	p.rollbackReservationLocked()
}

func (p *netstackProxy) rollbackReservationLocked() {
	if p.udpActiveTotal > 0 {
		p.udpActiveTotal--
	}
}

func (p *netstackProxy) unregisterFlow(flow *udpFlow) {
	p.udpMu.Lock()
	defer p.udpMu.Unlock()
	p.unregisterFlowLocked(flow)
}

// unregisterFlowLocked releases the flow's table slot exactly once, whether
// it is called by eviction or by the flow's own teardown.
func (p *netstackProxy) unregisterFlowLocked(flow *udpFlow) {
	if flow.unregistered.Swap(true) {
		return
	}
	if cur, ok := p.udpFlows[flow.key]; ok && cur == flow {
		delete(p.udpFlows, flow.key)
	}
	if p.udpActiveTotal > 0 {
		p.udpActiveTotal--
	}
}

// evictLRULocked frees the slot of the least recently active registered flow
// and returns it for the caller to close outside udpMu. It returns nil when
// no registered flow exists (every slot is an accept still in progress).
func (p *netstackProxy) evictLRULocked() *udpFlow {
	var victim *udpFlow
	var victimLast int64
	for _, f := range p.udpFlows {
		if f.unregistered.Load() {
			continue
		}
		if last := f.lastActive.Load(); victim == nil || last < victimLast {
			victim, victimLast = f, last
		}
	}
	if victim == nil {
		return nil
	}
	p.unregisterFlowLocked(victim)
	p.bridge.udpEvictions.Add(1)
	return victim
}

// runDNSOverTCPFlow carries one app UDP/53 socket over a single pipelined TCP
// connection to the resolver: queries go out as they arrive and answers come
// back as they are ready, so the A and AAAA lookups of one getaddrinfo share
// a dial and run in parallel. A broken connection is redialed once for the
// next query.
func (p *netstackProxy) runDNSOverTCPFlow(ctx context.Context, flow *udpFlow, dst netip.AddrPort) {
	defer p.recoverFlow("dns-over-tcp flow")
	defer func() {
		flow.close()
		p.untrack(flow.localConn)
		p.unregisterFlow(flow)
		p.udpWg.Done()
	}()
	var sess *dnsTCPSession
	defer func() {
		if sess != nil {
			sess.close()
		}
	}()
	buf := make([]byte, maxDNSMessageSize)
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		n, err := flow.localConn.Read(buf)
		if err != nil {
			return
		}
		flow.touch()
		flow.sent.Add(1)
		p.bridge.dnsQueries.Add(1)
		query := append([]byte(nil), buf[:n]...)
		if sess != nil && sess.send(query) == nil {
			continue
		}
		// No connection yet, or it broke: dial once more for this query.
		if sess != nil {
			sess.close()
		}
		if sess, err = p.startDNSOverTCP(ctx, dst, flow); err != nil {
			return
		}
		if err := sess.send(query); err != nil {
			return
		}
	}
}

// maxPendingDNSOverTCP bounds the queries remembered per connection for the
// answer's UDP size limit; answers to others use the 512 B classic limit.
const maxPendingDNSOverTCP = 256

// dnsTCPSession is one pipelined TCP connection to the resolver through the
// gateway (RFC 7766), shared by every query of one app UDP/53 socket.
type dnsTCPSession struct {
	p         *netstackProxy
	conn      net.Conn
	stopWatch func() bool
	done      chan struct{} // closed when readAnswers returns

	mu      sync.Mutex
	broken  bool
	pending map[uint16][]byte // query by DNS ID
}

func (p *netstackProxy) startDNSOverTCP(ctx context.Context, dst netip.AddrPort, flow *udpFlow) (*dnsTCPSession, error) {
	dialCtx, cancel := context.WithTimeout(ctx, tcpDialTimeout)
	defer cancel()
	conn, err := p.bridge.client.DialTCP(dialCtx, dst)
	if err != nil || isNilConn(conn) {
		closeConn(conn)
		if err == nil {
			err = errors.New("gateway dial returned nil connection without error")
		}
		return nil, err
	}
	// Tracked so Close()/Stop cannot hang on a silent resolver (AUDIT H4); a
	// resolver that never answers is also cut off when the flow idles out.
	p.track(conn)
	s := &dnsTCPSession{
		p:       p,
		conn:    conn,
		done:    make(chan struct{}),
		pending: make(map[uint16][]byte),
	}
	s.stopWatch = context.AfterFunc(ctx, func() { _ = conn.Close() })
	go s.readAnswers(flow)
	return s, nil
}

// send writes one query without waiting for earlier answers.
func (s *dnsTCPSession) send(query []byte) error {
	s.mu.Lock()
	if s.broken {
		s.mu.Unlock()
		return net.ErrClosed
	}
	if len(query) >= 2 && len(s.pending) < maxPendingDNSOverTCP {
		s.pending[binary.BigEndian.Uint16(query[:2])] = query
	}
	s.mu.Unlock()
	frame := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(frame, uint16(len(query)))
	copy(frame[2:], query)
	if err := s.conn.SetWriteDeadline(time.Now().Add(dnsTCPIOTimeout)); err != nil {
		return err
	}
	_, err := s.conn.Write(frame)
	return err
}

// readAnswers returns each answer to the app as it arrives, cut to the UDP
// size its query allowed.
func (s *dnsTCPSession) readAnswers(flow *udpFlow) {
	defer close(s.done)
	defer s.markBroken()
	defer s.p.recoverFlow("dns-over-tcp answers")
	var hdr [2]byte
	for {
		if _, err := io.ReadFull(s.conn, hdr[:]); err != nil {
			return
		}
		resp := make([]byte, binary.BigEndian.Uint16(hdr[:]))
		if _, err := io.ReadFull(s.conn, resp); err != nil {
			return
		}
		var query []byte
		if len(resp) >= 2 {
			id := binary.BigEndian.Uint16(resp[:2])
			s.mu.Lock()
			query = s.pending[id]
			delete(s.pending, id)
			s.mu.Unlock()
		}
		flow.touch()
		flow.received.Add(1)
		if _, err := flow.localConn.Write(truncateDNSForUDP(query, resp)); err != nil {
			return
		}
	}
}

func (s *dnsTCPSession) markBroken() {
	s.mu.Lock()
	s.broken = true
	s.mu.Unlock()
	_ = s.conn.Close()
}

func (s *dnsTCPSession) close() {
	s.stopWatch()
	s.markBroken()
	s.p.untrack(s.conn)
	<-s.done
}

func (p *netstackProxy) runUDPFlow(ctx context.Context, flow *udpFlow, remoteConn net.Conn) {
	defer p.recoverFlow("udp flow")
	defer func() {
		flow.close()
		p.untrack(flow.localConn)
		p.untrack(remoteConn)
		p.unregisterFlow(flow)
		p.udpWg.Done()
	}()

	done := make(chan struct{}, 2)

	go func() {
		defer p.recoverFlow("udp flow copy")
		buf := make([]byte, udpUplinkBufSize)
		for {
			n, err := flow.localConn.Read(buf)
			if err != nil {
				break
			}
			flow.touch()
			if n > maxTunnelUDPPayload {
				// Reassembled from IP fragments (single oversized packets are
				// refused with ICMP in handleOutboundPacket). The tunnel would
				// lose it, so drop it visibly instead.
				p.bridge.mtuExceeded.Add(1)
				continue
			}
			flow.sent.Add(1)
			if _, err := remoteConn.Write(buf[:n]); err != nil {
				break
			}
			if flow.isDNS {
				p.bridge.dnsQueries.Add(1)
			}
		}
		done <- struct{}{}
	}()

	go func() {
		defer p.recoverFlow("udp flow copy")
		buf := make([]byte, udpDownlinkBufSize)
		for {
			n, err := remoteConn.Read(buf)
			if err != nil {
				break
			}
			flow.touch()
			flow.received.Add(1)
			if _, err := flow.localConn.Write(buf[:n]); err != nil {
				break
			}
		}
		done <- struct{}{}
	}()

	select {
	case <-ctx.Done():
	case <-done:
	}
}

func (p *netstackProxy) cleanupIdleUDPFlows(ready chan struct{}) {
	defer p.recoverFlow("udp gc")
	ticker := time.NewTicker(udpGCInterval)
	defer ticker.Stop()
	signalReady(ready)

	for {
		select {
		case <-p.bridge.ctx.Done():
			return
		case <-ticker.C:
			p.expireIdleUDPFlows(time.Now())
		}
	}
}

// expireIdleUDPFlows closes the flows idle past their timeout at now and
// reports how each ended, so gateway UDP loss can re-latch tcpOnly.
func (p *netstackProxy) expireIdleUDPFlows(now time.Time) {
	for _, f := range p.expiredUDPFlows(now) {
		p.bridge.noteUDPFlowEnded(f.sent.Load(), f.received.Load())
		f.close()
	}
}

// expiredUDPFlows returns the flows that have been idle longer than their
// idle timeout at now.
func (p *netstackProxy) expiredUDPFlows(now time.Time) []*udpFlow {
	nowNano := now.UnixNano()
	var expired []*udpFlow
	p.udpMu.Lock()
	for _, flow := range p.udpFlows {
		if nowNano-flow.lastActive.Load() > int64(flow.idleTimeout()) {
			expired = append(expired, flow)
		}
	}
	p.udpMu.Unlock()
	return expired
}

func (p *netstackProxy) track(conn net.Conn) {
	p.conns.Store(conn, struct{}{})
}

func (p *netstackProxy) untrack(conn net.Conn) {
	p.conns.Delete(conn)
}

func (p *netstackProxy) Close() {
	// Serialize the closed transition with reservation/Add so Wait can never
	// observe zero and return while an accept is about to increment udpWg.
	p.udpMu.Lock()
	if p.closed.Load() {
		p.udpMu.Unlock()
		return
	}
	p.closed.Store(true)
	flows := make([]*udpFlow, 0, len(p.udpFlows))
	for _, f := range p.udpFlows {
		flows = append(flows, f)
	}
	p.udpMu.Unlock()

	p.link.Close()
	p.stack.Close()

	for _, f := range flows {
		f.close()
	}

	p.conns.Range(func(key, _ any) bool {
		_ = key.(net.Conn).Close()
		return true
	})

	done := make(chan struct{})
	go func() {
		p.udpWg.Wait()
		p.tcpWg.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(stopWaitTimeout):
		// Flows were cancelled/closed; abandon waiters so TunBridge.Stop stays bounded.
	}
	p.stack.Destroy()
}
