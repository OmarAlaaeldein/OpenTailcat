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

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

// TunnelClient abstracts the native WireGuard / Magicsock client for injection in testing.
type TunnelClient interface {
	Dial(ctx context.Context, network, address string) (net.Conn, error)
	DialTCP(ctx context.Context, dst netip.AddrPort) (net.Conn, error)
	DialUDP(ctx context.Context, dst netip.AddrPort) (net.Conn, error)
	Close() error
	Status() *ipnstate.Status
	ServerNodeKey() key.NodePublic
	DERPMap() *tailcfg.DERPMap
}

// TunBridge manages bidirectional packet pumping between the Android TUN descriptor
// and the Tailcat data plane / exit node using a unified gVisor proxy stack.
type TunBridge struct {
	sessionID int64
	tunFD     int
	tunFile   *os.File
	client    TunnelClient
	token     *ParsedToken
	transport string
	rttMs     int64
	mtu       int
	tcpOnly   bool

	ctx    context.Context
	cancel context.CancelFunc

	// Protocol and drop metrics
	tcpPackets       atomic.Int64
	udpPackets       atomic.Int64
	dnsQueries       atomic.Int64
	malformedIP      atomic.Int64
	mtuExceeded      atomic.Int64
	queueExhaustion  atomic.Int64
	policyRejections atomic.Int64

	// TUN interface counters
	txBytes    atomic.Int64
	rxBytes    atomic.Int64
	lastTx     int64
	lastRx     int64
	lastTime   time.Time
	txRateKbps atomic.Int64
	rxRateKbps atomic.Int64

	rttMu       sync.Mutex
	rttSamples  []int64
	rttSampling atomic.Bool

	// Egress probe audit results
	egressIP        atomic.Value // string
	egressTimestamp atomic.Int64 // unix seconds
	egressErr       atomic.Value // string

	dnsConfig atomic.Pointer[DNSConfig]

	netstack   *netstackProxy
	tunWriteMu sync.Mutex

	closed atomic.Bool
	wg     sync.WaitGroup

	onPumpDead func(error)
	onHealth   func()
}

// DNSConfig defines the active DNS resolver policy and optional forced resolver destination.
type DNSConfig struct {
	Policy    string         // "PROFILE_RESOLVER" (default) or "FORCED_RESOLVER"
	ForcedDNS netip.AddrPort // non-zero if Policy is FORCED_RESOLVER
}

// SetDNSConfig updates the active DNS policy and forced destination atomically.
func (b *TunBridge) SetDNSConfig(cfg DNSConfig) {
	b.dnsConfig.Store(&cfg)
}

// GetDNSConfig returns the current active DNS configuration, or nil if unset.
func (b *TunBridge) GetDNSConfig() *DNSConfig {
	return b.dnsConfig.Load()
}

// newTunBridge creates a new packet bridge using a duplicated TUN file descriptor.
func newTunBridge(
	tunFD int,
	client TunnelClient,
	token *ParsedToken,
	transport string,
	rttMs int64,
	sessionID int64,
	parentCtx context.Context,
) (*TunBridge, error) {
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

	if parentCtx == nil {
		parentCtx = context.Background()
	}
	ctx, cancel := context.WithCancel(parentCtx)

	b := &TunBridge{
		sessionID: sessionID,
		tunFD:     dupFD,
		tunFile:   tunFile,
		client:    client,
		token:     token,
		transport: transport,
		rttMs:     rttMs,
		mtu:       1280,
		ctx:       ctx,
		cancel:    cancel,
		lastTime:  time.Now(),
	}
	if rttMs > 0 {
		b.rttSamples = []int64{rttMs}
	}

	netstack, err := newNetstackProxy(b)
	if err != nil {
		_ = tunFile.Close()
		cancel()
		return nil, fmt.Errorf("create gVisor netstack proxy: %w", err)
	}
	b.netstack = netstack

	return b, nil
}

func signalReady(ch chan struct{}) {
	if ch == nil {
		return
	}
	select {
	case <-ch:
	default:
		close(ch)
	}
}

func (b *TunBridge) reportPumpDead(err error) {
	if err == nil || b.closed.Load() || b.ctx.Err() != nil {
		return
	}
	if b.onPumpDead != nil {
		b.onPumpDead(err)
	}
}

