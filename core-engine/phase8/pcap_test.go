package phase8

import (
	"bytes"
	"encoding/binary"
	"net/netip"
	"strings"
	"testing"
	"time"
)

var (
	device = netip.MustParseAddr("10.0.0.2")
	peer   = netip.MustParseAddr("203.0.113.9")
	gwTun  = netip.MustParseAddr("172.16.0.2")
	probe  = netip.MustParseAddr("1.1.1.1")
	t0     = time.Unix(1_790_000_000, 0)
)

type record struct {
	at    time.Time
	frame []byte
}

// ipv4 returns an IPv4 packet with a TCP (proto 6) or UDP header carrying
// the ports, or a bare header for other protocols.
func ipv4(src, dst netip.Addr, proto uint8, sport, dport uint16) []byte {
	l4 := 0
	switch proto {
	case 6:
		l4 = 20
	case 17:
		l4 = 8
	}
	ip := make([]byte, 20+l4)
	ip[0] = 0x45
	binary.BigEndian.PutUint16(ip[2:4], uint16(len(ip)))
	ip[8] = 64
	ip[9] = proto
	copy(ip[12:16], src.AsSlice())
	copy(ip[16:20], dst.AsSlice())
	if l4 > 0 {
		binary.BigEndian.PutUint16(ip[20:22], sport)
		binary.BigEndian.PutUint16(ip[22:24], dport)
	}
	return ip
}

func ether(ip []byte) []byte {
	eth := make([]byte, 14+len(ip))
	binary.BigEndian.PutUint16(eth[12:14], 0x0800)
	if ip[0]>>4 == 6 {
		binary.BigEndian.PutUint16(eth[12:14], 0x86dd)
	}
	copy(eth[14:], ip)
	return eth
}

func pcapFile(magic, linkType uint32, recs ...record) []byte {
	var buf bytes.Buffer
	var hdr [24]byte
	binary.LittleEndian.PutUint32(hdr[0:4], magic)
	binary.LittleEndian.PutUint16(hdr[4:6], 2)
	binary.LittleEndian.PutUint16(hdr[6:8], 4)
	binary.LittleEndian.PutUint32(hdr[16:20], 65535)
	binary.LittleEndian.PutUint32(hdr[20:24], linkType)
	buf.Write(hdr[:])
	for _, r := range recs {
		var ph [16]byte
		frac := r.at.Nanosecond() / 1000
		if magic == pcapMagicNanoseconds {
			frac = r.at.Nanosecond()
		}
		binary.LittleEndian.PutUint32(ph[0:4], uint32(r.at.Unix()))
		binary.LittleEndian.PutUint32(ph[4:8], uint32(frac))
		binary.LittleEndian.PutUint32(ph[8:12], uint32(len(r.frame)))
		binary.LittleEndian.PutUint32(ph[12:16], uint32(len(r.frame)))
		buf.Write(ph[:])
		buf.Write(r.frame)
	}
	return buf.Bytes()
}

func ethPcap(recs ...record) []byte {
	return pcapFile(pcapMagicMicroseconds, dltEN10MB, recs...)
}

func at(sec int) time.Time { return t0.Add(time.Duration(sec) * time.Second) }

// tunnelUplink is the phone's WireGuard traffic to the peer, one packet a
// second for ten seconds, plus extra.
func tunnelUplink(extra ...record) []record {
	var recs []record
	for i := 0; i < 10; i++ {
		recs = append(recs, record{at(i), ether(ipv4(device, peer, 17, 41641, 41641))})
	}
	return append(recs, extra...)
}

// probeGateway is the decrypted probe traffic leaving the gateway, plus extra.
func probeGateway(extra ...record) []record {
	var recs []record
	for i := 2; i <= 6; i++ {
		recs = append(recs, record{at(i), ether(ipv4(gwTun, probe, 6, 40000, 443))})
	}
	return append(recs, extra...)
}

func config() Config {
	return Config{
		Probes:           []netip.Addr{probe},
		DeviceIPs:        []netip.Addr{device},
		TunnelPeers:      []netip.Addr{peer},
		MinTunnelPackets: 5,
		MaxClockSkew:     2 * time.Second,
	}
}

