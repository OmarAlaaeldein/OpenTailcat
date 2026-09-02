package engine

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
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
	nodeKey := key.NewNode().Public()
	peer := &ipnstate.PeerStatus{
		PublicKey:     nodeKey,
		TxBytes:       50000,
		RxBytes:       75000,
		LastHandshake: time.Unix(1725300000, 0),
		CurAddr:       "203.0.113.50:41641",
	}

	status := &ipnstate.Status{
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			nodeKey: peer,
		},
	}

	mockClient := &mockTunnelClient{
		nodeKey:  nodeKey,
		statusFn: func() *ipnstate.Status { return status },
	}

	bridge := &TunBridge{
		sessionID: 1,
		token:     &ParsedToken{RegionID: 1},
		transport: "DERP_RELAY",
		client:    mockClient,
	}
	bridge.txBytes.Store(12000)
	bridge.rxBytes.Store(18000)

	stats := bridge.GetStats()

	// TUN accepted payload bytes
	if stats.TunTxBytes != 12000 || stats.TunRxBytes != 18000 {
		t.Fatalf("TUN counters mismatch: tx=%d rx=%d (expected 12000/18000)", stats.TunTxBytes, stats.TunRxBytes)
	}

	// Authoritative WireGuard encrypted transport bytes
	if stats.WireguardTxBytes != 50000 || stats.WireguardRxBytes != 75000 {
		t.Fatalf("WireGuard counters mismatch: tx=%d rx=%d (expected 50000/75000)", stats.WireguardTxBytes, stats.WireguardRxBytes)
	}

	// Reported TxBytes/RxBytes for backward-compat mirror WireGuard
	if stats.TxBytes != 50000 || stats.RxBytes != 75000 {
		t.Fatalf("Mirror counters mismatch: tx=%d rx=%d", stats.TxBytes, stats.RxBytes)
	}

	if stats.LastHandshakeSec != 1725300000 {
		t.Fatalf("expected last handshake 1725300000, got %d", stats.LastHandshakeSec)
	}
}

func TestDynamicTransportAndEndpointUpdate(t *testing.T) {
	nodeKey := key.NewNode().Public()

	// Initially connected via DERP relay
	peer := &ipnstate.PeerStatus{
		PublicKey: nodeKey,
		Relay:     "nyc",
	}
	status := &ipnstate.Status{
		Peer: map[key.NodePublic]*ipnstate.PeerStatus{
			nodeKey: peer,
		},
	}

	mockClient := &mockTunnelClient{
		nodeKey:  nodeKey,
		statusFn: func() *ipnstate.Status { return status },
	}

	bridge := &TunBridge{
		sessionID: 1,
		token:     &ParsedToken{RegionID: 1},
		transport: "DERP_RELAY",
		client:    mockClient,
	}

	stats1 := bridge.GetStats()
	if stats1.Transport != "DERP_RELAY" {
		t.Fatalf("expected DERP_RELAY, got %s", stats1.Transport)
	}
	if stats1.DerpRegionCode != "nyc" {
		t.Fatalf("expected region code nyc, got %s", stats1.DerpRegionCode)
	}
	if stats1.DirectEndpoint != "" {
		t.Fatalf("expected empty direct endpoint, got %s", stats1.DirectEndpoint)
	}

	// Magicsock discovers direct path and roams to Direct P2P
	peer.CurAddr = "198.51.100.99:41641"
	peer.Relay = ""

	stats2 := bridge.GetStats()
	if stats2.Transport != "DIRECT_P2P" {
		t.Fatalf("expected DIRECT_P2P after roaming, got %s", stats2.Transport)
	}
	if stats2.DirectEndpoint != "198.51.100.99:41641" {
		t.Fatalf("expected direct endpoint 198.51.100.99:41641, got %s", stats2.DirectEndpoint)
	}
}

func TestDERPRegionMetadataResolution(t *testing.T) {
	nodeKey := key.NewNode().Public()
	dm := &tailcfg.DERPMap{
		Regions: map[tailcfg.DERPRegionID]*tailcfg.DERPRegion{
			777: {
				RegionID:   777,
				RegionCode: "zrh",
				RegionName: "Zurich Swiss Alps",
			},
		},
	}

	mockClient := &mockTunnelClient{
		nodeKey: nodeKey,
		derpMap: dm,
	}

	bridge := &TunBridge{
		sessionID: 1,
		token:     &ParsedToken{RegionID: 777},
		transport: "DERP_RELAY",
		client:    mockClient,
	}

	stats := bridge.GetStats()
	if stats.DerpRegionName != "Zurich Swiss Alps" {
		t.Fatalf("expected region name from DERPMap 'Zurich Swiss Alps', got %s", stats.DerpRegionName)
	}
	if stats.DerpRegionCode != "zrh" {
		t.Fatalf("expected region code 'zrh', got %s", stats.DerpRegionCode)
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

	// 2 samples: Jitter must still be nil (< 3 samples per RFC 3550 requirement)
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
