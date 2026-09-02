package engine

import (
	"encoding/json"
	"os"
	"testing"

	"github.com/tailscale/tailcat"
)

type TokenFixture struct {
	Name                   string `json:"name"`
	Token                  string `json:"token"`
	Description            string `json:"description"`
	ExpectedClassification string `json:"expectedClassification"`
	ExpectedErrorCode      string `json:"expectedErrorCode,omitempty"`
	ExpectedNodeKeyHex     string `json:"expectedNodeKeyHex,omitempty"`
	ExpectedDiscoKeyHex    string `json:"expectedDiscoKeyHex,omitempty"`
	ExpectedRegionID       int    `json:"expectedRegionId,omitempty"`
	HasEmbeddedRegion      bool   `json:"hasEmbeddedRegion"`
}

func TestVerifyCanonicalFixtures(t *testing.T) {
	// Read-only verification of canonical fixtures file
	data, err := os.ReadFile("testdata/token_fixtures.json")
	if err != nil {
		t.Fatalf("Failed to read testdata/token_fixtures.json: %v", err)
	}

	var fixtures []TokenFixture
	if err := json.Unmarshal(data, &fixtures); err != nil {
		t.Fatalf("Failed to unmarshal fixtures: %v", err)
	}

	if len(fixtures) == 0 {
		t.Fatal("Fixtures array is empty")
	}

	for _, f := range fixtures {
		pt, parseErr := ParseToken(f.Token)
		if string(pt.Classification) != f.ExpectedClassification {
			t.Errorf("[%s] Expected classification %s, got %s (err: %v)",
				f.Name, f.ExpectedClassification, pt.Classification, parseErr)
		}

		if f.ExpectedClassification == "VALID_OFFICIAL_SHORT" || f.ExpectedClassification == "VALID_OFFICIAL_RESOLVED" {
			if parseErr != nil {
				t.Errorf("[%s] Unexpected error on valid token: %v", f.Name, parseErr)
			}
			if !pt.IsConnectable() {
				t.Errorf("[%s] Valid token expected to be connectable", f.Name)
			}
			if f.ExpectedNodeKeyHex != "" && pt.ServerPublicHex != f.ExpectedNodeKeyHex {
				t.Errorf("[%s] NodeKey mismatch: expected %s, got %s", f.Name, f.ExpectedNodeKeyHex, pt.ServerPublicHex)
			}
			if f.ExpectedDiscoKeyHex != "" && pt.ServerDiscoHex != f.ExpectedDiscoKeyHex {
				t.Errorf("[%s] DiscoKey mismatch: expected %s, got %s", f.Name, f.ExpectedDiscoKeyHex, pt.ServerDiscoHex)
			}
			if pt.ServerPublicHex == pt.ServerDiscoHex {
				t.Errorf("[%s] NodeKey and DiscoKey must be separate (p != k)", f.Name)
			}
			if f.ExpectedRegionID != 0 && int(pt.RegionID) != f.ExpectedRegionID {
				t.Errorf("[%s] RegionID mismatch: expected %d, got %d", f.Name, f.ExpectedRegionID, pt.RegionID)
			}
			if pt.HasEmbeddedRegion != f.HasEmbeddedRegion {
				t.Errorf("[%s] HasEmbeddedRegion mismatch: expected %v, got %v", f.Name, f.HasEmbeddedRegion, pt.HasEmbeddedRegion)
			}

			// Must be accepted directly by upstream tailcat.ParseConnBlob
			ci, err := tailcat.ParseConnBlob(tailcat.ConnBlob(f.Token))
			if err != nil {
				t.Errorf("[%s] Upstream ParseConnBlob failed on valid token: %v", f.Name, err)
			}
			if ci.ServerPublic.NodePublic.String() != pt.ServerPublic.String() {
				t.Errorf("[%s] Upstream NodePublic mismatch", f.Name)
			}
			if ci.ServerDiscoPublic.DiscoPublic.String() != pt.ServerDiscoPublic.String() {
				t.Errorf("[%s] Upstream DiscoPublic mismatch", f.Name)
			}
		}

		if f.ExpectedClassification == "LEGACY_REISSUE_REQUIRED" {
			if pt.IsConnectable() {
				t.Errorf("[%s] Legacy token must NEVER be connectable", f.Name)
			}
		}

		if f.ExpectedClassification == "EXPIRED" {
			if pt.IsConnectable() {
				t.Errorf("[%s] Expired token must NEVER be connectable", f.Name)
			}
		}

		if f.ExpectedErrorCode != "" && string(pt.ErrorCode) != f.ExpectedErrorCode {
			t.Errorf("[%s] Expected errorCode %s, got %s (err: %s)", f.Name, f.ExpectedErrorCode, pt.ErrorCode, pt.ErrorMessage)
		}
	}
}

func TestPrepareRejectsLegacyAndInvalidTokens(t *testing.T) {
	// 1. Prepare must fail on legacy numeric-r token
	legacyToken := "tcomFwWCCcjS5nKNqAod034nWoJZW0LZqDhhC8U_dKdnDRYQ8uNGFpGQEu"
	if err := Prepare(legacyToken); err == nil {
		t.Fatal("Prepare must reject legacy numeric-r token")
	}

	// 2. Prepare must fail on empty token
	if err := Prepare(""); err == nil {
		t.Fatal("Prepare must reject empty token")
	}

	// 3. Prepare must fail on invalid token
	if err := Prepare("tcInvalid!"); err == nil {
		t.Fatal("Prepare must reject invalid token")
	}
}
