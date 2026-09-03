package engine

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"

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
	netstackNIC                = 1
	netstackMTU                = 1280
	netstackQueueSize          = 4096
	tcpMaxInFlight             = 1024
	tcpDialTimeout             = 15 * time.Second
	udpDialTimeout             = 10 * time.Second
	ipv6DialTimeout            = 250 * time.Millisecond
	udpIdleTimeout             = 30 * time.Second
	maxActiveUDPFlowsTotal     = 1024
	maxActiveUDPFlowsPerSource = 128
	maxUDPPacketSize           = 65535
)

type udpFlowKey struct {
	isIPv6 bool
	src    netip.AddrPort
	dst    netip.AddrPort
}

type udpFlow struct {
	key          udpFlowKey
	localConn    *gonet.UDPConn
	remoteConn   net.Conn
	lastActive   atomic.Int64 // unix timestamp in nanoseconds
	closed       atomic.Bool
	closeOnce    sync.Once
	unregistered atomic.Bool
	cancel       context.CancelFunc
}

func (f *udpFlow) touch() {
	f.lastActive.Store(time.Now().UnixNano())
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
		if f.remoteConn != nil {
			_ = f.remoteConn.Close()
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

	udpMu              sync.Mutex
	udpFlows           map[udpFlowKey]*udpFlow
	udpActivePerSource map[netip.Addr]int
	udpActiveTotal     int
	udpWg              sync.WaitGroup // pending accepts and active flow pumps

	closed atomic.Bool
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

	linkEP := channel.New(netstackQueueSize, netstackMTU, "")
	linkEP.LinkEPCapabilities |= stack.CapabilityRXChecksumOffload
	if err := ipStack.CreateNIC(netstackNIC, linkEP); err != nil {
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
		bridge:             bridge,
		stack:              ipStack,
		link:               linkEP,
		udpFlows:           make(map[udpFlowKey]*udpFlow),
		udpActivePerSource: make(map[netip.Addr]int),
	}

	tcpForwarder := tcp.NewForwarder(ipStack, 0, tcpMaxInFlight, proxy.acceptTCP)
	ipStack.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpForwarder.HandlePacket)

	udpForwarder := udp.NewForwarder(ipStack, proxy.acceptUDP)
	ipStack.SetTransportProtocolHandler(udp.ProtocolNumber, udpForwarder.HandlePacket)

	return proxy, nil
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
		out := append([]byte(nil), view.AsSlice()...)
		view.Release()
		packet.DecRef()
		if len(out) == 0 {
			continue
		}
		p.bridge.rxBytes.Add(int64(len(out)))
		if err := p.bridge.writeTunPacket(out); err != nil {
			if p.bridge.closed.Load() || p.bridge.ctx.Err() != nil {
				return nil
			}
			return err
		}
	}
}

func (p *netstackProxy) resolveDNSDestination(dstAP netip.AddrPort) netip.AddrPort {
	if dstAP.Port() != 53 || p.bridge == nil {
		return dstAP
	}
	cfg := p.bridge.GetDNSConfig()
	if cfg != nil && cfg.Policy == "FORCED_RESOLVER" && cfg.ForcedDNS.IsValid() {
		return cfg.ForcedDNS
	}
	return dstAP
}

func dialTimeoutFor(dst netip.AddrPort, v4Timeout time.Duration) time.Duration {
	if dst.Addr().Is6() {
		return ipv6DialTimeout
	}
	return v4Timeout
}

func (p *netstackProxy) acceptTCP(request *tcp.ForwarderRequest) {
	if p.closed.Load() || p.bridge == nil || p.bridge.client == nil {
		request.Complete(true)
		return
	}
	go p.proxyTCP(request)
}

