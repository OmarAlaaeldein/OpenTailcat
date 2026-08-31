package engine

import (
	"encoding/base64"
	"encoding/json"
	"net/netip"
	"strings"
	"testing"
	"time"

	"github.com/fxamacker/cbor/v2"
	"tailscale.com/types/key"
)

func TestGetCapabilitiesJSON(t *testing.T) {
	raw := GetCapabilitiesJSON()

	var caps struct {
		APIVersion    int  `json:"apiVersion"`
		DataPlane     bool `json:"dataPlane"`
		WireGuard     bool `json:"wireGuard"`
		Magicsock     bool `json:"magicsock"`
		TwoPhaseStart bool `json:"twoPhaseStart"`
	}

	if err := json.Unmarshal([]byte(raw), &caps); err != nil {
		t.Fatalf("Failed to parse capabilities JSON: %v", err)
	}

	if caps.APIVersion < 1 {
		t.Errorf("Expected apiVersion >= 1, got %d", caps.APIVersion)
	}
	if !caps.DataPlane {
		t.Error("Expected dataPlane to be true")
	}
	if !caps.WireGuard {
		t.Error("Expected wireGuard to be true")
	}
	if !caps.Magicsock {
		t.Error("Expected magicsock to be true")
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

func TestRegionNameForID(t *testing.T) {
	tests := []struct {
		id   int
		name string
	}{
		{1, "New York City"},
		{2, "San Francisco"},
		{4, "Frankfurt"},
		{10, "Seattle"},
		{302, "San Francisco"},
		{999, "Region 999"},
	}

	for _, tt := range tests {
		got := regionNameForID(tt.id)
		if got != tt.name {
			t.Errorf("regionNameForID(%d) = %q, want %q", tt.id, got, tt.name)
		}
	}
}
