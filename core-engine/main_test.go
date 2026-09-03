package engine

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/fxamacker/cbor/v2"
	"github.com/tailscale/tailcat"
	"go4.org/mem"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

func TestGetCapabilitiesJSON(t *testing.T) {
	raw := GetCapabilitiesJSON()

	var caps Capabilities
	if err := json.Unmarshal([]byte(raw), &caps); err != nil {
		t.Fatalf("Failed to parse capabilities JSON: %v", err)
	}

	if caps.APIVersion < 2 {
		t.Errorf("Expected apiVersion >= 2, got %d", caps.APIVersion)
	}
	if !caps.DataPlane || !caps.WireGuard || !caps.Magicsock || !caps.TwoPhaseStart {
		t.Error("Expected IPv4 test-routing caps dataPlane/wireGuard/magicsock/twoPhaseStart true")
	}
	if !caps.IPv4 || !caps.TCP || !caps.UDP || !caps.DNS || !caps.LiveStats || !caps.CancelSafeLifecycle {
		t.Error("Expected IPv4 test-routing caps ipv4/tcp/udp/dns/liveStats/cancelSafeLifecycle true")
	}
	if caps.IPv6 {
		t.Error("Expected ipv6 to stay false until dual-stack ::/0 evidence exists")
	}
}

func TestParseTokenOfficialVectors(t *testing.T) {
	// Sample older token with i but missing disco key k
	shortNoDiscoToken := "tcomFwWCCcjS5nKNqAod034nWoJZW0LZqDhhC8U_dKdnDRYQ8uNGFpGQEu"
	ptNoDisco, _ := ParseToken(shortNoDiscoToken)
	if ptNoDisco.Classification != ClassificationInvalid || ptNoDisco.ErrorCode != ErrMissingDiscoKey {
		t.Errorf("Expected token without disco key to be INVALID / ERR_MISSING_DISCO_KEY, got %v / %v",
			ptNoDisco.Classification, ptNoDisco.ErrorCode)
	}

	// Official token with deterministic p, k, i
	var nodeRaw, discoRaw [32]byte
	for i := 0; i < 32; i++ {
		nodeRaw[i] = byte(i + 1)
		discoRaw[i] = byte(i + 33)
	}
	nodePub := key.NodePrivateFromRaw32(mem.B(nodeRaw[:])).Public()
	discoPub := key.DiscoPrivateFromRaw32(mem.B(discoRaw[:])).Public()

	wireMap := map[string]any{
		"p": nodePub.AppendTo(nil),
		"k": discoPub.AppendTo(nil),
		"i": uint64(302),
	}
	cborBytes, _ := cbor.Marshal(wireMap)
	officialToken := "tc" + base64.RawURLEncoding.EncodeToString(cborBytes)

	pt, err := ParseToken(officialToken)
	if err != nil {
		t.Fatalf("ParseToken failed: %v", err)
	}
	if pt.Classification != ClassificationValidOfficialShort {
		t.Errorf("Expected VALID_OFFICIAL_SHORT, got %v", pt.Classification)
	}
	if pt.RegionID != 302 {
		t.Errorf("Expected region 302, got %v", pt.RegionID)
	}
	if pt.ServerPublic.String() != nodePub.String() {
		t.Errorf("Unexpected server public key: %v", pt.ServerPublic.String())
	}
}

func TestParseTokenWithDiscoKeyAndRegion(t *testing.T) {
	var nodeRaw, discoRaw [32]byte
	for i := 0; i < 32; i++ {
		nodeRaw[i] = byte(i + 1)
		discoRaw[i] = byte(i + 33)
	}
	nodePub := key.NodePrivateFromRaw32(mem.B(nodeRaw[:])).Public()
	discoPub := key.DiscoPrivateFromRaw32(mem.B(discoRaw[:])).Public()

	wireMap := map[string]any{
		"p": nodePub.AppendTo(nil),
		"k": discoPub.AppendTo(nil),
		"i": int64(10),
	}

	cborBytes, err := cbor.Marshal(wireMap)
	if err != nil {
		t.Fatalf("CBOR marshal: %v", err)
	}

	token := "tc" + base64.RawURLEncoding.EncodeToString(cborBytes)
	pt, err := ParseToken(token)
	if err != nil {
		t.Fatalf("ParseToken failed: %v", err)
	}

	if pt.RegionID != 10 {
		t.Errorf("Expected region 10, got %v", pt.RegionID)
	}
	if pt.ServerPublic.String() != nodePub.String() {
		t.Errorf("Server public mismatch: got %v want %v", pt.ServerPublic, nodePub)
	}
	if pt.ServerDiscoPublic.String() != discoPub.String() {
		t.Errorf("Disco public mismatch: got %v want %v", pt.ServerDiscoPublic, discoPub)
	}
}