func (p *netstackProxy) proxyTCP(request *tcp.ForwarderRequest) {
	if p.closed.Load() || p.bridge == nil || p.bridge.client == nil {
		request.Complete(true)
		return
	}
	id := request.ID()
	destinationIP, ok := netip.AddrFromSlice(id.LocalAddress.AsSlice())
	if !ok {
		request.Complete(true)
		return
	}
	destination := netip.AddrPortFrom(destinationIP.Unmap(), id.LocalPort)
	resolvedDst := p.resolveDNSDestination(destination)

	type dialResult struct {
		conn net.Conn
		err  error
	}
	dialed := make(chan dialResult, 1)
	go func() {
		ctx, cancel := context.WithTimeout(p.bridge.ctx, dialTimeoutFor(resolvedDst, tcpDialTimeout))
		conn, err := p.bridge.client.DialTCP(ctx, resolvedDst)
		cancel()
		dialed <- dialResult{conn, err}
	}()

	var waitQueue waiter.Queue
	endpoint, tcpErr := request.CreateEndpoint(&waitQueue)
	if tcpErr != nil {
		request.Complete(true)
		res := <-dialed
		if res.conn != nil {
			_ = res.conn.Close()
		}
		return
	}
	request.Complete(false)
	local := gonet.NewTCPConn(&waitQueue, endpoint)

	res := <-dialed
	if res.err != nil {
		if strings.Contains(res.err.Error(), "proxy destination not permitted") {
			p.bridge.policyRejections.Add(1)
		}
		_ = local.Close()
		if res.conn != nil {
			_ = res.conn.Close()
		}
		return
	}
	remote := res.conn

	p.track(local)
	p.track(remote)
	defer p.untrack(local)
	defer p.untrack(remote)
	defer local.Close()
	defer remote.Close()

	copyDone := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(remote, local)
		if closeWriter, ok := remote.(interface{ CloseWrite() error }); ok {
			_ = closeWriter.CloseWrite()
		}
		copyDone <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(local, remote)
		_ = local.CloseWrite()
		copyDone <- struct{}{}
	}()

	select {
	case <-p.bridge.ctx.Done():
	case <-copyDone:
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

	if p.bridge.tcpOnly && dstAP.Port() != 53 {
		p.bridge.policyRejections.Add(1)
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
	if p.udpActiveTotal >= maxActiveUDPFlowsTotal {
		p.udpMu.Unlock()
		if p.bridge != nil {
			p.bridge.queueExhaustion.Add(1)
		}
		return false // Global flow limit reached (backpressure drop)
	}
	if p.udpActivePerSource[srcAP.Addr()] >= maxActiveUDPFlowsPerSource {
		p.udpMu.Unlock()
		if p.bridge != nil {
			p.bridge.queueExhaustion.Add(1)
		}
		return false // Per-source flow limit reached
	}
	p.udpActiveTotal++
	p.udpActivePerSource[srcAP.Addr()]++
	// Account for this accept before releasing udpMu. Close changes closed while
	// holding the same mutex, so no Add can race with or occur after Wait.
	p.udpWg.Add(1)
	p.udpMu.Unlock()

	// 2. Perform endpoint creation and tunnel network dial outside the lock
	var wq waiter.Queue
	ep, tcpipErr := request.CreateEndpoint(&wq)
	if tcpipErr != nil {
		p.rollbackReservation(srcAP.Addr())
		p.udpWg.Done()
		return false
	}

	localConn := gonet.NewUDPConn(&wq, ep)

	resolvedDst := p.resolveDNSDestination(dstAP)

	if p.bridge.tcpOnly && resolvedDst.Port() == 53 {
		ctx, cancel := context.WithCancel(p.bridge.ctx)
		flow := &udpFlow{
			key:       flowKey,
			localConn: localConn,
			cancel:    cancel,
		}
		flow.touch()
		p.udpMu.Lock()
		if p.closed.Load() {
			p.rollbackReservationLocked(srcAP.Addr())
			p.udpMu.Unlock()
			flow.close()
			p.udpWg.Done()
			return false
		}
		p.udpFlows[flowKey] = flow
		p.udpMu.Unlock()
		p.track(localConn)
		go p.runDNSOverTCPFlow(ctx, flow, resolvedDst)
		return true
	}

	ctx, cancel := context.WithCancel(p.bridge.ctx)
	dialCtx, dialCancel := context.WithTimeout(ctx, dialTimeoutFor(resolvedDst, udpDialTimeout))
	remoteConn, err := p.bridge.client.DialUDP(dialCtx, resolvedDst)
	dialCancel()
	if err != nil {
		if strings.Contains(err.Error(), "proxy destination not permitted") {
			p.bridge.policyRejections.Add(1)
		}
		localConn.Close()
		cancel()
		p.rollbackReservation(srcAP.Addr())
		p.udpWg.Done()
		return false
	}

	flow := &udpFlow{
		key:        flowKey,
		localConn:  localConn,
		remoteConn: remoteConn,
		cancel:     cancel,
	}
	flow.touch()

	// 3. Register active flow or roll back if closed concurrently
	p.udpMu.Lock()
	if p.closed.Load() {
		p.rollbackReservationLocked(srcAP.Addr())
		p.udpMu.Unlock()
		flow.close()
		p.udpWg.Done()
		return false
	}
	p.udpFlows[flowKey] = flow
	p.udpMu.Unlock()

	p.track(localConn)
	p.track(remoteConn)

	go p.runUDPFlow(ctx, flow)
	return true
}

func (p *netstackProxy) rollbackReservation(srcAddr netip.Addr) {
	p.udpMu.Lock()
	defer p.udpMu.Unlock()
	p.rollbackReservationLocked(srcAddr)
}

func (p *netstackProxy) rollbackReservationLocked(srcAddr netip.Addr) {
	if p.udpActiveTotal > 0 {
		p.udpActiveTotal--
	}
	if p.udpActivePerSource[srcAddr] > 0 {
		p.udpActivePerSource[srcAddr]--
		if p.udpActivePerSource[srcAddr] == 0 {
			delete(p.udpActivePerSource, srcAddr)
		}
	}
}

func (p *netstackProxy) unregisterFlow(flow *udpFlow) {
	if flow.unregistered.Swap(true) {
		return
	}
	p.udpMu.Lock()
	defer p.udpMu.Unlock()

	if cur, ok := p.udpFlows[flow.key]; ok && cur == flow {
		delete(p.udpFlows, flow.key)
	}
	if p.udpActiveTotal > 0 {
		p.udpActiveTotal--
	}
	srcAddr := flow.key.src.Addr()
	if p.udpActivePerSource[srcAddr] > 0 {
		p.udpActivePerSource[srcAddr]--
		if p.udpActivePerSource[srcAddr] == 0 {
			delete(p.udpActivePerSource, srcAddr)
		}
	}
}

func (p *netstackProxy) runDNSOverTCPFlow(ctx context.Context, flow *udpFlow, dst netip.AddrPort) {
	defer func() {
		flow.close()
		p.untrack(flow.localConn)
		p.unregisterFlow(flow)
		p.udpWg.Done()
	}()
	buf := make([]byte, maxUDPPacketSize)
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		_ = flow.localConn.SetReadDeadline(time.Now().Add(udpIdleTimeout))
		n, err := flow.localConn.Read(buf)
		if err != nil {
			return
		}
		flow.touch()
		query := append([]byte(nil), buf[:n]...)
		if err := p.exchangeDNSOverTCP(ctx, dst, query, flow); err != nil {
			return
		}
	}
}

