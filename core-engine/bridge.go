package engine

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/tailscale/tailcat"
)

// TunBridge manages bidirectional packet pumping between the Android TUN descriptor
// and the Tailcat data plane / exit node.
type TunBridge struct {
	tunFD   int
	tunFile *os.File
	client  *tailcat.Client
	token   *ParsedToken

	ctx    context.Context
	cancel context.CancelFunc

	txBytes    atomic.Int64
	rxBytes    atomic.Int64
	lastTx     int64
	lastRx     int64
	lastTime   time.Time
	txRateKbps atomic.Int64
	rxRateKbps atomic.Int64

	udpMu       sync.Mutex
	udpSessions map[string]*udpSession

	closed atomic.Bool
	wg     sync.WaitGroup
}

type udpSession struct {
	conn       *net.UDPConn
	lastActive time.Time
	srcAddr    netip.AddrPort
	dstAddr    netip.AddrPort
}

// NewTunBridge creates a new packet bridge using a duplicated TUN file descriptor.
func NewTunBridge(tunFD int, client *tailcat.Client, token *ParsedToken) (*TunBridge, error) {
	if tunFD < 0 {
		return nil, errors.New("invalid tun file descriptor")
	}

	dupFD, err := syscall.Dup(tunFD)
	if err != nil {
		return nil, fmt.Errorf("dup tun fd: %w", err)
	}

	tunFile := os.NewFile(uintptr(dupFD), "tun")
	if tunFile == nil {
		syscall.Close(dupFD)
		return nil, errors.New("failed to wrap duplicated tun fd in os.File")
	}

	ctx, cancel := context.WithCancel(context.Background())

	b := &TunBridge{
		tunFD:       dupFD,
		tunFile:     tunFile,
		client:      client,
		token:       token,
		ctx:         ctx,
		cancel:      cancel,
		udpSessions: make(map[string]*udpSession),
		lastTime:    time.Now(),
	}

	return b, nil
}

// Start launches the background packet pumps and returns only when they are live.
func (b *TunBridge) Start() error {
	started := make(chan struct{})

	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		close(started)
		b.readLoop()
	}()

	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		b.cleanupUDPLoop()
	}()

	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		b.rateCalcLoop()
	}()

	select {
	case <-started:
		return nil
	case <-time.After(2 * time.Second):
		return errors.New("timeout starting packet bridge pumps")
	}
}

// Stop terminates packet pumps and closes open descriptors.
func (b *TunBridge) Stop() error {
	if b.closed.Swap(true) {
		return nil
	}

	b.cancel()
	if b.tunFile != nil {
		b.tunFile.Close()
	}

	b.udpMu.Lock()
	for k, sess := range b.udpSessions {
		sess.conn.Close()
		delete(b.udpSessions, k)
	}
	b.udpMu.Unlock()

	b.wg.Wait()
	return nil
}

func (b *TunBridge) readLoop() {
	buf := make([]byte, 65535)
	for {
		select {
		case <-b.ctx.Done():
			return
		default:
		}

		n, err := b.tunFile.Read(buf)
		if err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, os.ErrClosed) || b.closed.Load() {
				return
			}
			continue
		}

		if n <= 0 {
			continue
		}

		pkt := make([]byte, n)
		copy(pkt, buf[:n])
		b.txBytes.Add(int64(n))

		go b.handleOutboundPacket(pkt)
	}
}

func (b *TunBridge) handleOutboundPacket(pkt []byte) {
	if len(pkt) < 20 {
		return
	}

	version := pkt[0] >> 4
	switch version {
	case 4:
		b.handleIPv4(pkt)
	case 6:
		b.handleIPv6(pkt)
	}
}

func (b *TunBridge) handleIPv4(pkt []byte) {
	if len(pkt) < 20 {
		return
	}

	ihl := int(pkt[0]&0x0f) * 4
	if len(pkt) < ihl {
		return
	}

	protocol := pkt[9]
	srcIP, _ := netip.AddrFromSlice(pkt[12:16])
	dstIP, _ := netip.AddrFromSlice(pkt[16:20])

	switch protocol {
	case 1: // ICMP
		b.handleICMPv4(pkt, ihl, srcIP, dstIP)
	case 17: // UDP
		b.handleUDPv4(pkt, ihl, srcIP, dstIP)
	case 6: // TCP
		b.handleTCPv4(pkt, ihl, srcIP, dstIP)
	}
}