// Start launches the background packet pumps and returns after required loops are entered.
func (b *TunBridge) Start() error {
	tunReady := make(chan struct{})
	gvisorReady := make(chan struct{})
	udpReady := make(chan struct{})
	healthReady := make(chan struct{})

	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		b.reportPumpDead(b.readLoop(tunReady))
	}()

	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		b.reportPumpDead(b.netstack.writeLoop(gvisorReady))
	}()

	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		b.netstack.cleanupIdleUDPFlows(udpReady)
	}()

	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		b.rateCalcLoop(healthReady)
	}()

	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		b.egressProbeLoop()
	}()

	timer := time.NewTimer(2 * time.Second)
	defer timer.Stop()
	for _, ch := range []chan struct{}{tunReady, gvisorReady, udpReady, healthReady} {
		select {
		case <-ch:
		case <-b.ctx.Done():
			return b.ctx.Err()
		case <-timer.C:
			return errors.New("timeout starting packet bridge pumps")
		}
	}
	return nil
}

// Stop terminates packet pumps and closes open descriptors.
func (b *TunBridge) Stop() error {
	if b.closed.Swap(true) {
		return nil
	}

	b.cancel()
	if b.netstack != nil {
		b.netstack.Close()
	}
	if b.tunFile != nil {
		b.tunFile.Close()
	}

	done := make(chan struct{})
	go func() {
		b.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
		return nil
	case <-time.After(stopWaitTimeout):
		return errors.New("timeout waiting for packet pumps to stop")
	}
}

func (b *TunBridge) readLoop(ready chan struct{}) error {
	buf := make([]byte, 65535)
	signalReady(ready)
	for {
		select {
		case <-b.ctx.Done():
			return nil
		default:
		}

		n, err := b.tunFile.Read(buf)
		if err != nil {
			if b.closed.Load() || b.ctx.Err() != nil {
				return nil
			}
			if errors.Is(err, io.EOF) || errors.Is(err, os.ErrClosed) {
				return err
			}
			return err
		}

		if n <= 0 {
			continue
		}

		pkt := make([]byte, n)
		copy(pkt, buf[:n])
		b.txBytes.Add(int64(n))

		b.handleOutboundPacket(pkt)
	}
}

func (b *TunBridge) handleOutboundPacket(pkt []byte) {
	if len(pkt) < 20 {
		b.malformedIP.Add(1)
		return
	}

	version := pkt[0] >> 4
	if b.mtu > 0 && len(pkt) > b.mtu {
		b.mtuExceeded.Add(1)
		if version == 6 {
			b.writeICMPv6PacketTooBig(pkt)
		}
		return
	}

	switch version {
	case 4:
		b.handleIPv4(pkt)
	case 6:
		b.handleIPv6(pkt)
	default:
		b.malformedIP.Add(1)
	}
}

func (b *TunBridge) handleIPv4(pkt []byte) {
	ihl := int(pkt[0]&0x0f) * 4
	if len(pkt) < ihl || ihl < 20 {
		b.malformedIP.Add(1)
		return
	}

	protocol := pkt[9]
	srcIP, _ := netip.AddrFromSlice(pkt[12:16])
	dstIP, _ := netip.AddrFromSlice(pkt[16:20])

	switch protocol {
	case 1: // ICMP
		b.handleICMPv4(pkt, ihl, srcIP, dstIP)
	case 6: // TCP
		b.tcpPackets.Add(1)
		if len(pkt) >= ihl+4 {
			dstPort := binary.BigEndian.Uint16(pkt[ihl+2 : ihl+4])
			if dstPort == 53 {
				b.dnsQueries.Add(1)
			}
		}
		b.netstack.inject(pkt, false)
	case 17: // UDP
		b.udpPackets.Add(1)
		if len(pkt) >= ihl+4 {
			dstPort := binary.BigEndian.Uint16(pkt[ihl+2 : ihl+4])
			if dstPort == 53 {
				b.dnsQueries.Add(1)
			}
		}
		b.netstack.inject(pkt, false)
	default:
		b.netstack.inject(pkt, false)
	}
}

func (b *TunBridge) handleIPv6(pkt []byte) {
	if len(pkt) < 40 {
		b.malformedIP.Add(1)
		return
	}
	nextHeader := pkt[6]
	switch nextHeader {
	case 58: // ICMPv6
		b.policyRejections.Add(1)
		return
	case 6:
		b.tcpPackets.Add(1)
		if len(pkt) >= 44 {
			dstPort := binary.BigEndian.Uint16(pkt[42:44])
			if dstPort == 53 {
				b.dnsQueries.Add(1)
			}
		}
		b.netstack.inject(pkt, true)
	case 17:
		b.udpPackets.Add(1)
		if len(pkt) >= 44 {
			dstPort := binary.BigEndian.Uint16(pkt[42:44])
			if dstPort == 53 {
				b.dnsQueries.Add(1)
			}
		}
		b.netstack.inject(pkt, true)
	default:
		b.netstack.inject(pkt, true)
	}
}

