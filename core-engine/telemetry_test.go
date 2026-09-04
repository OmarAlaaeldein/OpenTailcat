package engine

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
)

func TestTelemetryVersionAndSessionID(t *testing.T) {
	bridge := &TunBridge{
		sessionID: 42,
		token:     &ParsedToken{RegionID: 1},
		transport: "DERP_RELAY",
	}

	stats := bridge.GetStats()
	if stats.Version != 2 {
		t.Fatalf("expected telemetry version 2, got %d", stats.Version)
	}
	if stats.SessionID != 42 {
		t.Fatalf("expected session ID 42, got %d", stats.SessionID)
	}
	if stats.State != "RUNNING" {
		t.Fatalf("expected RUNNING state, got %s", stats.State)
	}
}

func TestAuthoritativeWireGuardVsTUNCounters(t *testing.T) {
	bridge := &TunBridge{
		sessionID: 1,
		token:     &ParsedToken{RegionID: 1},
		transport: "DERP_RELAY",
	}
	bridge.txBytes.Store(12000)
	bridge.rxBytes.Store(18000)

	stats := bridge.GetStats()
	if stats.TunTxBytes != 12000 || stats.TunRxBytes != 18000 {
		t.Fatalf("TUN counters mismatch: tx=%d rx=%d (expected 12000/18000)", stats.TunTxBytes, stats.TunRxBytes)
	}
	if stats.TxBytes != 0 || stats.RxBytes != 0 {
		t.Fatalf("TxBytes/RxBytes must not fall back to TUN: tx=%d rx=%d", stats.TxBytes, stats.RxBytes)
	}
	if stats.WireguardTxBytes != 0 || stats.WireguardRxBytes != 0 {
		t.Fatalf("WireGuard counters must stay zero without a Client.Status API: tx=%d rx=%d", stats.WireguardTxBytes, stats.WireguardRxBytes)
	}
}

func TestSessionTransportIsReported(t *testing.T) {
	bridge := &TunBridge{
		sessionID: 1,
		token:     &ParsedToken{RegionID: 1},
		transport: "DERP_RELAY",
	}
	stats := bridge.GetStats()
	if stats.Transport != "DERP_RELAY" {
		t.Fatalf("expected DERP_RELAY, got %s", stats.Transport)
	}
	if stats.DirectEndpoint != "" {
		t.Fatalf("expected empty direct endpoint, got %s", stats.DirectEndpoint)
	}

	bridge.transport = "DIRECT_P2P"
	stats = bridge.GetStats()
	if stats.Transport != "DIRECT_P2P" {
		t.Fatalf("expected DIRECT_P2P, got %s", stats.Transport)
	}
}

func TestDERPRegionMetadataResolution(t *testing.T) {
	bridge := &TunBridge{
		sessionID: 1,
		token: &ParsedToken{
			RegionID: 777,
			Region: []*tailcfg.DERPRegion{{
				RegionID:   777,
				RegionCode: "zrh",
				RegionName: "Zurich Swiss Alps",
			}},
		},
		transport: "DERP_RELAY",
	}

	stats := bridge.GetStats()
	if stats.DerpRegionName != "Zurich Swiss Alps" {
		t.Fatalf("expected region name from token 'Zurich Swiss Alps', got %s", stats.DerpRegionName)
	}
	if stats.DerpRegionCode != "zrh" {
		t.Fatalf("expected region code 'zrh', got %s", stats.DerpRegionCode)
	}
}

func TestDERPRegionNameNotSynthesized(t *testing.T) {
	bridge := &TunBridge{
		sessionID: 1,
		token:     &ParsedToken{RegionID: 302},
		transport: "DERP_RELAY",
	}
	stats := bridge.GetStats()
	if stats.DerpRegionName != "" {
		t.Fatalf("expected empty DERP name without DERPMap, got %q", stats.DerpRegionName)
	}
}

