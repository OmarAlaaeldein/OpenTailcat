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
	"os"
	"reflect"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"tailscale.com/ipn/ipnstate"
)

// TunnelClient abstracts the native WireGuard / Magicsock client for injection in testing.
type TunnelClient interface {
	DialTCP(ctx context.Context, dst netip.AddrPort) (net.Conn, error)
	DialUDP(ctx context.Context, dst netip.AddrPort) (net.Conn, error)
	Close() error
}

// TunBridge manages bidirectional packet pumping between the Android TUN descriptor
// and the Tailcat data plane / exit node using a unified gVisor proxy stack.
type TunBridge struct {
	sessionID int64
	tunFile   *os.File
	client    TunnelClient
	token     *ParsedToken
	transport string
	rttMs     int64
	mtu        int
	tcpOnly    atomic.Bool
	ipv6Egress atomic.Bool

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
	pingFails   atomic.Int32

	// Egress probe audit results
	egressIP        atomic.Value // string
	egressTimestamp atomic.Int64 // unix seconds
	egressErr       atomic.Value // string

	dnsConfig atomic.Pointer[DNSConfig]

	netstack   *netstackProxy
	tunWriteMu sync.Mutex

	closed atomic.Bool
	wg     sync.WaitGroup

	pumpDeadMu    sync.Mutex
	onPumpDead    func(error)
	onHealth      func()
	startupFailed atomic.Bool
	discoFresh    atomic.Bool // true after a successful live DiscoPing
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

// defaultTunnelMTU is the fail-closed TUN/netstack MTU when Android does not
// supply a profile MTU. 1280 is the IPv6 minimum and the safe mobile default.
const (
	defaultTunnelMTU = 1280
	minTunnelMTU     = 1280
	maxTunnelMTU     = 1500
)

// newTunBridge creates a new packet bridge using a duplicated TUN file descriptor.
// mtu <= 0 selects defaultTunnelMTU.
func newTunBridge(
	tunFD int,
	client TunnelClient,
	token *ParsedToken,
	transport string,
	rttMs int64,
	sessionID int64,
	parentCtx context.Context,
	mtu int,
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

	if mtu < minTunnelMTU {
		mtu = defaultTunnelMTU
	}
	if mtu > maxTunnelMTU {
		mtu = maxTunnelMTU
	}

	b := &TunBridge{
		sessionID: sessionID,
		tunFile:   tunFile,
		client:    client,
		token:     token,
		transport: transport,
		rttMs:     rttMs,
		mtu:       mtu,
		ctx:       ctx,
		cancel:    cancel,
		lastTime:  time.Now(),
	}
	if rttMs > 0 {
		b.rttSamples = []int64{rttMs}
		// prepare already completed a live DiscoPing; seed freshness so the
		// first stats sample does not report a false discoStale/DEGRADED flap.
		b.discoFresh.Store(true)
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

func (b *TunBridge) setOnPumpDead(fn func(error)) {
	b.pumpDeadMu.Lock()
	b.onPumpDead = fn
	b.pumpDeadMu.Unlock()
}

func (b *TunBridge) reportPumpDead(err error) {
	if b == nil || err == nil || b.closed.Load() {
		return
	}
	if b.ctx != nil && b.ctx.Err() != nil {
		return
	}
	b.startupFailed.Store(true)
	b.discoFresh.Store(false)
	b.pumpDeadMu.Lock()
	fn := b.onPumpDead
	b.pumpDeadMu.Unlock()
	if fn != nil {
		fn(err)
	}
}

// recoverPump converts a pump-loop panic into a fail-closed FAILED signal via
// the existing reportPumpDead path instead of aborting the process.
func (b *TunBridge) recoverPump(name string) {
	if r := recover(); r != nil {
		err := fmt.Errorf("%s pump panic: %v @ %s", name, r, panicSite())
		log.Printf("Tailcat %s", err.Error())
		if b == nil {
			return
		}
		b.reportPumpDead(err)
	}
}

// recoverPumpLogOnly contains a panic in a non-required loop (egress audit,
// UDP re-probe) without marking the session FAILED.
func (b *TunBridge) recoverPumpLogOnly(name string) {
	if r := recover(); r != nil {
		log.Printf("Tailcat %s pump panic (contained, session kept): %v @ %s", name, r, panicSite())
	}
}

// panicSite names the innermost non-runtime function active when a panic is
// recovered, so contained pump/flow reports point at the faulting code instead
// of runtime.panicmem / gopanic frames. Anonymous defer wrappers (.funcN) are
// skipped when a named caller is available.
func panicSite() string {
	var pcs [16]uintptr
	n := runtime.Callers(3, pcs[:])
	if n == 0 {
		return "unknown"
	}
	frames := runtime.CallersFrames(pcs[:n])
	fallback := ""
	for {
		frame, more := frames.Next()
		fn := frame.Function
		if fn == "" {
			if !more {
				break
			}
			continue
		}
		base := fn
		if i := strings.LastIndex(fn, "/"); i >= 0 {
			base = fn[i+1:]
		}
		// Match helper names exactly on the base (do not use Contains("panicSite"),
		// which would also skip panicSiteHelper).
		if strings.HasPrefix(fn, "runtime.") ||
			base == "panicSite" ||
			strings.HasSuffix(base, ".panicSite") ||
			strings.Contains(base, "recoverFlow") ||
			strings.Contains(base, "recoverPump") ||
			strings.Contains(base, "recoverLikeProduction") {
			if !more {
				break
			}
			continue
		}
		if strings.Contains(fn, ".func") {
			if fallback == "" {
				fallback = fn
			}
			if !more {
				break
			}
			continue
		}
		return fn
	}
	if fallback != "" {
		return fallback
	}
	return "unknown"
}

// closeConn closes c only when it is a usable (non-nil, non-typed-nil) Conn.
// A plain `c != nil` check is true for typed-nil interfaces and Close panics.
func closeConn(c net.Conn) {
	if !isNilConn(c) {
		_ = c.Close()
	}
}

// isNilConn reports whether a dialed connection is unusable: either a nil
// interface or a typed-nil pointer inside the interface. Upstream dials must
// return (nil, err) on failure, but a (nil, nil) result dereferences to a
// "tcp proxy panic: invalid memory address" on flow teardown, so every dial
// consumption site rejects it fail-closed before tracking or copying.
func isNilConn(c net.Conn) bool {
	if c == nil {
		return true
	}
	v := reflect.ValueOf(c)
	switch v.Kind() {
	case reflect.Chan, reflect.Func, reflect.Interface, reflect.Map, reflect.Ptr, reflect.Slice:
		return v.IsNil()
	default:
		return false
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
		defer b.recoverPump("tun read")
		b.reportPumpDead(b.readLoop(tunReady))
	}()

	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		defer b.recoverPump("gvisor output")
		b.reportPumpDead(b.netstack.writeLoop(gvisorReady))
	}()

	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		defer b.recoverPump("udp gc")
		b.netstack.cleanupIdleUDPFlows(udpReady)
	}()

	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		defer b.recoverPump("health/rate")
		b.rateCalcLoop(healthReady)
	}()

	b.wg.Add(1)
	go func() {
		defer b.wg.Done()
		defer b.recoverPumpLogOnly("egress probe")
		b.egressProbeLoop()
	}()

	timer := time.NewTimer(2 * time.Second)
	defer timer.Stop()
	for _, ch := range []chan struct{}{tunReady, gvisorReady, udpReady, healthReady} {
		select {
		case <-ch:
			if b.startupFailed.Load() {
				return errors.New("packet pump failed during startup")
			}
		case <-b.ctx.Done():
			return b.ctx.Err()
		case <-timer.C:
			return errors.New("timeout starting packet bridge pumps")
		}
	}
	// Give an immediately-EOF reader a chance to fail after signaling ready
	// before we publish RUNNING (AUDIT H3).
	time.Sleep(20 * time.Millisecond)
	if b.startupFailed.Load() || b.closed.Load() || b.ctx.Err() != nil {
		return errors.New("packet pump failed during startup")
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
	defer b.recoverPump("tun read")
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
			if errors.Is(err, syscall.EAGAIN) || errors.Is(err, syscall.EWOULDBLOCK) {
				time.Sleep(time.Millisecond)
				continue
			}
			if errors.Is(err, io.EOF) || errors.Is(err, os.ErrClosed) {
				return err
			}
			return err
		}

		if n <= 0 {
			continue
		}

		b.txBytes.Add(int64(n))
		b.handleOutboundPacket(buf[:n])
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
		} else if version == 4 {
			b.writeIPv4FragNeeded(pkt)
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
	nextHeader, l4off, ok := ipv6FinalNextHeader(pkt)
	if !ok {
		b.malformedIP.Add(1)
		return
	}
	switch nextHeader {
	case 58:
		b.policyRejections.Add(1)
		return
	case 6:
		b.tcpPackets.Add(1)
		if len(pkt) >= l4off+4 {
			dstPort := binary.BigEndian.Uint16(pkt[l4off+2 : l4off+4])
			if dstPort == 53 {
				b.dnsQueries.Add(1)
			}
		}
		b.netstack.inject(pkt, true)
	case 17:
		b.udpPackets.Add(1)
		if len(pkt) >= l4off+4 {
			dstPort := binary.BigEndian.Uint16(pkt[l4off+2 : l4off+4])
			if dstPort == 53 {
				b.dnsQueries.Add(1)
			}
		}
		b.netstack.inject(pkt, true)
	default:
		b.netstack.inject(pkt, true)
	}
}