func TestParseTokenExpiredRejection(t *testing.T) {
	var nodeRaw, discoRaw [32]byte
	for i := 0; i < 32; i++ {
		nodeRaw[i] = byte(i + 1)
		discoRaw[i] = byte(i + 33)
	}
	nodePub := key.NodePrivateFromRaw32(mem.B(nodeRaw[:])).Public()
	discoPub := key.DiscoPrivateFromRaw32(mem.B(discoRaw[:])).Public()
	past := time.Now().Unix() - 3600

	wireMap := map[string]any{
		"p":   nodePub.AppendTo(nil),
		"k":   discoPub.AppendTo(nil),
		"i":   uint64(1),
		"exp": past,
	}

	cborBytes, err := cbor.Marshal(wireMap)
	if err != nil {
		t.Fatalf("CBOR marshal: %v", err)
	}

	token := "tc" + base64.RawURLEncoding.EncodeToString(cborBytes)
	_, err = ParseToken(token)
	if err == nil {
		t.Fatal("Expected expired token to be rejected, but it succeeded")
	}
	if !strings.Contains(err.Error(), "expired") {
		t.Errorf("Expected error to mention expiration, got: %v", err)
	}
}

func TestChecksumCalculations(t *testing.T) {
	srcAP := netip.MustParseAddrPort("100.64.0.2:12345")
	dstAP := netip.MustParseAddrPort("1.1.1.1:53")
	payload := []byte("test-dns-payload")

	v4Pkt := buildIPv4UDPPacket(srcAP, dstAP, payload)
	if len(v4Pkt) < 28 {
		t.Fatalf("IPv4 packet too short: %d", len(v4Pkt))
	}

	chk := ipv4Checksum(v4Pkt[:20])
	if chk != 0 {
		t.Errorf("IPv4 header checksum validation failed: %x", chk)
	}

	srcV6AP := netip.MustParseAddrPort("[fd7a:115c:a1e0::2]:12345")
	dstV6AP := netip.MustParseAddrPort("[2606:4700:4700::1111]:53")
	v6Pkt := buildIPv6UDPPacket(srcV6AP, dstV6AP, payload)
	if len(v6Pkt) < 48 {
		t.Fatalf("IPv6 packet too short: %d", len(v6Pkt))
	}
}

func TestStopLifecycle(t *testing.T) {
	// Call Stop when already stopped
	err := Stop()
	if err != nil {
		t.Fatalf("Stop() should be idempotent, got error: %v", err)
	}

	statsJSON := GetStatsJSON()
	if !strings.Contains(statsJSON, "DISCONNECTED") {
		t.Errorf("Expected DISCONNECTED stats after Stop, got: %s", statsJSON)
	}
}

func TestPrepareInvalidToken(t *testing.T) {
	err := Prepare("tcInvalidPayload")
	if err == nil {
		t.Fatal("Expected Prepare with invalid token to fail")
	}
}

func TestLegacyNumericRTokenHandling(t *testing.T) {
	var nodeRaw [32]byte
	for i := 0; i < 32; i++ {
		nodeRaw[i] = byte(i + 1)
	}
	nodePub := key.NodePrivateFromRaw32(mem.B(nodeRaw[:])).Public()

	wireMap := map[string]any{
		"p": nodePub.AppendTo(nil),
		"r": uint64(302),
	}

	cborBytes, err := cbor.Marshal(wireMap)
	if err != nil {
		t.Fatalf("CBOR marshal: %v", err)
	}

	token := "tc" + base64.RawURLEncoding.EncodeToString(cborBytes)
	pt, _ := ParseToken(token)

	if pt.Classification != ClassificationLegacyReissueRequired {
		t.Fatalf("Expected classification LEGACY_REISSUE_REQUIRED, got %v", pt.Classification)
	}

	if pt.IsConnectable() {
		t.Fatal("Legacy numeric-r token must NOT be connectable")
	}

	if err := Prepare(token); err == nil {
		t.Fatal("Prepare must reject legacy numeric-r token without disco key")
	}
}