func analyze(t *testing.T, uplink, gateway []byte, cfg Config) *Result {
	t.Helper()
	res, err := Analyze(bytes.NewReader(uplink), bytes.NewReader(gateway), cfg)
	if err != nil {
		t.Fatalf("Analyze: %v", err)
	}
	return res
}

func TestAnalyzePass(t *testing.T) {
	// The gateway resolver's own DNS to the probe is ignored.
	gw := probeGateway(record{at(3), ether(ipv4(gwTun, probe, 17, 5353, 53))})
	res := analyze(t, ethPcap(tunnelUplink()...), ethPcap(gw...), config())
	if res.Verdict != Pass {
		t.Fatalf("verdict %s (%s), want PASS: %+v", res.Verdict, res.Reason, res)
	}
	if res.TunnelPackets != 9 {
		t.Fatalf("tunnel packets in window = %d, want 9 (seconds 0..8)", res.TunnelPackets)
	}
}

func TestAnalyzeProbeLeakFails(t *testing.T) {
	for _, port := range []uint16{443, 53} {
		up := tunnelUplink(record{at(3), ether(ipv4(device, probe, 6, 50000, port))})
		res := analyze(t, ethPcap(up...), ethPcap(probeGateway()...), config())
		if res.Verdict != Fail || len(res.ProbeLeaks) != 1 || res.ProbeLeaks[0] != probe {
			t.Fatalf("port %d: verdict %s leaks %v, want FAIL with %v", port, res.Verdict, res.ProbeLeaks, probe)
		}
	}
}

func TestAnalyzePlaintextDNSOutsideTunnelFails(t *testing.T) {
	lan := netip.MustParseAddr("192.168.2.1")
	up := tunnelUplink(record{at(4), ether(ipv4(device, lan, 17, 5353, 53))})
	res := analyze(t, ethPcap(up...), ethPcap(probeGateway()...), config())
	if res.Verdict != Fail || len(res.DNSLeaks) != 1 || res.DNSLeaks[0] != lan {
		t.Fatalf("verdict %s DNS leaks %v, want FAIL with %v", res.Verdict, res.DNSLeaks, lan)
	}
	// A reply seen without its query is a leak too.
	up = tunnelUplink(record{at(4), ether(ipv4(lan, device, 17, 53, 5353))})
	if res := analyze(t, ethPcap(up...), ethPcap(probeGateway()...), config()); res.Verdict != Fail {
		t.Fatalf("DNS reply outside the tunnel: verdict %s, want FAIL", res.Verdict)
	}
	// DNS to the tunnel peer itself did not leave the tunnel path.
	up = tunnelUplink(record{at(4), ether(ipv4(device, peer, 17, 5353, 53))})
	if res := analyze(t, ethPcap(up...), ethPcap(probeGateway()...), config()); res.Verdict != Pass {
		t.Fatalf("DNS to the tunnel peer: verdict %s, want PASS", res.Verdict)
	}
}

func TestAnalyzeAllowedResolverIsReportedNotFailed(t *testing.T) {
	lan := netip.MustParseAddr("192.168.2.1")
	other := netip.MustParseAddr("192.168.2.53")
	cfg := config()
	cfg.AllowedDNS = []netip.Addr{lan}
	up := tunnelUplink(
		record{at(4), ether(ipv4(device, lan, 17, 5353, 53))},
		record{at(4), ether(ipv4(lan, device, 17, 53, 5353))},
	)
	res := analyze(t, ethPcap(up...), ethPcap(probeGateway()...), cfg)
	if res.Verdict != Pass || len(res.AllowedDNSSeen) != 1 || res.AllowedDNSSeen[0] != lan {
		t.Fatalf("verdict %s allowed %v leaks %v, want PASS reporting %v", res.Verdict, res.AllowedDNSSeen, res.DNSLeaks, lan)
	}
	// Only the listed resolver is allowed.
	up = tunnelUplink(record{at(4), ether(ipv4(device, other, 17, 5353, 53))})
	res = analyze(t, ethPcap(up...), ethPcap(probeGateway()...), cfg)
	if res.Verdict != Fail || len(res.DNSLeaks) != 1 || res.DNSLeaks[0] != other {
		t.Fatalf("unlisted resolver: verdict %s leaks %v, want FAIL with %v", res.Verdict, res.DNSLeaks, other)
	}
}