func (b *TunBridge) writeICMPv6PacketTooBig(pkt []byte) {
	if len(pkt) < 40 {
		return
	}
	if pkt[6] == 58 && len(pkt) >= 41 && pkt[40] < 128 {
		return
	}
	mtu := b.mtu
	if mtu <= 0 {
		mtu = 1280
	}
	maxBody := mtu - 48
	if maxBody < 40 {
		return
	}
	invoking := pkt
	if len(invoking) > maxBody {
		invoking = pkt[:maxBody]
	}
	reply := make([]byte, 40+8+len(invoking))
	reply[0] = 0x60
	binary.BigEndian.PutUint16(reply[4:6], uint16(8+len(invoking)))
	reply[6] = 58
	reply[7] = 64
	copy(reply[8:24], pkt[24:40])
	copy(reply[24:40], pkt[8:24])
	reply[40] = 2
	binary.BigEndian.PutUint32(reply[44:48], uint32(mtu))
	copy(reply[48:], invoking)
	pseudo := make([]byte, 40)
	copy(pseudo[0:16], reply[8:24])
	copy(pseudo[16:32], reply[24:40])
	binary.BigEndian.PutUint32(pseudo[32:36], uint32(8+len(invoking)))
	pseudo[39] = 58
	chk := checksum(pseudo, reply[40:])
	binary.BigEndian.PutUint16(reply[42:44], chk)
	b.rxBytes.Add(int64(len(reply)))
	_ = b.writeTunPacket(reply)
}

func (b *TunBridge) writeTunPacket(pkt []byte) error {
	b.tunWriteMu.Lock()
	defer b.tunWriteMu.Unlock()
	_, err := b.tunFile.Write(pkt)
	return err
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
	b.writeTunPacket(reply)
}

const (
	rttSampleInterval = 5 * time.Second
	rttSampleTimeout  = 2 * time.Second
)

type discoPinger interface {
	DiscoPing(context.Context) (*ipnstate.PingResult, error)
}

func (b *TunBridge) rateCalcLoop(ready chan struct{}) {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	signalReady(ready)
	if b.onHealth != nil {
		b.onHealth()
	}
	var lastRTTSample time.Time

	for {
		select {
		case <-b.ctx.Done():
			return
		case t := <-ticker.C:
			if b.onHealth != nil {
				b.onHealth()
			}
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

			if lastRTTSample.IsZero() || t.Sub(lastRTTSample) >= rttSampleInterval {
				if b.rttSampling.CompareAndSwap(false, true) {
					lastRTTSample = t
					go func() {
						defer b.rttSampling.Store(false)
						b.sampleLiveRTT()
					}()
				}
			}
		}
	}
}

func (b *TunBridge) sampleLiveRTT() {
	if b.client == nil {
		return
	}
	sampler, ok := b.client.(discoPinger)
	if !ok {
		return
	}
	ctx, cancel := context.WithTimeout(b.ctx, rttSampleTimeout)
	defer cancel()
	res, err := sampler.DiscoPing(ctx)
	if err != nil || res == nil || res.LatencySeconds <= 0 {
		return
	}
	b.RecordRTT(int64(res.LatencySeconds * 1000))
}

// GetStats returns current measured metrics from the live bridge and client.
// RecordRTT appends an RTT sample and maintains jitter statistics.
func (b *TunBridge) RecordRTT(rtt int64) {
	if rtt <= 0 {
		return
	}
	b.rttMu.Lock()
	defer b.rttMu.Unlock()
	b.rttMs = rtt
	b.rttSamples = append(b.rttSamples, rtt)
	if len(b.rttSamples) > 50 {
		b.rttSamples = b.rttSamples[len(b.rttSamples)-50:]
	}
}

func (b *TunBridge) currentRTTMs() int64 {
	b.rttMu.Lock()
	defer b.rttMu.Unlock()
	return b.rttMs
}