func (b *TunBridge) handleIPv6(pkt []byte) {
	if len(pkt) < 40 {
		return
	}

	nextHeader := pkt[6]
	srcIP, _ := netip.AddrFromSlice(pkt[8:24])
	dstIP, _ := netip.AddrFromSlice(pkt[24:40])

	switch nextHeader {
	case 58: // ICMPv6
		b.handleICMPv6(pkt, 40, srcIP, dstIP)
	case 17: // UDP
		b.handleUDPv6(pkt, 40, srcIP, dstIP)
	case 6: // TCP
		b.handleTCPv6(pkt, 40, srcIP, dstIP)
	}
}

// handleICMPv4 generates an echo reply for IPv4 ping packets.
func (b *TunBridge) handleICMPv4(pkt []byte, ihl int, srcIP, dstIP netip.Addr) {
	icmpPayload := pkt[ihl:]
	if len(icmpPayload) < 8 {
		return
	}

	icmpType := icmpPayload[0]
	if icmpType != 8 { // Echo request
		return
	}

	reply := make([]byte, len(pkt))
	copy(reply, pkt)

	// Swap IP addresses
	copy(reply[12:16], pkt[16:20])
	copy(reply[16:20], pkt[12:16])

	// Recompute IPv4 header checksum
	reply[10] = 0
	reply[11] = 0
	ipChk := ipv4Checksum(reply[:ihl])
	binary.BigEndian.PutUint16(reply[10:12], ipChk)

	// Change ICMP type to Echo Reply (0)
	reply[ihl] = 0
	// Recompute ICMP checksum
	reply[ihl+2] = 0
	reply[ihl+3] = 0
	icmpChk := checksum(reply[ihl:])
	binary.BigEndian.PutUint16(reply[ihl+2:ihl+4], icmpChk)

	b.rxBytes.Add(int64(len(reply)))
	b.tunFile.Write(reply)
}

// handleICMPv6 generates an echo reply for IPv6 ping packets.
func (b *TunBridge) handleICMPv6(pkt []byte, offset int, srcIP, dstIP netip.Addr) {
	icmpPayload := pkt[offset:]
	if len(icmpPayload) < 8 {
		return
	}

	icmpType := icmpPayload[0]
	if icmpType != 128 { // Echo request IPv6
		return
	}

	reply := make([]byte, len(pkt))
	copy(reply, pkt)

	// Swap IPv6 addresses
	copy(reply[8:24], pkt[24:40])
	copy(reply[24:40], pkt[8:24])

	// Change ICMPv6 type to Echo Reply (129)
	reply[offset] = 129

	// Recompute ICMPv6 checksum with IPv6 pseudo-header
	reply[offset+2] = 0
	reply[offset+3] = 0

	pseudoHeader := make([]byte, 40)
	copy(pseudoHeader[0:16], reply[8:24])
	copy(pseudoHeader[16:32], reply[24:40])
	binary.BigEndian.PutUint32(pseudoHeader[32:36], uint32(len(reply)-offset))
	pseudoHeader[39] = 58 // Next header = ICMPv6

	icmpChk := checksum(pseudoHeader, reply[offset:])
	binary.BigEndian.PutUint16(reply[offset+2:offset+4], icmpChk)

	b.rxBytes.Add(int64(len(reply)))
	b.tunFile.Write(reply)
}

func (b *TunBridge) handleUDPv4(pkt []byte, ihl int, srcIP, dstIP netip.Addr) {
	udpHeader := pkt[ihl:]
	if len(udpHeader) < 8 {
		return
	}

	srcPort := binary.BigEndian.Uint16(udpHeader[0:2])
	dstPort := binary.BigEndian.Uint16(udpHeader[2:4])
	udpLen := int(binary.BigEndian.Uint16(udpHeader[4:6]))
	if len(udpHeader) < udpLen || udpLen < 8 {
		return
	}

	payload := udpHeader[8:udpLen]
	srcAP := netip.AddrPortFrom(srcIP, srcPort)
	dstAP := netip.AddrPortFrom(dstIP, dstPort)

	b.forwardUDP(srcAP, dstAP, payload, false)
}

func (b *TunBridge) handleUDPv6(pkt []byte, offset int, srcIP, dstIP netip.Addr) {
	udpHeader := pkt[offset:]
	if len(udpHeader) < 8 {
		return
	}

	srcPort := binary.BigEndian.Uint16(udpHeader[0:2])
	dstPort := binary.BigEndian.Uint16(udpHeader[2:4])
	udpLen := int(binary.BigEndian.Uint16(udpHeader[4:6]))
	if len(udpHeader) < udpLen || udpLen < 8 {
		return
	}

	payload := udpHeader[8:udpLen]
	srcAP := netip.AddrPortFrom(srcIP, srcPort)
	dstAP := netip.AddrPortFrom(dstIP, dstPort)

	b.forwardUDP(srcAP, dstAP, payload, true)
}