func TestAnalyzeWithoutDeviceTunnelTrafficIsInconclusive(t *testing.T) {
	// An idle or wrong interface: only unrelated hosts, never the phone.
	var up []record
	for i := 0; i < 10; i++ {
		up = append(up, record{at(i), ether(ipv4(netip.MustParseAddr("10.0.0.7"), netip.MustParseAddr("198.51.100.20"), 6, 50000, 443))})
	}
	res := analyze(t, ethPcap(up...), ethPcap(probeGateway()...), config())
	if res.Verdict != Inconclusive || res.TunnelPackets != 0 {
		t.Fatalf("verdict %s tunnel %d, want INCONCLUSIVE with 0", res.Verdict, res.TunnelPackets)
	}
}

func TestAnalyzeTunnelTrafficOutsideProbeWindowIsInconclusive(t *testing.T) {
	// The phone's tunnel traffic stops before the probes reach the gateway,
	// though the capture keeps running.
	var up []record
	for i := 0; i < 10; i++ {
		up = append(up, record{at(i - 30), ether(ipv4(device, peer, 17, 41641, 41641))})
	}
	up = append(up, record{at(20), ether(ipv4(device, netip.MustParseAddr("192.168.2.1"), 6, 50000, 443))})
	res := analyze(t, ethPcap(up...), ethPcap(probeGateway()...), config())
	if res.Verdict != Inconclusive {
		t.Fatalf("verdict %s, want INCONCLUSIVE", res.Verdict)
	}
}

func TestAnalyzeCapturesWithoutOverlapAreInconclusive(t *testing.T) {
	var gw []record
	for _, r := range probeGateway() {
		gw = append(gw, record{r.at.Add(time.Hour), r.frame})
	}
	res := analyze(t, ethPcap(tunnelUplink()...), ethPcap(gw...), config())
	if res.Verdict != Inconclusive || !strings.Contains(res.Reason, "does not cover") {
		t.Fatalf("verdict %s (%s), want INCONCLUSIVE for a stale gateway capture", res.Verdict, res.Reason)
	}
}

func TestAnalyzeGatewayDNSOnlyIsMissing(t *testing.T) {
	gw := []record{{at(3), ether(ipv4(gwTun, probe, 17, 5353, 53))}}
	res := analyze(t, ethPcap(tunnelUplink()...), ethPcap(gw...), config())
	if res.Verdict != Fail || len(res.MissingOnGateway) != 1 {
		t.Fatalf("verdict %s missing %v, want FAIL with the probe missing", res.Verdict, res.MissingOnGateway)
	}
}

func TestAnalyzeGatewaySourceFilter(t *testing.T) {
	cfg := config()
	cfg.GatewaySources = []netip.Addr{netip.MustParseAddr("172.16.0.99")}
	res := analyze(t, ethPcap(tunnelUplink()...), ethPcap(probeGateway()...), cfg)
	if res.Verdict != Fail || len(res.MissingOnGateway) != 1 {
		t.Fatalf("verdict %s missing %v, want the probe missing for another source", res.Verdict, res.MissingOnGateway)
	}
	cfg.GatewaySources = []netip.Addr{gwTun}
	if res := analyze(t, ethPcap(tunnelUplink()...), ethPcap(probeGateway()...), cfg); res.Verdict != Pass {
		t.Fatalf("verdict %s, want PASS with the matching source", res.Verdict)
	}
}

func TestAnalyzeRequiresPositiveControlInputs(t *testing.T) {
	up, gw := ethPcap(tunnelUplink()...), ethPcap(probeGateway()...)
	for name, mutate := range map[string]func(*Config){
		"probes": func(c *Config) { c.Probes = nil },
		"device": func(c *Config) { c.DeviceIPs = nil },
		"tunnel": func(c *Config) { c.TunnelPeers = nil },
	} {
		cfg := config()
		mutate(&cfg)
		if _, err := Analyze(bytes.NewReader(up), bytes.NewReader(gw), cfg); err == nil {
			t.Fatalf("missing %s must be an error", name)
		}
	}
}

