package engine

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"sync"
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
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	tcpProxyNIC         = 1
	tcpProxyMTU         = 1280
	tcpProxyQueueSize   = 4096
	tcpProxyMaxInFlight = 1024
	tcpProxyDialTimeout = 15 * time.Second
)

// tcpProxyStack terminates Android TCP flows with gVisor's production TCP
// implementation and proxies their byte streams through Tailcat. The former
// hand-written TCP shim could not safely implement retransmission, reordering,
// flow control, or MTU segmentation and therefore corrupted TLS responses.
type tcpProxyStack struct {
	bridge *TunBridge
	stack  *stack.Stack
	link   *channel.Endpoint

	conns  sync.Map // map[net.Conn]struct{}
	closed sync.Once
}

func newTCPProxyStack(bridge *TunBridge) (*tcpProxyStack, error) {
	ipStack := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol,
			ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol,
		},
	})

	sack := tcpip.TCPSACKEnabled(true)
	if err := ipStack.SetTransportProtocolOption(tcp.ProtocolNumber, &sack); err != nil {
		ipStack.Destroy()
		return nil, fmt.Errorf("enable TCP SACK: %v", err)
	}

	linkEP := channel.New(tcpProxyQueueSize, tcpProxyMTU, "")
	if err := ipStack.CreateNIC(tcpProxyNIC, linkEP); err != nil {
		ipStack.Destroy()
		return nil, fmt.Errorf("create netstack NIC: %v", err)
	}
	ipStack.SetPromiscuousMode(tcpProxyNIC, true)
	ipStack.SetSpoofing(tcpProxyNIC, true)

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
		{Destination: v4Default, NIC: tcpProxyNIC},
		{Destination: v6Default, NIC: tcpProxyNIC},
	})

	proxy := &tcpProxyStack{bridge: bridge, stack: ipStack, link: linkEP}
	forwarder := tcp.NewForwarder(ipStack, 0, tcpProxyMaxInFlight, proxy.accept)
	ipStack.SetTransportProtocolHandler(tcp.ProtocolNumber, forwarder.HandlePacket)
	return proxy, nil
}

func (p *tcpProxyStack) inject(pkt []byte, ipv6Packet bool) {
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

func (p *tcpProxyStack) writeLoop() {
	for {
		packet := p.link.ReadContext(p.bridge.ctx)
		if packet == nil {
			return
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
			return
		}
	}
}

func (p *tcpProxyStack) accept(request *tcp.ForwarderRequest) {
	id := request.ID()
	destinationIP, ok := netip.AddrFromSlice(id.LocalAddress.AsSlice())
	if !ok {
		request.Complete(true)
		return
	}
	destination := netip.AddrPortFrom(destinationIP.Unmap(), id.LocalPort)

	ctx, cancel := context.WithTimeout(p.bridge.ctx, tcpProxyDialTimeout)
	remote, err := p.bridge.client.DialTCP(ctx, destination)
	cancel()
	if err != nil {
		request.Complete(true)
		return
	}

	var waitQueue waiter.Queue
	endpoint, tcpErr := request.CreateEndpoint(&waitQueue)
	if tcpErr != nil {
		_ = remote.Close()
		request.Complete(true)
		return
	}
	request.Complete(false)

	local := gonet.NewTCPConn(&waitQueue, endpoint)
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

func (p *tcpProxyStack) track(conn net.Conn) {
	p.conns.Store(conn, struct{}{})
}

func (p *tcpProxyStack) untrack(conn net.Conn) {
	p.conns.Delete(conn)
}

func (p *tcpProxyStack) Close() {
	p.closed.Do(func() {
		p.link.Close()
		p.stack.Close()
		p.conns.Range(func(key, _ any) bool {
			_ = key.(net.Conn).Close()
			return true
		})
		p.stack.Destroy()
	})
}