func TestJitterNullWhenInsufficientSamples(t *testing.T) {
	bridge := &TunBridge{
		sessionID: 1,
		token:     &ParsedToken{RegionID: 1},
	}

	// 0 samples: Jitter must be nil
	stats := bridge.GetStats()
	if stats.JitterMs != nil {
		t.Fatalf("expected nil jitter for 0 samples, got %v", *stats.JitterMs)
	}

	// 1 sample: Jitter must still be nil
	bridge.RecordRTT(25)
	stats = bridge.GetStats()
	if stats.JitterMs != nil {
		t.Fatalf("expected nil jitter for 1 sample, got %v", *stats.JitterMs)
	}

	// 2 samples: Jitter must still be nil (< 3 samples)
	bridge.RecordRTT(35)
	stats = bridge.GetStats()
	if stats.JitterMs != nil {
		t.Fatalf("expected nil jitter for 2 samples, got %v", *stats.JitterMs)
	}

	// 3 samples: Jitter is computed
	// samples: [25, 35, 29]
	// diffs: |35-25| = 10, |29-35| = 6. Total = 16. Mean = 16 / 2 = 8.
	bridge.RecordRTT(29)
	stats = bridge.GetStats()
	if stats.JitterMs == nil {
		t.Fatal("expected non-nil jitter for 3 samples")
	}
	if *stats.JitterMs != 8 {
		t.Fatalf("expected jitter 8 ms, got %d", *stats.JitterMs)
	}

	// Verify JSON serialization produces `null` when nil
	bEmpty := &TunBridge{sessionID: 1, token: &ParsedToken{RegionID: 1}}
	statsEmpty := bEmpty.GetStats()
	rawJSON, err := json.Marshal(statsEmpty)
	if err != nil {
		t.Fatalf("marshal stats: %v", err)
	}
	var unmarshaled map[string]any
	if err := json.Unmarshal(rawJSON, &unmarshaled); err != nil {
		t.Fatalf("unmarshal stats: %v", err)
	}
	if unmarshaled["jitterMs"] != nil {
		t.Fatalf("expected null jitterMs in JSON, got %v", unmarshaled["jitterMs"])
	}
}

func TestSampleLiveRTTFromDiscoPing(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	latencies := []float64{0.025, 0.035, 0.029}
	n := 0
	mock := &mockTunnelClient{
		discoPingFn: func(context.Context) (*ipnstate.PingResult, error) {
			if n >= len(latencies) {
				n = len(latencies) - 1
			}
			sec := latencies[n]
			n++
			return &ipnstate.PingResult{LatencySeconds: sec}, nil
		},
	}
	bridge := &TunBridge{
		sessionID: 1,
		token:     &ParsedToken{RegionID: 1},
		client:    mock,
		ctx:       ctx,
		cancel:    cancel,
	}
	bridge.sampleLiveRTT()
	bridge.sampleLiveRTT()
	if bridge.GetStats().JitterMs != nil {
		t.Fatal("jitter should be null before 3 samples")
	}
	bridge.sampleLiveRTT()
	stats := bridge.GetStats()
	if stats.JitterMs == nil {
		t.Fatal("expected jitter after 3 live samples")
	}
	if *stats.JitterMs != 8 {
		t.Fatalf("expected jitter 8 ms, got %d", *stats.JitterMs)
	}
	if stats.RTTMs != 29 {
		t.Fatalf("expected rtt 29 ms, got %d", stats.RTTMs)
	}
}

func TestSampleLiveRTTSkipsFailedPing(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	mock := &mockTunnelClient{
		discoPingFn: func(context.Context) (*ipnstate.PingResult, error) {
			return nil, errors.New("disco timeout")
		},
	}
	bridge := &TunBridge{
		sessionID:  1,
		token:      &ParsedToken{RegionID: 1},
		client:     mock,
		ctx:        ctx,
		cancel:     cancel,
		rttMs:      40,
		rttSamples: []int64{40},
	}
	bridge.sampleLiveRTT()
	if bridge.currentRTTMs() != 40 {
		t.Fatalf("failed ping must not overwrite RTT, got %d", bridge.currentRTTMs())
	}
	bridge.rttMu.Lock()
	n := len(bridge.rttSamples)
	bridge.rttMu.Unlock()
	if n != 1 {
		t.Fatalf("failed ping must not append a sample, got %d", n)
	}
}