func ipv6FinalNextHeader(pkt []byte) (byte, int, bool) {
	if len(pkt) < 40 {
		return 0, 0, false
	}
	nh := pkt[6]
	off := 40
	for i := 0; i < 8; i++ {
		switch nh {
		case 0, 43, 60:
			if len(pkt) < off+2 {
				return 0, 0, false
			}
			hdrLen := int(pkt[off+1]+1) * 8
			if hdrLen < 8 || len(pkt) < off+hdrLen {
				return 0, 0, false
			}
			nh = pkt[off]
			off += hdrLen
		case 44:
			if len(pkt) < off+8 {
				return 0, 0, false
			}
			nh = pkt[off]
			off += 8
		case 51:
			if len(pkt) < off+2 {
				return 0, 0, false
			}
			hdrLen := int(pkt[off+1]+2) * 4
			if hdrLen < 8 || len(pkt) < off+hdrLen {
				return 0, 0, false
			}
			nh = pkt[off]
			off += hdrLen
		default:
			return nh, off, true
		}
	}
	return 0, 0, false
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

func (b *TunBridge) writeIPv4FragNeeded(pkt []byte) {
	if len(pkt) < 20 {
		return
	}
	ihl := int(pkt[0]&0x0f) * 4
	if ihl < 20 || len(pkt) < ihl {
		return
	}
	if pkt[9] == 1 && len(pkt) >= ihl+1 && pkt[ihl] < 8 {
		return
	}
	mtu := b.mtu
	if mtu <= 0 {
		mtu = 1280
	}
	quoted := ihl + 8
	if quoted > len(pkt) {
		quoted = len(pkt)
	}
	total := 20 + 8 + quoted
	reply := make([]byte, total)
	reply[0] = 0x45
	binary.BigEndian.PutUint16(reply[2:4], uint16(total))
	reply[8] = 64
	reply[9] = 1
	copy(reply[12:16], pkt[16:20])
	copy(reply[16:20], pkt[12:16])
	binary.BigEndian.PutUint16(reply[10:12], ipv4Checksum(reply[:20]))
	reply[20] = 3
	reply[21] = 4
	binary.BigEndian.PutUint16(reply[26:28], uint16(mtu))
	copy(reply[28:], pkt[:quoted])
	icmpChk := checksum(reply[20:])
	binary.BigEndian.PutUint16(reply[22:24], icmpChk)
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
	// udpProbeTimeout bounds the gateway UDP capability check. The previous 1s
	// bound could latch tcpOnly on a slow DERP-relayed mobile path even though
	// gateway UDP works, dropping all non-DNS UDP for the whole session while
	// the TCP-only speedtest stayed green.
	udpProbeTimeout = 5 * time.Second
	// udpReprobeInterval clears a stale tcpOnly latch when gateway UDP
	// recovers. Re-probing only ever re-enables gateway-proxied UDP via
	// Client.DialUDP, never direct OS sockets.
	udpReprobeInterval = 30 * time.Second
)

type discoPinger interface {
	DiscoPing(context.Context) (*ipnstate.PingResult, error)
}

func (b *TunBridge) rateCalcLoop(ready chan struct{}) {
	defer b.recoverPump("health/rate")
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()
	signalReady(ready)
	if b.onHealth != nil {
		b.onHealth()
	}
	var lastRTTSample time.Time
	var lastUDPReprobe time.Time

	for {
		select {
		case <-b.ctx.Done():
			return
		case t := <-ticker.C:
			// Pumps-alive heartbeat: health tracks required-loop liveness, not
			// disco freshness. Health freshness still gates Kotlin CONNECTED;
			// disco staleness is exposed separately via discoStale so callers
			// can distinguish "pumps alive, RTT stale" from a dead data plane.
			if b.onHealth != nil && !b.closed.Load() && !b.startupFailed.Load() && b.ctx != nil && b.ctx.Err() == nil {
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
						defer b.recoverPump("rtt sample")
						defer b.rttSampling.Store(false)
						b.sampleGatewayPing()
						b.sampleLiveRTT()
					}()
				}
			}

			if b.tcpOnly.Load() &&
				(lastUDPReprobe.IsZero() || t.Sub(lastUDPReprobe) >= udpReprobeInterval) {
				lastUDPReprobe = t
				go b.reprobeUDP()
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
		// A failed DiscoPing must not kill a relayed tunnel: user traffic
		// can flow over DERP while disco probes fail. Keep the last known
		// transport, mark disco stale, and keep counting failures. Only a
		// real required-pump exit marks FAILED via reportPumpDead.
		b.discoFresh.Store(false)
		b.pingFails.Add(1)
		return
	}
	b.pingFails.Store(0)
	b.discoFresh.Store(true)
	if b.onHealth != nil {
		b.onHealth()
	}
	transport := "DERP_RELAY"
	if res.Endpoint != "" {
		transport = "DIRECT_P2P"
	}
	b.rttMu.Lock()
	b.transport = transport
	b.rttMu.Unlock()
	b.RecordRTT(int64(res.LatencySeconds * 1000))
}

// reprobeUDP clears a stale tcpOnly latch when gateway UDP recovers.
// It only re-enables gateway-proxied UDP via Client.DialUDP; a failed probe
// keeps tcpOnly and never touches direct OS sockets.
func (b *TunBridge) reprobeUDP() {
	defer b.recoverPumpLogOnly("udp reprobe")
	if b.client == nil {
		return
	}
	prober, ok := b.client.(udpCapability)
	if !ok {
		return
	}
	ctx, cancel := context.WithTimeout(b.ctx, udpProbeTimeout)
	defer cancel()
	if prober.SupportsUDP(ctx) {
		b.tcpOnly.Store(false)
	}
}

func (b *TunBridge) sampleGatewayPing() {
	// Client.Ping waits on a channel closed after the first Meowed reply.
	// Subsequent calls can succeed without a fresh gateway observation, so
	// this must not refresh health or clear ping failures (AUDIT H5).
	// Liveness is owned by sampleLiveRTT / DiscoPing.
	_ = b.client
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

	b.rttMu.Lock()
	transport := b.transport
	b.rttMu.Unlock()

	stats := EngineStats{
		Version:          2,
		SessionID:        b.sessionID,
		State:            "RUNNING",
		Transport:        transport,
		TcpOnly:          b.tcpOnly.Load(),
		Ipv6Egress:       b.ipv6Egress.Load(),
		DiscoStale:       !b.discoFresh.Load(),
		DerpRegionID:     regionID,
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

	if b.token != nil {
		for _, reg := range b.token.Region {
			if reg != nil && int(reg.RegionID) == regionID {
				stats.DerpRegionName = reg.RegionName
				stats.DerpRegionCode = reg.RegionCode
				break
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