func TestUpdateNetworkStateJSON(t *testing.T) {
	// Test invalid JSON rejection
	if err := UpdateNetworkState("{invalid_json"); err == nil {
		t.Fatal("Expected error on invalid JSON payload")
	}

	// Test valid JSON payload with IPv4 and IPv6 CIDR and plain addresses
	payload := `{
		"isOnline": true,
		"networkType": "WIFI",
		"interfaces": [
			{
				"name": "wlan0",
				"addresses": ["192.168.1.50/24", "2607:f8b0:4005:805::200e/64"],
				"flags": "UP|RUNNING|BROADCAST",
				"mtu": 1500
			},
			{
				"name": "rmnet0",
				"addresses": ["10.15.20.30"],
				"flags": "UP|RUNNING|POINTTOPOINT",
				"mtu": 1420
			}
		],
		"gateways": ["192.168.1.1", "2607:f8b0:4005:805::1"],
		"dnsServers": ["1.1.1.1", "8.8.8.8"]
	}`

	if err := UpdateNetworkState(payload); err != nil {
		t.Fatalf("UpdateNetworkState failed: %v", err)
	}

	netStateMu.RLock()
	if len(customIfs) != 2 {
		netStateMu.RUnlock()
		t.Fatalf("Expected 2 interfaces, got %d", len(customIfs))
	}

	wlan := customIfs[0]
	if wlan.Interface.Name != "wlan0" {
		t.Errorf("Expected wlan0, got %s", wlan.Interface.Name)
	}
	if wlan.Interface.MTU != 1500 {
		t.Errorf("Expected MTU 1500, got %d", wlan.Interface.MTU)
	}
	if len(wlan.AltAddrs) != 2 {
		t.Errorf("Expected 2 addresses for wlan0, got %d", len(wlan.AltAddrs))
	}
	if wlan.AltAddrs[0].String() != "192.168.1.50/24" {
		t.Errorf("Expected 192.168.1.50/24, got %s", wlan.AltAddrs[0].String())
	}
	if wlan.AltAddrs[1].String() != "2607:f8b0:4005:805::200e/64" {
		t.Errorf("Expected 2607:f8b0:4005:805::200e/64, got %s", wlan.AltAddrs[1].String())
	}

	rmnet := customIfs[1]
	if rmnet.Interface.Name != "rmnet0" {
		t.Errorf("Expected rmnet0, got %s", rmnet.Interface.Name)
	}
	if rmnet.Interface.MTU != 1420 {
		t.Errorf("Expected MTU 1420, got %d", rmnet.Interface.MTU)
	}
	if (rmnet.Interface.Flags & net.FlagPointToPoint) == 0 {
		t.Error("Expected PointToPoint flag on rmnet0")
	}
	netStateMu.RUnlock()

	// Test reset with empty string
	if err := UpdateNetworkState(""); err != nil {
		t.Fatalf("UpdateNetworkState reset failed: %v", err)
	}
	netStateMu.RLock()
	if len(customIfs) != 0 {
		t.Errorf("Expected empty customIfs after reset, got %d", len(customIfs))
	}
	netStateMu.RUnlock()
}

func TestLiveMonitorLifecycle(t *testing.T) {
	// 1. Initially activeMonitor must be nil before prepare/start
	netStateMu.RLock()
	if activeMonitor != nil {
		netStateMu.RUnlock()
		t.Fatal("Expected activeMonitor to be nil initially")
	}
	netStateMu.RUnlock()

	// 2. Setting a live monitor triggers event injection on UpdateNetworkState
	liveMon := netmon.NewStatic()
	netStateMu.Lock()
	activeMonitor = liveMon
	netStateMu.Unlock()

	// UpdateNetworkState should invoke liveMon.InjectEvent() without panic
	payload := `{"isOnline":true,"networkType":"WIFI","interfaces":[{"name":"wlan0","addresses":["192.168.1.100/24"]}]}`
	if err := UpdateNetworkState(payload); err != nil {
		t.Fatalf("UpdateNetworkState with live monitor failed: %v", err)
	}

	// 3. Stop lifecycle resets activeMonitor to nil
	if err := Stop(); err != nil {
		t.Fatalf("Stop failed: %v", err)
	}

	netStateMu.RLock()
	if activeMonitor != nil {
		netStateMu.RUnlock()
		t.Fatal("Expected activeMonitor to be reset to nil after Stop")
	}
	netStateMu.RUnlock()

	// Clean up custom interfaces
	_ = UpdateNetworkState("")
}

