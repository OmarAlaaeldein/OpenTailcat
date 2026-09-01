package engine

import (
	"encoding/base64"
	"encoding/json"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/fxamacker/cbor/v2"
	"github.com/tailscale/tailcat"
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
	// Fail-closed invariant: incomplete engine MUST NOT report dataPlane as true.
	if caps.DataPlane {
		t.Error("Expected dataPlane to be false under Phase 0 fail-closed invariant")
	}
	if caps.IPv4 {
		t.Error("Expected ipv4 to be false until Phase 5 tests pass")
	}
	if caps.IPv6 {
		t.Error("Expected ipv6 to be false until Phase 5 tests pass")
	}
	if caps.TCP {
		t.Error("Expected tcp to be false until Phase 3/8 tests pass")
	}
	if caps.UDP {
		t.Error("Expected udp to be false until Phase 3 tunneled UDP tests pass")
	}
	if caps.DNS {
		t.Error("Expected dns to be false until Phase 4 resolver policy tests pass")
	}
	if caps.LiveStats {
		t.Error("Expected liveStats to be false until Phase 7 tests pass")
	}
	if caps.CancelSafeLifecycle {
		t.Error("Expected cancelSafeLifecycle to be false until Phase 6 refactor")
	}
	if !caps.TwoPhaseStart {
		t.Error("Expected twoPhaseStart to be true")
	}
}

func TestParseTokenOfficialVectors(t *testing.T) {
	// Sample official token from README
	token := "tcomFwWCCcjS5nKNqAod034nWoJZW0LZqDhhC8U_dKdnDRYQ8uNGFpGQEu"
	pt, err := ParseToken(token)
	if err != nil {
		t.Fatalf("ParseToken failed: %v", err)
	}

	if pt.RegionID != 302 {
		t.Errorf("Expected region 302, got %v", pt.RegionID)
	}
	if pt.ServerPublic.String() != "nodekey:9c8d2e6728da80a1dd37e275a82595b42d9a838610bc53f74a7670d1610f2e34" {
		t.Errorf("Unexpected server public key: %v", pt.ServerPublic.String())
	}
}

func TestParseTokenWithDiscoKeyAndRegion(t *testing.T) {
	nodePriv := key.NewNode()
	discoPriv := key.NewDisco()

	wireMap := map[string]any{
		"p": nodePriv.Public().AppendTo(nil),
		"k": discoPriv.Public().AppendTo(nil),
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
	if pt.ServerPublic.String() != nodePriv.Public().String() {
		t.Errorf("Server public mismatch: got %v want %v", pt.ServerPublic, nodePriv.Public())
	}
	if pt.ServerDiscoPublic.String() != discoPriv.Public().String() {
		t.Errorf("Disco public mismatch: got %v want %v", pt.ServerDiscoPublic, discoPriv.Public())
	}
}

func TestParseTokenExpiredRejection(t *testing.T) {
	nodePriv := key.NewNode()
	past := time.Now().Unix() - 3600

	wireMap := map[string]any{
		"p":   nodePriv.Public().AppendTo(nil),
		"r":   uint64(1),
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
	if err := Stop(); err != nil {
		t.Fatalf("First Stop() call failed: %v", err)
	}
	// Verify idempotency
	if err := Stop(); err != nil {
		t.Fatalf("Second Stop() call failed: %v", err)
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
	nodePriv := key.NewNode()

	wireMap := map[string]any{
		"p": nodePriv.Public().AppendTo(nil),
		"r": uint64(302),
	}

	cborBytes, err := cbor.Marshal(wireMap)
	if err != nil {
		t.Fatalf("CBOR marshal: %v", err)
	}

	token := "tc" + base64.RawURLEncoding.EncodeToString(cborBytes)
	pt, err := ParseToken(token)
	if err != nil {
		t.Fatalf("ParseToken failed on legacy numeric r: %v", err)
	}

	if pt.RegionID != 302 {
		t.Errorf("Expected region 302, got %v", pt.RegionID)
	}

	// Canonical token must parse directly with upstream tailcat.ParseConnBlob
	canonicalBlob := pt.CanonicalToken()
	if !strings.HasPrefix(canonicalBlob, "tc") {
		t.Fatalf("Canonical token missing tc prefix: %s", canonicalBlob)
	}

	// Verify that the canonical representation is accepted by upstream without
	// starting a real network engine for this intentionally fabricated token.
	if _, err := tailcat.ParseConnBlob(tailcat.ConnBlob(canonicalBlob)); err != nil {
		t.Fatalf("upstream rejected canonical legacy token: %v", err)
	}
}