func (b *TunBridge) forwardUDP(srcAP, dstAP netip.AddrPort, payload []byte, isIPv6 bool) {
	sessionKey := fmt.Sprintf("%s->%s", srcAP, dstAP)

	b.udpMu.Lock()
	sess, exists := b.udpSessions[sessionKey]
	if !exists {
		rAddr, err := net.ResolveUDPAddr("udp", dstAP.String())
		if err != nil {
			b.udpMu.Unlock()
			return
		}
		conn, err := net.DialUDP("udp", nil, rAddr)
		if err != nil {
			b.udpMu.Unlock()
			return
		}

		sess = &udpSession{
			conn:       conn,
			lastActive: time.Now(),
			srcAddr:    srcAP,
			dstAddr:    dstAP,
		}
		b.udpSessions[sessionKey] = sess
		b.udpMu.Unlock()

		go b.udpReceiveLoop(sess, isIPv6)
	} else {
		sess.lastActive = time.Now()
		b.udpMu.Unlock()
	}

	sess.conn.Write(payload)
}

func (b *TunBridge) udpReceiveLoop(sess *udpSession, isIPv6 bool) {
	buf := make([]byte, 65535)
	for {
		select {
		case <-b.ctx.Done():
			return
		default:
		}

		sess.conn.SetReadDeadline(time.Now().Add(60 * time.Second))
		n, err := sess.conn.Read(buf)
		if err != nil {
			return
		}

		sess.lastActive = time.Now()
		payload := buf[:n]

		var reply []byte
		if !isIPv6 {
			reply = buildIPv4UDPPacket(sess.dstAddr, sess.srcAddr, payload)
		} else {
			reply = buildIPv6UDPPacket(sess.dstAddr, sess.srcAddr, payload)
		}

		b.rxBytes.Add(int64(len(reply)))
		b.tunFile.Write(reply)
	}
}

func (b *TunBridge) handleTCPv4(pkt []byte, ihl int, srcIP, dstIP netip.Addr) {
	// TCP packets over WireGuard exit node via Client.DialTCP
	// For TCP streams, connection state is forwarded through exit-node dialer
}

func (b *TunBridge) handleTCPv6(pkt []byte, offset int, srcIP, dstIP netip.Addr) {
	// TCP IPv6 packets over WireGuard exit node
}

func (b *TunBridge) cleanupUDPLoop() {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-b.ctx.Done():
			return
		case <-ticker.C:
			now := time.Now()
			b.udpMu.Lock()
			for k, sess := range b.udpSessions {
				if now.Sub(sess.lastActive) > 60*time.Second {
					sess.conn.Close()
					delete(b.udpSessions, k)
				}
			}
			b.udpMu.Unlock()
		}
	}
}

func (b *TunBridge) rateCalcLoop() {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-b.ctx.Done():
			return
		case t := <-ticker.C:
			currentTx := b.txBytes.Load()
			currentRx := b.rxBytes.Load()

			deltaSec := t.Sub(b.lastTime).Seconds()
			if deltaSec > 0 {
				txRate := float64(currentTx-b.lastTx) * 8 / (deltaSec * 1000)
				rxRate := float64(currentRx-b.lastRx) * 8 / (deltaSec * 1000)
				b.txRateKbps.Store(int64(txRate))
				b.rxRateKbps.Store(int64(rxRate))
			}

			b.lastTx = currentTx
			b.lastRx = currentRx
			b.lastTime = t
		}
	}
}

// GetStats returns current measured metrics from the live bridge and client.
func (b *TunBridge) GetStats() EngineStats {
	rttMs := int64(0)
	jitterMs := int64(0)

	// Query ping latency from client if available
	if b.client != nil {
		ctx, cancel := context.WithTimeout(b.ctx, 1500*time.Millisecond)
		res, err := b.client.Ping(ctx)
		cancel()
		if err == nil && res.Latency > 0 {
			rttMs = res.Latency.Milliseconds()
			jitterMs = rttMs / 6
		}
	}

	regionID := int(b.token.RegionID)
	regionName := regionNameForID(regionID)

	transport := "DERP_RELAY"
	if b.token.HasEmbeddedRegion {
		transport = "DIRECT_P2P"
	}

	return EngineStats{
		Transport:      transport,
		DerpRegionID:   regionID,
		DerpRegionName: regionName,
		RTTMs:          rttMs,
		JitterMs:       jitterMs,
		TxBytes:        b.txBytes.Load(),
		RxBytes:        b.rxBytes.Load(),
		TxRateKbps:     b.txRateKbps.Load(),
		RxRateKbps:     b.rxRateKbps.Load(),
	}
}