func TestPacketAndDropCounters(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	bridge := &TunBridge{
		sessionID: 1,
		token:     &ParsedToken{RegionID: 1},
		mtu:       1280,
		ctx:       ctx,
		cancel:    cancel,
	}

	netstack, err := newNetstackProxy(bridge)
	if err != nil {
		t.Fatalf("create netstack: %v", err)
	}
	defer netstack.Close()
	bridge.netstack = netstack

	// 1. Truncated packet (< 20 bytes) -> malformedIP
	bridge.handleOutboundPacket([]byte{0x45, 0x00, 0x00})
	if bridge.malformedIP.Load() != 1 {
		t.Fatalf("expected 1 malformedIP drop, got %d", bridge.malformedIP.Load())
	}

	// 2. Packet exceeding MTU (MTU=1280, length=1281) -> mtuExceeded
	oversized := make([]byte, 1281)
	oversized[0] = 0x45
	bridge.handleOutboundPacket(oversized)
	if bridge.mtuExceeded.Load() != 1 {
		t.Fatalf("expected 1 mtuExceeded drop, got %d", bridge.mtuExceeded.Load())
	}

	// 3. Valid IPv4 TCP packet -> tcpPackets++
	tcpPkt := buildIPv4Packet(6, 12345, 443, []byte("SYN"))
	bridge.handleOutboundPacket(tcpPkt)
	if bridge.tcpPackets.Load() != 1 {
		t.Fatalf("expected 1 tcpPacket, got %d", bridge.tcpPackets.Load())
	}

	// 4. Valid IPv4 UDP packet to port 53 (DNS) -> udpPackets++, dnsQueries++
	dnsPkt := buildIPv4Packet(17, 54321, 53, []byte("QUERY"))
	bridge.handleOutboundPacket(dnsPkt)
	if bridge.udpPackets.Load() != 1 {
		t.Fatalf("expected 1 udpPacket, got %d", bridge.udpPackets.Load())
	}
	if bridge.dnsQueries.Load() != 1 {
		t.Fatalf("expected 1 dnsQuery, got %d", bridge.dnsQueries.Load())
	}

	// Check that GetStats reports these exact counters
	stats := bridge.GetStats()
	if stats.TCPPackets != 1 || stats.UDPPackets != 1 || stats.DNSQueries != 1 {
		t.Fatalf("stats packet counts mismatch: tcp=%d udp=%d dns=%d", stats.TCPPackets, stats.UDPPackets, stats.DNSQueries)
	}
	if stats.DropCounters.MalformedIP != 1 || stats.DropCounters.MTUExceeded != 1 {
		t.Fatalf("stats drop counters mismatch: malformed=%d mtu=%d", stats.DropCounters.MalformedIP, stats.DropCounters.MTUExceeded)
	}
}

func TestDisconnectedTelemetryFormat(t *testing.T) {
	_ = Stop()
	raw := GetStatsJSON()

	var stats EngineStats
	if err := json.Unmarshal([]byte(raw), &stats); err != nil {
		t.Fatalf("failed to unmarshal disconnected stats: %v", err)
	}

	if stats.Version != 2 {
		t.Fatalf("expected version 2 in disconnected stats, got %d", stats.Version)
	}
	if stats.Transport != "DISCONNECTED" {
		t.Fatalf("expected DISCONNECTED transport, got %s", stats.Transport)
	}
	if stats.State != "STOPPED" {
		t.Fatalf("expected STOPPED state, got %s", stats.State)
	}
}

func buildIPv4Packet(protocol byte, srcPort, dstPort uint16, payload []byte) []byte {
	totalLen := 20 + 8 + len(payload)
	pkt := make([]byte, totalLen)
	pkt[0] = 0x45 // IPv4, IHL=5
	pkt[1] = 0x00
	pkt[2] = byte(totalLen >> 8)
	pkt[3] = byte(totalLen & 0xff)
	pkt[8] = 64 // TTL
	pkt[9] = protocol
	// src IP: 100.64.0.2
	copy(pkt[12:16], []byte{100, 64, 0, 2})
	// dst IP: 1.1.1.1
	copy(pkt[16:20], []byte{1, 1, 1, 1})

	// Transport header
	pkt[20] = byte(srcPort >> 8)
	pkt[21] = byte(srcPort & 0xff)
	pkt[22] = byte(dstPort >> 8)
	pkt[23] = byte(dstPort & 0xff)
	pkt[24] = byte((8 + len(payload)) >> 8)
	pkt[25] = byte((8 + len(payload)) & 0xff)
	copy(pkt[28:], payload)
	return pkt
}