func TestCapabilityEncodingAndParsing(t *testing.T) {
	// 1. Legacy 5-byte meowed (without capability byte)
	legacyMeowed := []byte{'m', 'e', 'o', 'w', 0x02}
	if caps := tailcat.ParseMeowedCaps(legacyMeowed); caps != 0 {
		t.Errorf("Expected legacy meowed caps = 0, got %02x", caps)
	}

	// 2. Modern meowed with CapExitUDP
	modernMeowed := tailcat.EncodeMeowedWithCaps(tailcat.CapExitUDP | tailcat.CapExitTCP)
	if caps := tailcat.ParseMeowedCaps(modernMeowed); caps != (tailcat.CapExitUDP | tailcat.CapExitTCP) {
		t.Errorf("Expected caps = %02x, got %02x", tailcat.CapExitUDP|tailcat.CapExitTCP, caps)
	}

	// 3. Meowed with unknown future capability bits (0xff)
	futureMeowed := tailcat.EncodeMeowedWithCaps(0xff)
	if caps := tailcat.ParseMeowedCaps(futureMeowed); caps != 0xff {
		t.Errorf("Expected caps = 0xff, got %02x", caps)
	}

	// 4. Invalid packet
	if caps := tailcat.ParseMeowedCaps([]byte("invalid")); caps != 0 {
		t.Errorf("Expected invalid packet caps = 0, got %02x", caps)
	}
}

type prepareTestClient struct {
	caps   uint8
	closed bool
}

func (c *prepareTestClient) Ping(context.Context) (tailcat.PingResult, error) {
	return tailcat.PingResult{Latency: time.Millisecond}, nil
}

func (c *prepareTestClient) DiscoPing(context.Context) (*ipnstate.PingResult, error) {
	return nil, errors.New("direct path unavailable in test")
}

func (c *prepareTestClient) HasServerCap(cap uint8) bool { return c.caps&cap != 0 }
func (c *prepareTestClient) NetMon() *netmon.Monitor     { return nil }
func (c *prepareTestClient) Close() error {
	c.closed = true
	return nil
}
func (c *prepareTestClient) Dial(context.Context, string, string) (net.Conn, error) {
	return nil, errors.New("unexpected Dial")
}
func (c *prepareTestClient) DialTCP(context.Context, netip.AddrPort) (net.Conn, error) {
	return nil, errors.New("unexpected DialTCP")
}
func (c *prepareTestClient) DialUDP(context.Context, netip.AddrPort) (net.Conn, error) {
	return nil, errors.New("unexpected DialUDP")
}
func (c *prepareTestClient) Status() *ipnstate.Status       { return nil }
func (c *prepareTestClient) ServerNodeKey() key.NodePublic { return key.NodePublic{} }
func (c *prepareTestClient) DERPMap() *tailcfg.DERPMap     { return nil }

func TestPrepareAcceptsTCPOnlyGateway(t *testing.T) {
	if err := Stop(); err != nil {
		t.Fatalf("reset engine: %v", err)
	}

	var nodeRaw, discoRaw [32]byte
	for i := 0; i < 32; i++ {
		nodeRaw[i] = byte(i + 1)
		discoRaw[i] = byte(i + 33)
	}
	nodePub := key.NodePrivateFromRaw32(mem.B(nodeRaw[:])).Public()
	discoPub := key.DiscoPrivateFromRaw32(mem.B(discoRaw[:])).Public()

	wireMap := map[string]any{
		"p": nodePub.AppendTo(nil),
		"k": discoPub.AppendTo(nil),
		"i": uint64(1),
	}
	cborBytes, _ := cbor.Marshal(wireMap)
	tokenStr := "tc" + base64.RawURLEncoding.EncodeToString(cborBytes)

	fake := &prepareTestClient{caps: tailcat.CapExitTCP}
	originalFactory := newTailcatClient
	newTailcatClient = func(tailcat.ConnBlob) preparedClient { return fake }
	defer func() {
		newTailcatClient = originalFactory
		_ = Stop()
	}()

	if err := Prepare(tokenStr); err != nil {
		t.Fatalf("expected Prepare to accept TCP-only gateway: %v", err)
	}
	if globalCore.state != StatePrepared {
		t.Errorf("expected PREPARED, got %s", globalCore.state)
	}
	if globalCore.sess == nil || !globalCore.sess.tcpOnly {
		t.Fatal("expected tcpOnly session for CapExitTCP without CapExitUDP")
	}
}
