package engine

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"log"
	"net"
	"net/netip"
	"time"
)

// udpProbeTargets are public STUN servers used to check that the gateway
// forwards generic UDP. A DNS answer is not enough: gateways often handle
// port 53 specially, so it says nothing about QUIC or WebRTC. The addresses
// are fixed because DNS may be the thing that is broken; two operators keep
// one outage from latching tcpOnly.
var udpProbeTargets = []netip.AddrPort{
	netip.MustParseAddrPort("162.159.207.0:3478"),   // stun.cloudflare.com
	netip.MustParseAddrPort("74.125.250.129:19302"), // stun.l.google.com
}

const (
	stunMagicCookie   = 0x2112A442
	stunResendAfter   = time.Second
	stunBindingReq    = 0x0001
	stunBindingOK     = 0x0101
	stunHeaderLen     = 20
	stunTxIDOffset    = 8
	stunMaxAnswerSize = 1500

	// udpUnansweredRelatch is how many UDP flows in a row may end without a
	// single reply before gateway UDP is probed again while tcpOnly is off.
	udpUnansweredRelatch = 8
)

// probeGatewayUDP reports whether any probe target answers a STUN binding
// request sent through dial (the gateway). Targets are probed in parallel.
func probeGatewayUDP(ctx context.Context, dial func(context.Context, netip.AddrPort) (net.Conn, error)) bool {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	results := make(chan bool, len(udpProbeTargets))
	for _, dst := range udpProbeTargets {
		go func() { results <- stunAnswers(ctx, dial, dst) }()
	}
	for range udpProbeTargets {
		if <-results {
			return true
		}
	}
	return false
}

// stunAnswers sends a binding request to dst, resending every second, until a
// matching success response arrives or ctx ends.
func stunAnswers(ctx context.Context, dial func(context.Context, netip.AddrPort) (net.Conn, error), dst netip.AddrPort) bool {
	conn, err := dial(ctx, dst)
	if err != nil || isNilConn(conn) {
		return false
	}
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { conn.Close() })
	defer stop()

	req := make([]byte, stunHeaderLen)
	binary.BigEndian.PutUint16(req[0:2], stunBindingReq)
	binary.BigEndian.PutUint32(req[4:8], stunMagicCookie)
	if _, err := rand.Read(req[stunTxIDOffset:stunHeaderLen]); err != nil {
		return false
	}
	buf := make([]byte, stunMaxAnswerSize)
	for ctx.Err() == nil {
		if _, err := conn.Write(req); err != nil {
			return false
		}
		deadline := time.Now().Add(stunResendAfter)
		if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
			deadline = d
		}
		_ = conn.SetReadDeadline(deadline)
		for {
			n, err := conn.Read(buf)
			if err != nil {
				var ne net.Error
				if errors.As(err, &ne) && ne.Timeout() {
					break // resend
				}
				return false
			}
			if isStunBindingSuccess(buf[:n], req[stunTxIDOffset:stunHeaderLen]) {
				return true
			}
		}
	}
	return false
}

func isStunBindingSuccess(msg, txID []byte) bool {
	return len(msg) >= stunHeaderLen &&
		binary.BigEndian.Uint16(msg[0:2]) == stunBindingOK &&
		binary.BigEndian.Uint32(msg[4:8]) == stunMagicCookie &&
		bytes.Equal(msg[stunTxIDOffset:stunHeaderLen], txID)
}

// noteUDPFlowEnded records how a UDP flow ended (idle expiry or eviction).
// While tcpOnly is off, udpUnansweredRelatch flows in a row with no reply at
// all start a probe of gateway UDP; if it fails, tcpOnly is set so DNS moves
// to TCP and other UDP fails fast. rateCalcLoop's re-probe clears it again.
func (b *TunBridge) noteUDPFlowEnded(sent, received int64) {
	if received > 0 {
		b.udpUnanswered.Store(0)
		return
	}
	if sent == 0 || b.tcpOnly.Load() {
		return
	}
	if b.udpUnanswered.Add(1) < udpUnansweredRelatch {
		return
	}
	b.udpUnanswered.Store(0)
	if b.udpRelatching.CompareAndSwap(false, true) {
		go b.relatchUDP()
	}
}

func (b *TunBridge) relatchUDP() {
	defer b.recoverPumpLogOnly("udp relatch")
	defer b.udpRelatching.Store(false)
	if b.client == nil || b.ctx == nil {
		return
	}
	prober, ok := b.client.(udpCapability)
	if !ok {
		return
	}
	ctx, cancel := context.WithTimeout(b.ctx, udpProbeTimeout)
	defer cancel()
	if !prober.SupportsUDP(ctx) && b.ctx.Err() == nil {
		b.tcpOnly.Store(true)
		log.Printf("Tailcat gateway UDP stopped answering; DNS now goes over TCP")
	}
}