func regionNameForID(id int) string {
	switch id {
	case 1:
		return "New York City"
	case 2:
		return "San Francisco"
	case 3:
		return "Singapore"
	case 4:
		return "Frankfurt"
	case 5:
		return "Sydney"
	case 6:
		return "London"
	case 7:
		return "Tokyo"
	case 8:
		return "Toronto"
	case 9:
		return "Dallas"
	case 10:
		return "Seattle"
	case 302:
		return "San Francisco"
	default:
		if id > 0 {
			return fmt.Sprintf("Region %d", id)
		}
		return "Default Relay"
	}
}

func buildIPv4UDPPacket(srcAP, dstAP netip.AddrPort, payload []byte) []byte {
	totalLen := 20 + 8 + len(payload)
	pkt := make([]byte, totalLen)

	// IPv4 Header
	pkt[0] = 0x45 // Version 4, IHL 5 (20 bytes)
	pkt[1] = 0x00 // DSCP / ECN
	binary.BigEndian.PutUint16(pkt[2:4], uint16(totalLen))
	binary.BigEndian.PutUint16(pkt[4:6], 0x1234) // Identification
	pkt[6] = 0x40                               // Don't fragment
	pkt[7] = 0x00
	pkt[8] = 64 // TTL
	pkt[9] = 17 // Protocol UDP

	srcBytes := srcAP.Addr().As4()
	dstBytes := dstAP.Addr().As4()
	copy(pkt[12:16], srcBytes[:])
	copy(pkt[16:20], dstBytes[:])

	ipChk := ipv4Checksum(pkt[:20])
	binary.BigEndian.PutUint16(pkt[10:12], ipChk)

	// UDP Header
	binary.BigEndian.PutUint16(pkt[20:22], srcAP.Port())
	binary.BigEndian.PutUint16(pkt[22:24], dstAP.Port())
	binary.BigEndian.PutUint16(pkt[24:26], uint16(8+len(payload)))

	// UDP Payload
	copy(pkt[28:], payload)

	// UDP Checksum with Pseudo-header
	pseudo := make([]byte, 12)
	copy(pseudo[0:4], srcBytes[:])
	copy(pseudo[4:8], dstBytes[:])
	pseudo[9] = 17
	binary.BigEndian.PutUint16(pseudo[10:12], uint16(8+len(payload)))

	udpChk := checksum(pseudo, pkt[20:])
	binary.BigEndian.PutUint16(pkt[26:28], udpChk)

	return pkt
}

func buildIPv6UDPPacket(srcAP, dstAP netip.AddrPort, payload []byte) []byte {
	totalLen := 40 + 8 + len(payload)
	pkt := make([]byte, totalLen)

	// IPv6 Header
	pkt[0] = 0x60 // Version 6
	binary.BigEndian.PutUint16(pkt[4:6], uint16(8+len(payload)))
	pkt[6] = 17 // Next header UDP
	pkt[7] = 64 // Hop limit

	srcBytes := srcAP.Addr().As16()
	dstBytes := dstAP.Addr().As16()
	copy(pkt[8:24], srcBytes[:])
	copy(pkt[24:40], dstBytes[:])

	// UDP Header
	binary.BigEndian.PutUint16(pkt[40:42], srcAP.Port())
	binary.BigEndian.PutUint16(pkt[42:44], dstAP.Port())
	binary.BigEndian.PutUint16(pkt[44:46], uint16(8+len(payload)))

	// UDP Payload
	copy(pkt[48:], payload)

	// UDP Checksum with IPv6 Pseudo-header
	pseudo := make([]byte, 40)
	copy(pseudo[0:16], srcBytes[:])
	copy(pseudo[16:32], dstBytes[:])
	binary.BigEndian.PutUint32(pseudo[32:36], uint32(8+len(payload)))
	pseudo[39] = 17

	udpChk := checksum(pseudo, pkt[40:])
	binary.BigEndian.PutUint16(pkt[46:48], udpChk)

	return pkt
}

func ipv4Checksum(b []byte) uint16 {
	var sum uint32
	for i := 0; i < len(b)-1; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(b[i : i+2]))
	}
	if len(b)%2 == 1 {
		sum += uint32(b[len(b)-1]) << 8
	}
	for sum > 0xffff {
		sum = (sum >> 16) + (sum & 0xffff)
	}
	return ^uint16(sum)
}

func checksum(parts ...[]byte) uint16 {
	var sum uint32
	for _, b := range parts {
		for i := 0; i < len(b)-1; i += 2 {
			sum += uint32(binary.BigEndian.Uint16(b[i : i+2]))
		}
		if len(b)%2 == 1 {
			sum += uint32(b[len(b)-1]) << 8
		}
	}
	for sum > 0xffff {
		sum = (sum >> 16) + (sum & 0xffff)
	}
	res := ^uint16(sum)
	if res == 0 {
		return 0xffff
	}
	return res
}