func (b *TunBridge) currentJitterMs() *int64 {
	b.rttMu.Lock()
	defer b.rttMu.Unlock()

	if len(b.rttSamples) < 3 {
		return nil
	}

	var sumDiff int64
	for i := 1; i < len(b.rttSamples); i++ {
		diff := b.rttSamples[i] - b.rttSamples[i-1]
		if diff < 0 {
			diff = -diff
		}
		sumDiff += diff
	}
	jitter := sumDiff / int64(len(b.rttSamples)-1)
	return &jitter
}

func (b *TunBridge) resolveRegionName(id int) string {
	if b.client == nil {
		return ""
	}
	dm := b.client.DERPMap()
	if dm == nil || len(dm.Regions) == 0 {
		return ""
	}
	reg, ok := dm.Regions[tailcfg.DERPRegionID(id)]
	if !ok || reg == nil {
		return ""
	}
	return reg.RegionName
}

// GetStats returns authoritative telemetry from the live bridge, netstack, and WireGuard engine.
func (b *TunBridge) GetStats() EngineStats {
	if b.closed.Load() {
		return EngineStats{Version: 2, SessionID: b.sessionID, State: "STOPPING", Transport: "DISCONNECTED"}
	}
	regionID := 0
	if b.token != nil {
		regionID = int(b.token.RegionID)
	}
	egressIP, _ := b.egressIP.Load().(string)
	egressErr, _ := b.egressErr.Load().(string)

	stats := EngineStats{
		Version:          2,
		SessionID:        b.sessionID,
		State:            "RUNNING",
		Transport:        b.transport,
		DerpRegionID:     regionID,
		DerpRegionName:   b.resolveRegionName(regionID),
		TunnelEgressIP:   egressIP,
		EgressAuditError: egressErr,
		TunTxBytes:       b.txBytes.Load(),
		TunRxBytes:       b.rxBytes.Load(),
		TxRateKbps:       b.txRateKbps.Load(),
		RxRateKbps:       b.rxRateKbps.Load(),
		TCPPackets:       b.tcpPackets.Load(),
		UDPPackets:       b.udpPackets.Load(),
		DNSQueries:       b.dnsQueries.Load(),
		DropCounters: DropCounters{
			MalformedIP:      b.malformedIP.Load(),
			MTUExceeded:      b.mtuExceeded.Load(),
			QueueExhaustion:  b.queueExhaustion.Load(),
			PolicyRejections: b.policyRejections.Load(),
		},
	}

	if b.egressTimestamp.Load() > 0 {
		stats.EgressAuditTimestampSec = b.egressTimestamp.Load()
	}

	// Authoritative WireGuard / Magicsock metrics from client status
	if b.client != nil {
		if st := b.client.Status(); st != nil {
			nodeKey := b.client.ServerNodeKey()
			if peer, ok := st.Peer[nodeKey]; ok && peer != nil {
				stats.WireguardTxBytes = peer.TxBytes
				stats.WireguardRxBytes = peer.RxBytes
				if !peer.LastHandshake.IsZero() {
					stats.LastHandshakeSec = peer.LastHandshake.Unix()
				}
				if peer.CurAddr != "" {
					stats.Transport = "DIRECT_P2P"
					stats.DirectEndpoint = peer.CurAddr
				} else if peer.Relay != "" {
					stats.Transport = "DERP_RELAY"
					stats.DerpRegionCode = peer.Relay
				}
			}
		}

		if dm := b.client.DERPMap(); dm != nil && len(dm.Regions) > 0 {
			if reg, ok := dm.Regions[tailcfg.DERPRegionID(regionID)]; ok && reg != nil {
				stats.DerpRegionName = reg.RegionName
				stats.DerpRegionCode = reg.RegionCode
			}
		}
	}

	stats.TxBytes = stats.WireguardTxBytes
	stats.RxBytes = stats.WireguardRxBytes

	stats.RTTMs = b.currentRTTMs()
	stats.JitterMs = b.currentJitterMs()

	return stats
}

func buildIPv4UDPPacket(srcAP, dstAP netip.AddrPort, payload []byte) []byte {
	totalLen := 20 + 8 + len(payload)
	pkt := make([]byte, totalLen)

	// IPv4 Header
	pkt[0] = 0x45 // Version 4, IHL 5 (20 bytes)
	pkt[1] = 0x00 // DSCP / ECN
	binary.BigEndian.PutUint16(pkt[2:4], uint16(totalLen))
	binary.BigEndian.PutUint16(pkt[4:6], 0x1234) // Identification
	pkt[6] = 0x40                                // Don't fragment
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