func (p *netstackProxy) exchangeDNSOverTCP(ctx context.Context, dst netip.AddrPort, query []byte, flow *udpFlow) error {
	dialCtx, cancel := context.WithTimeout(ctx, dialTimeoutFor(dst, tcpDialTimeout))
	defer cancel()
	conn, err := p.bridge.client.DialTCP(dialCtx, dst)
	if err != nil {
		if strings.Contains(err.Error(), "proxy destination not permitted") {
			p.bridge.policyRejections.Add(1)
		}
		return err
	}
	defer conn.Close()
	if err := binary.Write(conn, binary.BigEndian, uint16(len(query))); err != nil {
		return err
	}
	if _, err := conn.Write(query); err != nil {
		return err
	}
	var ln uint16
	if err := binary.Read(conn, binary.BigEndian, &ln); err != nil {
		return err
	}
	if int(ln) > maxUDPPacketSize {
		return fmt.Errorf("dns-over-tcp response too large: %d", ln)
	}
	resp := make([]byte, ln)
	if _, err := io.ReadFull(conn, resp); err != nil {
		return err
	}
	flow.touch()
	_, err = flow.localConn.Write(resp)
	return err
}

func (p *netstackProxy) runUDPFlow(ctx context.Context, flow *udpFlow) {
	defer func() {
		flow.close()
		p.untrack(flow.localConn)
		p.untrack(flow.remoteConn)
		p.unregisterFlow(flow)
		p.udpWg.Done()
	}()

	done := make(chan struct{}, 2)

	// Inbound TUN (local endpoint) -> Tunnel exit (remoteConn)
	// Preserves zero-length datagrams (n >= 0 written directly)
	go func() {
		buf := make([]byte, maxUDPPacketSize)
		for {
			_ = flow.localConn.SetReadDeadline(time.Now().Add(udpIdleTimeout))
			n, err := flow.localConn.Read(buf)
			if err != nil {
				break
			}
			flow.touch()
			p.bridge.txBytes.Add(int64(n))
			if _, err := flow.remoteConn.Write(buf[:n]); err != nil {
				break
			}
		}
		done <- struct{}{}
	}()

	// Tunnel exit reply (remoteConn) -> Inbound TUN (local endpoint)
	// Preserves zero-length datagrams (n >= 0 written directly)
	go func() {
		buf := make([]byte, maxUDPPacketSize)
		for {
			_ = flow.remoteConn.SetReadDeadline(time.Now().Add(udpIdleTimeout))
			n, err := flow.remoteConn.Read(buf)
			if err != nil {
				break
			}
			flow.touch()
			p.bridge.rxBytes.Add(int64(n))
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
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	signalReady(ready)

	for {
		select {
		case <-p.bridge.ctx.Done():
			return
		case <-ticker.C:
			now := time.Now().UnixNano()
			var expired []*udpFlow

			p.udpMu.Lock()
			for _, flow := range p.udpFlows {
				lastActive := flow.lastActive.Load()
				if now-lastActive > int64(udpIdleTimeout) {
					expired = append(expired, flow)
				}
			}
			p.udpMu.Unlock()

			for _, f := range expired {
				f.close()
			}
		}
	}
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

	p.udpWg.Wait()
	p.stack.Destroy()
}