func TestReadCaptureKeepsTimestampsAndPorts(t *testing.T) {
	when := t0.Add(1500 * time.Millisecond)
	frame := ether(ipv4(device, probe, 17, 5353, 53))
	for _, magic := range []uint32{pcapMagicMicroseconds, pcapMagicNanoseconds} {
		c, err := ReadCapture(bytes.NewReader(pcapFile(magic, dltEN10MB, record{when, frame})))
		if err != nil {
			t.Fatal(err)
		}
		p := c.Packets[0]
		if !p.Time.Equal(when) || p.Src != device || p.Dst != probe || p.Proto != 17 || p.SrcPort != 5353 || p.DstPort != 53 || !p.IsDNS() {
			t.Fatalf("magic %08x: decoded %+v", magic, p)
		}
	}
}

func TestReadCaptureIPv6ExtensionHeaders(t *testing.T) {
	src, dst := netip.MustParseAddr("fd00::2"), netip.MustParseAddr("2606:4700:4700::1111")
	ip := make([]byte, 40+8+8)
	ip[0] = 0x60
	binary.BigEndian.PutUint16(ip[4:6], 16)
	ip[6] = 0 // hop-by-hop
	ip[7] = 64
	copy(ip[8:24], src.AsSlice())
	copy(ip[24:40], dst.AsSlice())
	ip[40] = 17 // next: UDP, length 0 => 8 bytes
	binary.BigEndian.PutUint16(ip[48:50], 5353)
	binary.BigEndian.PutUint16(ip[50:52], 53)
	c, err := ReadCapture(bytes.NewReader(ethPcap(record{t0, ether(ip)})))
	if err != nil {
		t.Fatal(err)
	}
	if p := c.Packets[0]; p.Dst != dst || p.Proto != 17 || p.DstPort != 53 {
		t.Fatalf("decoded %+v, want UDP/53 to %v", p, dst)
	}
}

func TestReadCaptureRejectsEmptyCapture(t *testing.T) {
	if _, err := ReadCapture(bytes.NewReader(ethPcap())); err == nil {
		t.Fatal("expected empty capture to fail")
	}
}

func TestReadCaptureRejectsUnsupportedLinkType(t *testing.T) {
	// DLT 999 is not supported; treating it as raw IP could PASS a leak.
	pcap := pcapFile(pcapMagicMicroseconds, 999, record{t0, ether(ipv4(device, probe, 17, 1, 2))})
	if _, err := ReadCapture(bytes.NewReader(pcap)); err == nil {
		t.Fatal("expected unsupported link type to fail")
	}
}

func TestReadCaptureRejectsPcapng(t *testing.T) {
	pcap := pcapFile(0x0a0d0d0a, dltEN10MB, record{t0, ether(ipv4(device, probe, 17, 1, 2))})
	if _, err := ReadCapture(bytes.NewReader(pcap)); err == nil {
		t.Fatal("expected pcapng magic to fail")
	}
}

func TestReadCaptureLinuxSLL2(t *testing.T) {
	ip := ipv4(device, probe, 6, 50000, 443)
	// SLL2 is a fixed 20-byte header; addr_len is often 0 on tcpdump -i any.
	for _, addrLen := range []byte{0, 8} {
		sll2 := make([]byte, 20+len(ip))
		binary.BigEndian.PutUint16(sll2[0:2], 0x0800)
		sll2[11] = addrLen
		copy(sll2[20:], ip)
		c, err := ReadCapture(bytes.NewReader(pcapFile(pcapMagicMicroseconds, dltLINUXSLL2, record{t0, sll2})))
		if err != nil {
			t.Fatalf("addrLen=%d: %v", addrLen, err)
		}
		if p := c.Packets[0]; p.Dst != probe || p.DstPort != 443 {
			t.Fatalf("addrLen=%d: decoded %+v", addrLen, p)
		}
	}
}
