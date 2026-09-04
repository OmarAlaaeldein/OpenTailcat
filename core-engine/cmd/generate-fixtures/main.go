package main

import (
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"

	"github.com/fxamacker/cbor/v2"
	"github.com/tailscale/tailcat"
	"go4.org/mem"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
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

var canonicalEncMode = func() cbor.EncMode {
	mode, err := cbor.CanonicalEncOptions().EncMode()
	if err != nil {
		panic(err)
	}
	return mode
}()

func main() {
	var fixedNodeRaw [32]byte
	var fixedDiscoRaw [32]byte
	for i := 0; i < 32; i++ {
		fixedNodeRaw[i] = byte(i + 1)
		fixedDiscoRaw[i] = byte(i + 33)
	}

	nodePriv := key.NodePrivateFromRaw32(mem.B(fixedNodeRaw[:]))
	discoPriv := key.DiscoPrivateFromRaw32(mem.B(fixedDiscoRaw[:]))

	nodePub := nodePriv.Public()
	discoPub := discoPriv.Public()
	nodePubBytes := nodePub.Raw32()
	discoPubBytes := discoPub.Raw32()

	// 1. Official valid short token (p, k, i)
	shortCI := tailcat.ConnInfo{
		ServerPublic:      tailcat.NodePublic{NodePublic: nodePub},
		ServerDiscoPublic: tailcat.DiscoPublic{DiscoPublic: discoPub},
		RegionID:          302,
	}
	officialShortToken := string(shortCI.ConnBlob())

	// 2. Official valid resolved token (p, k, r)
	region301 := &tailcfg.DERPRegion{
		RegionID:   301,
		RegionCode: "nyc",
		RegionName: "New York City",
		Nodes: []*tailcfg.DERPNode{
			{
				Name:     "301a",
				RegionID: 301,
				HostName: "tc301a.ipn.dev",
				IPv4:     "199.38.181.166",
				IPv6:     "2607:f740:f::26b",
			},
		},
	}
	resolvedCI := tailcat.ConnInfo{
		ServerPublic:      tailcat.NodePublic{NodePublic: nodePub},
		ServerDiscoPublic: tailcat.DiscoPublic{DiscoPublic: discoPub},
		Region:            []*tailcfg.DERPRegion{region301},
	}
	officialResolvedToken := string(resolvedCI.ConnBlob())

	// 3. Valid with future expiration and iat
	futureExpMap := map[string]any{
		"p":   nodePubBytes[:],
		"k":   discoPubBytes[:],
		"i":   uint64(302),
		"exp": uint64(253402300000),
		"iat": uint64(1700000000),
	}
	futureExpCBOR, _ := canonicalEncMode.Marshal(futureExpMap)
	futureExpToken := "tc" + base64.RawURLEncoding.EncodeToString(futureExpCBOR)

	// 4. Expired token (past exp timestamp)
	expiredMap := map[string]any{
		"p":   nodePubBytes[:],
		"k":   discoPubBytes[:],
		"i":   uint64(302),
		"exp": uint64(1000000000),
	}
	expiredCBOR, _ := canonicalEncMode.Marshal(expiredMap)
	expiredToken := "tc" + base64.RawURLEncoding.EncodeToString(expiredCBOR)

	// 5. Historical legacy numeric-r token
	legacyMap := map[string]any{
		"p": nodePubBytes[:],
		"r": uint64(302),
	}
	legacyCBOR, _ := canonicalEncMode.Marshal(legacyMap)
	legacyNumericRToken := "tc" + base64.RawURLEncoding.EncodeToString(legacyCBOR)

	// 6. Invalid: synthetic disco key (k == p)
	syntheticDiscoMap := map[string]any{
		"p": nodePubBytes[:],
		"k": nodePubBytes[:],
		"i": uint64(302),
	}
	syntheticDiscoCBOR, _ := canonicalEncMode.Marshal(syntheticDiscoMap)
	syntheticDiscoToken := "tc" + base64.RawURLEncoding.EncodeToString(syntheticDiscoCBOR)

	// 7. Invalid: all-zero disco key
	zeroDiscoMap := map[string]any{
		"p": nodePubBytes[:],
		"k": make([]byte, 32),
		"i": uint64(302),
	}
	zeroDiscoCBOR, _ := canonicalEncMode.Marshal(zeroDiscoMap)
	zeroDiscoToken := "tc" + base64.RawURLEncoding.EncodeToString(zeroDiscoCBOR)

	// 8. Invalid: missing disco key in short token (no k and not legacy r)
	missingDiscoMap := map[string]any{
		"p": nodePubBytes[:],
		"i": uint64(302),
	}
	missingDiscoCBOR, _ := canonicalEncMode.Marshal(missingDiscoMap)
	missingDiscoToken := "tc" + base64.RawURLEncoding.EncodeToString(missingDiscoCBOR)

	// 9. Invalid: missing node key p
	missingNodeMap := map[string]any{
		"k": discoPubBytes[:],
		"i": uint64(302),
	}
	missingNodeCBOR, _ := canonicalEncMode.Marshal(missingNodeMap)
	missingNodeToken := "tc" + base64.RawURLEncoding.EncodeToString(missingNodeCBOR)

	// 10. Invalid: short node key (16 bytes)
	shortNodeMap := map[string]any{
		"p": nodePubBytes[:16],
		"k": discoPubBytes[:],
		"i": uint64(302),
	}
	shortNodeCBOR, _ := canonicalEncMode.Marshal(shortNodeMap)
	shortNodeToken := "tc" + base64.RawURLEncoding.EncodeToString(shortNodeCBOR)

	// 11. Invalid: long node key (48 bytes)
	longNodeMap := map[string]any{
		"p": append(nodePubBytes[:], nodePubBytes[:16]...),
		"k": discoPubBytes[:],
		"i": uint64(302),
	}
	longNodeCBOR, _ := canonicalEncMode.Marshal(longNodeMap)
	longNodeToken := "tc" + base64.RawURLEncoding.EncodeToString(longNodeCBOR)

	// 12. Invalid: all-zero node key
	zeroNodeMap := map[string]any{
		"p": make([]byte, 32),
		"k": discoPubBytes[:],
		"i": uint64(302),
	}
	zeroNodeCBOR, _ := canonicalEncMode.Marshal(zeroNodeMap)
	zeroNodeToken := "tc" + base64.RawURLEncoding.EncodeToString(zeroNodeCBOR)

	// 13. Invalid: short disco key (16 bytes)
	shortDiscoMap := map[string]any{
		"p": nodePubBytes[:],
		"k": discoPubBytes[:16],
		"i": uint64(302),
	}
	shortDiscoCBOR, _ := canonicalEncMode.Marshal(shortDiscoMap)
	shortDiscoToken := "tc" + base64.RawURLEncoding.EncodeToString(shortDiscoCBOR)

	// 14. Invalid: long disco key (48 bytes)
	longDiscoMap := map[string]any{
		"p": nodePubBytes[:],
		"k": append(discoPubBytes[:], discoPubBytes[:16]...),
		"i": uint64(302),
	}
	longDiscoCBOR, _ := canonicalEncMode.Marshal(longDiscoMap)
	longDiscoToken := "tc" + base64.RawURLEncoding.EncodeToString(longDiscoCBOR)

	// 15. Invalid: duplicate exact "p"
	dupP_CBOR := []byte{0xa2, 0x61, 'p', 0x58, 0x20}
	dupP_CBOR = append(dupP_CBOR, nodePubBytes[:]...)
	dupP_CBOR = append(dupP_CBOR, 0x61, 'p', 0x58, 0x20)
	dupP_CBOR = append(dupP_CBOR, discoPubBytes[:]...)
	dupP_Token := "tc" + base64.RawURLEncoding.EncodeToString(dupP_CBOR)

	// 16. Invalid: duplicate exact "k"
	dupK_CBOR := []byte{0xa3, 0x61, 'p', 0x58, 0x20}
	dupK_CBOR = append(dupK_CBOR, nodePubBytes[:]...)
	dupK_CBOR = append(dupK_CBOR, 0x61, 'k', 0x58, 0x20)
	dupK_CBOR = append(dupK_CBOR, discoPubBytes[:]...)
	dupK_CBOR = append(dupK_CBOR, 0x61, 'k', 0x58, 0x20)
	dupK_CBOR = append(dupK_CBOR, discoPubBytes[:]...)
	dupK_Token := "tc" + base64.RawURLEncoding.EncodeToString(dupK_CBOR)

	// 17. Invalid: duplicate exact "i"
	dupI_CBOR := []byte{0xa4, 0x61, 'p', 0x58, 0x20}
	dupI_CBOR = append(dupI_CBOR, nodePubBytes[:]...)
	dupI_CBOR = append(dupI_CBOR, 0x61, 'k', 0x58, 0x20)
	dupI_CBOR = append(dupI_CBOR, discoPubBytes[:]...)
	dupI_CBOR = append(dupI_CBOR, 0x61, 'i', 0x19, 0x01, 0x2e)
	dupI_CBOR = append(dupI_CBOR, 0x61, 'i', 0x19, 0x01, 0x2e)
	dupI_Token := "tc" + base64.RawURLEncoding.EncodeToString(dupI_CBOR)

	// 18. Invalid: duplicate exact "r"
	dupR_CBOR := []byte{0xa3, 0x61, 'p', 0x58, 0x20}
	dupR_CBOR = append(dupR_CBOR, nodePubBytes[:]...)
	dupR_CBOR = append(dupR_CBOR, 0x61, 'r', 0x19, 0x01, 0x2e)
	dupR_CBOR = append(dupR_CBOR, 0x61, 'r', 0x19, 0x01, 0x2e)
	dupR_Token := "tc" + base64.RawURLEncoding.EncodeToString(dupR_CBOR)

	// 19. Invalid: duplicate exact "exp"
	dupExp_CBOR := []byte{0xa4, 0x61, 'p', 0x58, 0x20}
	dupExp_CBOR = append(dupExp_CBOR, nodePubBytes[:]...)
	dupExp_CBOR = append(dupExp_CBOR, 0x61, 'k', 0x58, 0x20)
	dupExp_CBOR = append(dupExp_CBOR, discoPubBytes[:]...)
	dupExp_CBOR = append(dupExp_CBOR, 0x63, 'e', 'x', 'p', 0x1a, 0x65, 0x54, 0x32, 0x10)
	dupExp_CBOR = append(dupExp_CBOR, 0x63, 'e', 'x', 'p', 0x1a, 0x65, 0x54, 0x32, 0x10)
	dupExp_Token := "tc" + base64.RawURLEncoding.EncodeToString(dupExp_CBOR)

	// 20. Invalid: duplicate exact "iat"
	dupIat_CBOR := []byte{0xa4, 0x61, 'p', 0x58, 0x20}
	dupIat_CBOR = append(dupIat_CBOR, nodePubBytes[:]...)
	dupIat_CBOR = append(dupIat_CBOR, 0x61, 'k', 0x58, 0x20)
	dupIat_CBOR = append(dupIat_CBOR, discoPubBytes[:]...)
	dupIat_CBOR = append(dupIat_CBOR, 0x63, 'i', 'a', 't', 0x1a, 0x65, 0x54, 0x32, 0x10)
	dupIat_CBOR = append(dupIat_CBOR, 0x63, 'i', 'a', 't', 0x1a, 0x65, 0x54, 0x32, 0x10)
	dupIat_Token := "tc" + base64.RawURLEncoding.EncodeToString(dupIat_CBOR)

	// 21. Invalid: alias-only field "pub"
	aliasOnlyPubMap := map[string]any{
		"pub": nodePubBytes[:],
		"k":   discoPubBytes[:],
		"i":   uint64(302),
	}
	aliasOnlyPubCBOR, _ := canonicalEncMode.Marshal(aliasOnlyPubMap)
	aliasOnlyPubToken := "tc" + base64.RawURLEncoding.EncodeToString(aliasOnlyPubCBOR)

	// 22. Invalid: alias-only field "disco"
	aliasOnlyDiscoMap := map[string]any{
		"p":     nodePubBytes[:],
		"disco": discoPubBytes[:],
		"i":     uint64(302),
	}
	aliasOnlyDiscoCBOR, _ := canonicalEncMode.Marshal(aliasOnlyDiscoMap)
	aliasOnlyDiscoToken := "tc" + base64.RawURLEncoding.EncodeToString(aliasOnlyDiscoCBOR)

	// 23. Invalid: mixed-case field "P"
	mixedCaseMap := map[string]any{
		"P": nodePubBytes[:],
		"k": discoPubBytes[:],
		"i": uint64(302),
	}
	mixedCaseCBOR, _ := canonicalEncMode.Marshal(mixedCaseMap)
	mixedCaseToken := "tc" + base64.RawURLEncoding.EncodeToString(mixedCaseCBOR)

	// 24. Invalid: padded Base64URL with '='
	paddedB64Token := "tc" + base64.URLEncoding.EncodeToString(futureExpCBOR)

	// 25. Invalid: standard Base64 '+' character
	badB64PlusToken := "tcABC+DEF"

	// 26. Invalid: standard Base64 '/' character
	badB64SlashToken := "tcABC/DEF"

	// 27. Invalid: leading whitespace
	leadingWsToken := " " + officialShortToken

	// 28. Invalid: trailing whitespace
	trailingWsToken := officialShortToken + " "

	// 29. Invalid: uppercase prefix "TC"
	uppercasePrefixToken := "TC" + base64.RawURLEncoding.EncodeToString(futureExpCBOR)

	// 30. Invalid: negative region ID (i = -1)
	negRegionCBOR := []byte{0xa3, 0x61, 'p', 0x58, 0x20}
	negRegionCBOR = append(negRegionCBOR, nodePubBytes[:]...)
	negRegionCBOR = append(negRegionCBOR, 0x61, 'k', 0x58, 0x20)
	negRegionCBOR = append(negRegionCBOR, discoPubBytes[:]...)
	negRegionCBOR = append(negRegionCBOR, 0x61, 'i', 0x20) // major 1, value 0 -> -1
	negRegionToken := "tc" + base64.RawURLEncoding.EncodeToString(negRegionCBOR)

	// 31. Invalid: zero region ID (i = 0)
	zeroRegionMap := map[string]any{
		"p": nodePubBytes[:],
		"k": discoPubBytes[:],
		"i": uint64(0),
	}
	zeroRegionCBOR, _ := canonicalEncMode.Marshal(zeroRegionMap)
	zeroRegionToken := "tc" + base64.RawURLEncoding.EncodeToString(zeroRegionCBOR)

	// 32. Invalid: overflow region ID (i = 70000)
	overflowRegionMap := map[string]any{
		"p": nodePubBytes[:],
		"k": discoPubBytes[:],
		"i": uint64(70000),
	}
	overflowRegionCBOR, _ := canonicalEncMode.Marshal(overflowRegionMap)
	overflowRegionToken := "tc" + base64.RawURLEncoding.EncodeToString(overflowRegionCBOR)

	// 33. Invalid: float region ID
	floatRegionCBOR := []byte{0xa3, 0x61, 'p', 0x58, 0x20}
	floatRegionCBOR = append(floatRegionCBOR, nodePubBytes[:]...)
	floatRegionCBOR = append(floatRegionCBOR, 0x61, 'k', 0x58, 0x20)
	floatRegionCBOR = append(floatRegionCBOR, discoPubBytes[:]...)
	floatRegionCBOR = append(floatRegionCBOR, 0x61, 'i', 0xfb, 0x40, 0x72, 0xe0, 0x00, 0x00, 0x00, 0x00, 0x00) // float 302.0
	floatRegionToken := "tc" + base64.RawURLEncoding.EncodeToString(floatRegionCBOR)

	// 34. Invalid: negative expiration
	negExpCBOR := []byte{0xa4, 0x61, 'p', 0x58, 0x20}
	negExpCBOR = append(negExpCBOR, nodePubBytes[:]...)
	negExpCBOR = append(negExpCBOR, 0x61, 'k', 0x58, 0x20)
	negExpCBOR = append(negExpCBOR, discoPubBytes[:]...)
	negExpCBOR = append(negExpCBOR, 0x61, 'i', 0x19, 0x01, 0x2e)
	negExpCBOR = append(negExpCBOR, 0x63, 'e', 'x', 'p', 0x20) // -1
	negExpToken := "tc" + base64.RawURLEncoding.EncodeToString(negExpCBOR)

	// 35. Invalid: zero expiration
	zeroExpMap := map[string]any{
		"p":   nodePubBytes[:],
		"k":   discoPubBytes[:],
		"i":   uint64(302),
		"exp": uint64(0),
	}
	zeroExpCBOR, _ := canonicalEncMode.Marshal(zeroExpMap)
	zeroExpToken := "tc" + base64.RawURLEncoding.EncodeToString(zeroExpCBOR)

	// 36. Invalid: overflow expiration
	overflowExpMap := map[string]any{
		"p":   nodePubBytes[:],
		"k":   discoPubBytes[:],
		"i":   uint64(302),
		"exp": uint64(253402300800),
	}
	overflowExpCBOR, _ := canonicalEncMode.Marshal(overflowExpMap)
	overflowExpToken := "tc" + base64.RawURLEncoding.EncodeToString(overflowExpCBOR)

	// 37. Invalid: float expiration
	floatExpCBOR := []byte{0xa4, 0x61, 'p', 0x58, 0x20}
	floatExpCBOR = append(floatExpCBOR, nodePubBytes[:]...)
	floatExpCBOR = append(floatExpCBOR, 0x61, 'k', 0x58, 0x20)
	floatExpCBOR = append(floatExpCBOR, discoPubBytes[:]...)
	floatExpCBOR = append(floatExpCBOR, 0x61, 'i', 0x19, 0x01, 0x2e)
	floatExpCBOR = append(floatExpCBOR, 0x63, 'e', 'x', 'p', 0xfb, 0x41, 0xd9, 0x54, 0x23, 0x00, 0x00, 0x00, 0x00)
	floatExpToken := "tc" + base64.RawURLEncoding.EncodeToString(floatExpCBOR)

	// 38. Invalid: exp < iat
	expBeforeIatMap := map[string]any{
		"p":   nodePubBytes[:],
		"k":   discoPubBytes[:],
		"i":   uint64(302),
		"exp": uint64(1600000000),
		"iat": uint64(1700000000),
	}
	expBeforeIatCBOR, _ := canonicalEncMode.Marshal(expBeforeIatMap)
	expBeforeIatToken := "tc" + base64.RawURLEncoding.EncodeToString(expBeforeIatCBOR)

	// 39. Invalid: trailing unparsed bytes
	trailingCBOR := append(append([]byte{}, futureExpCBOR...), 0x00, 0xff)
	trailingToken := "tc" + base64.RawURLEncoding.EncodeToString(trailingCBOR)

	// 40. Invalid: empty structured region array
	emptyRegionMap := map[string]any{
		"p": nodePubBytes[:],
		"k": discoPubBytes[:],
		"r": []any{},
	}
	emptyRegionCBOR, _ := canonicalEncMode.Marshal(emptyRegionMap)
	emptyRegionToken := "tc" + base64.RawURLEncoding.EncodeToString(emptyRegionCBOR)

	// 41. Invalid: malformed DERP node (missing hostname)
	malformedNodeMap := map[string]any{
		"p": nodePubBytes[:],
		"k": discoPubBytes[:],
		"r": []any{
			map[string]any{
				"i": uint64(301),
				"N": []any{
					map[string]any{
						"n": "301a",
					},
				},
			},
		},
	}
	malformedNodeCBOR, _ := canonicalEncMode.Marshal(malformedNodeMap)
	malformedNodeToken := "tc" + base64.RawURLEncoding.EncodeToString(malformedNodeCBOR)

	// 42. Invalid: indefinite-length CBOR map (0xbf)
	indefiniteCBOR := []byte{0xbf, 0x61, 'p', 0x58, 0x20}
	indefiniteCBOR = append(indefiniteCBOR, nodePubBytes[:]...)
	indefiniteCBOR = append(indefiniteCBOR, 0xff)
	indefiniteToken := "tc" + base64.RawURLEncoding.EncodeToString(indefiniteCBOR)

	// 43. Invalid: unknown field
	unknownFieldMap := map[string]any{
		"p":             nodePubBytes[:],
		"k":             discoPubBytes[:],
		"i":             uint64(302),
		"unknown_field": "disallowed",
	}
	unknownFieldCBOR, _ := canonicalEncMode.Marshal(unknownFieldMap)
	unknownFieldToken := "tc" + base64.RawURLEncoding.EncodeToString(unknownFieldCBOR)

	psk := make([]byte, 32)
	for i := 0; i < 32; i++ {
		psk[i] = byte(i + 65)
	}
	validPSKMap := map[string]any{
		"p": nodePubBytes[:],
		"k": discoPubBytes[:],
		"i": uint64(302),
		"q": psk,
	}
	validPSKCBOR, _ := canonicalEncMode.Marshal(validPSKMap)
	validPSKToken := "tc" + base64.RawURLEncoding.EncodeToString(validPSKCBOR)

	zeroPSKMap := map[string]any{
		"p": nodePubBytes[:],
		"k": discoPubBytes[:],
		"i": uint64(302),
		"q": make([]byte, 32),
	}
	zeroPSKCBOR, _ := canonicalEncMode.Marshal(zeroPSKMap)
	zeroPSKToken := "tc" + base64.RawURLEncoding.EncodeToString(zeroPSKCBOR)

	shortPSKMap := map[string]any{
		"p": nodePubBytes[:],
		"k": discoPubBytes[:],
		"i": uint64(302),
		"q": psk[:16],
	}
	shortPSKCBOR, _ := canonicalEncMode.Marshal(shortPSKMap)
	shortPSKToken := "tc" + base64.RawURLEncoding.EncodeToString(shortPSKCBOR)

	fixtures := []TokenFixture{
		{
			Name:                   "official_valid_short",
			Token:                  officialShortToken,
			Description:            "Official Tailcat v0.4.0 short token with deterministic p, k, and region i (302)",
			ExpectedClassification: "VALID_OFFICIAL_SHORT",
			ExpectedNodeKeyHex:     hex.EncodeToString(nodePubBytes[:]),
			ExpectedDiscoKeyHex:    hex.EncodeToString(discoPubBytes[:]),
			ExpectedRegionID:       302,
			HasEmbeddedRegion:      false,
		},
		{
			Name:                   "official_valid_resolved",
			Token:                  officialResolvedToken,
			Description:            "Official Tailcat v0.4.0 resolved token with deterministic p, k, and embedded region array r",
			ExpectedClassification: "VALID_OFFICIAL_RESOLVED",
			ExpectedNodeKeyHex:     hex.EncodeToString(nodePubBytes[:]),
			ExpectedDiscoKeyHex:    hex.EncodeToString(discoPubBytes[:]),
			ExpectedRegionID:       1,
			HasEmbeddedRegion:      true,
		},
		{
			Name:                   "valid_with_future_exp",
			Token:                  futureExpToken,
			Description:            "Valid official short token with valid iat and future exp",
			ExpectedClassification: "VALID_OFFICIAL_SHORT",
			ExpectedNodeKeyHex:     hex.EncodeToString(nodePubBytes[:]),
			ExpectedDiscoKeyHex:    hex.EncodeToString(discoPubBytes[:]),
			ExpectedRegionID:       302,
			HasEmbeddedRegion:      false,
		},
		{
			Name:                   "expired_token",
			Token:                  expiredToken,
			Description:            "Token with valid structure but expiration timestamp in the past",
			ExpectedClassification: "EXPIRED",
			ExpectedNodeKeyHex:     hex.EncodeToString(nodePubBytes[:]),
			ExpectedDiscoKeyHex:    hex.EncodeToString(discoPubBytes[:]),
			ExpectedRegionID:       302,
			HasEmbeddedRegion:      false,
		},
		{
			Name:                   "legacy_numeric_r",
			Token:                  legacyNumericRToken,
			Description:            "Historical legacy token with numeric r and missing disco key k",
			ExpectedClassification: "LEGACY_REISSUE_REQUIRED",
			ExpectedNodeKeyHex:     hex.EncodeToString(nodePubBytes[:]),
			ExpectedRegionID:       302,
			HasEmbeddedRegion:      false,
		},
		{
			Name:                   "invalid_synthetic_disco_key",
			Token:                  syntheticDiscoToken,
			Description:            "Invalid token where disco key k equals node key p",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_SYNTHETIC_DISCO_KEY",
		},
		{
			Name:                   "invalid_all_zero_disco_key",
			Token:                  zeroDiscoToken,
			Description:            "Invalid token with all-zero disco key k",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_DISCO_KEY",
		},
		{
			Name:                   "invalid_missing_disco_key_in_short",
			Token:                  missingDiscoToken,
			Description:            "Invalid short token missing disco key k",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_MISSING_DISCO_KEY",
		},
		{
			Name:                   "invalid_missing_node_key",
			Token:                  missingNodeToken,
			Description:            "Invalid token missing node key p",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_MISSING_NODE_KEY",
		},
		{
			Name:                   "invalid_short_node_key",
			Token:                  shortNodeToken,
			Description:            "Invalid token with 16-byte node key p",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_NODE_KEY",
		},
		{
			Name:                   "invalid_long_node_key",
			Token:                  longNodeToken,
			Description:            "Invalid token with 48-byte node key p",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_NODE_KEY",
		},
		{
			Name:                   "invalid_all_zero_node_key",
			Token:                  zeroNodeToken,
			Description:            "Invalid token with all-zero node key p",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_NODE_KEY",
		},
		{
			Name:                   "invalid_short_disco_key",
			Token:                  shortDiscoToken,
			Description:            "Invalid token with 16-byte disco key k",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_DISCO_KEY",
		},
		{
			Name:                   "invalid_long_disco_key",
			Token:                  longDiscoToken,
			Description:            "Invalid token with 48-byte disco key k",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_DISCO_KEY",
		},
		{
			Name:                   "invalid_duplicate_exact_p",
			Token:                  dupP_Token,
			Description:            "Invalid token with duplicate exact 'p' key",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_DUPLICATE_KEY",
		},
		{
			Name:                   "invalid_duplicate_exact_k",
			Token:                  dupK_Token,
			Description:            "Invalid token with duplicate exact 'k' key",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_DUPLICATE_KEY",
		},
		{
			Name:                   "invalid_duplicate_exact_i",
			Token:                  dupI_Token,
			Description:            "Invalid token with duplicate exact 'i' key",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_DUPLICATE_KEY",
		},
		{
			Name:                   "invalid_duplicate_exact_r",
			Token:                  dupR_Token,
			Description:            "Invalid token with duplicate exact 'r' key",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_DUPLICATE_KEY",
		},
		{
			Name:                   "invalid_duplicate_exact_exp",
			Token:                  dupExp_Token,
			Description:            "Invalid token with duplicate exact 'exp' key",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_DUPLICATE_KEY",
		},
		{
			Name:                   "invalid_duplicate_exact_iat",
			Token:                  dupIat_Token,
			Description:            "Invalid token with duplicate exact 'iat' key",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_DUPLICATE_KEY",
		},
		{
			Name:                   "invalid_alias_only_pub",
			Token:                  aliasOnlyPubToken,
			Description:            "Invalid token using alias-only key 'pub'",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_UNKNOWN_FIELD",
		},
		{
			Name:                   "invalid_alias_only_disco",
			Token:                  aliasOnlyDiscoToken,
			Description:            "Invalid token using alias-only key 'disco'",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_UNKNOWN_FIELD",
		},
		{
			Name:                   "invalid_mixed_case_key",
			Token:                  mixedCaseToken,
			Description:            "Invalid token using mixed-case key 'P'",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_UNKNOWN_FIELD",
		},
		{
			Name:                   "invalid_padded_base64",
			Token:                  paddedB64Token,
			Description:            "Invalid token with Base64URL '=' padding",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_BASE64_PADDED",
		},
		{
			Name:                   "invalid_base64_standard_plus",
			Token:                  badB64PlusToken,
			Description:            "Invalid token containing standard Base64 '+'",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_BASE64_CHAR",
		},
		{
			Name:                   "invalid_base64_standard_slash",
			Token:                  badB64SlashToken,
			Description:            "Invalid token containing standard Base64 '/'",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_BASE64_CHAR",
		},
		{
			Name:                   "invalid_leading_whitespace",
			Token:                  leadingWsToken,
			Description:            "Invalid token with leading whitespace",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_WHITESPACE",
		},
		{
			Name:                   "invalid_trailing_whitespace",
			Token:                  trailingWsToken,
			Description:            "Invalid token with trailing whitespace",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_WHITESPACE",
		},
		{
			Name:                   "invalid_uppercase_prefix",
			Token:                  uppercasePrefixToken,
			Description:            "Invalid token with uppercase 'TC' prefix",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_PREFIX",
		},
		{
			Name:                   "invalid_negative_region_id",
			Token:                  negRegionToken,
			Description:            "Invalid token with negative region ID",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_REGION_ID",
		},
		{
			Name:                   "invalid_zero_region_id",
			Token:                  zeroRegionToken,
			Description:            "Invalid token with region ID 0",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_REGION_ID",
		},
		{
			Name:                   "invalid_overflow_region_id",
			Token:                  overflowRegionToken,
			Description:            "Invalid token with region ID > 65535",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_REGION_ID",
		},
		{
			Name:                   "invalid_float_region_id",
			Token:                  floatRegionToken,
			Description:            "Invalid token with float-encoded region ID",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_CBOR_MALFORMED",
		},
		{
			Name:                   "invalid_negative_expiration",
			Token:                  negExpToken,
			Description:            "Invalid token with negative expiration",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_EXPIRATION",
		},
		{
			Name:                   "invalid_zero_expiration",
			Token:                  zeroExpToken,
			Description:            "Invalid token with expiration 0",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_EXPIRATION",
		},
		{
			Name:                   "invalid_overflow_expiration",
			Token:                  overflowExpToken,
			Description:            "Invalid token with expiration > MaxUnixTimestampSec",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_EXPIRATION",
		},
		{
			Name:                   "invalid_float_expiration",
			Token:                  floatExpToken,
			Description:            "Invalid token with float-encoded expiration",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_CBOR_MALFORMED",
		},
		{
			Name:                   "invalid_exp_before_iat",
			Token:                  expBeforeIatToken,
			Description:            "Invalid token with exp < iat",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_EXP_BEFORE_IAT",
		},
		{
			Name:                   "invalid_trailing_bytes",
			Token:                  trailingToken,
			Description:            "Invalid token with trailing unparsed bytes",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_TRAILING_DATA",
		},
		{
			Name:                   "invalid_empty_structured_r",
			Token:                  emptyRegionToken,
			Description:            "Invalid token with empty embedded region array",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_STRUCTURED_REGION",
		},
		{
			Name:                   "invalid_malformed_derp_node",
			Token:                  malformedNodeToken,
			Description:            "Invalid token with malformed DERP node",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_STRUCTURED_REGION",
		},
		{
			Name:                   "invalid_indefinite_length_cbor",
			Token:                  indefiniteToken,
			Description:            "Invalid token using indefinite-length CBOR",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_CBOR_MALFORMED",
		},
		{
			Name:                   "invalid_unknown_field",
			Token:                  unknownFieldToken,
			Description:            "Invalid token containing unknown field",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_UNKNOWN_FIELD",
		},
		{
			Name:                   "official_valid_short_with_psk",
			Token:                  validPSKToken,
			Description:            "Official short token with WireGuard pre-shared key q",
			ExpectedClassification: "VALID_OFFICIAL_SHORT",
			ExpectedNodeKeyHex:     hex.EncodeToString(nodePubBytes[:]),
			ExpectedDiscoKeyHex:    hex.EncodeToString(discoPubBytes[:]),
			ExpectedRegionID:       302,
			HasEmbeddedRegion:      false,
		},
		{
			Name:                   "invalid_all_zero_preshared_key",
			Token:                  zeroPSKToken,
			Description:            "Invalid token with all-zero preshared key q",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_PRESHARED_KEY",
		},
		{
			Name:                   "invalid_short_preshared_key",
			Token:                  shortPSKToken,
			Description:            "Invalid token with 16-byte preshared key q",
			ExpectedClassification: "INVALID",
			ExpectedErrorCode:      "ERR_INVALID_PRESHARED_KEY",
		},
	}

	data, err := json.MarshalIndent(fixtures, "", "  ")
	if err != nil {
		panic(err)
	}

	outPath := "core-engine/testdata/token_fixtures.json"
	if _, err := os.Stat("testdata"); err == nil {
		outPath = "testdata/token_fixtures.json"
	}
	if err := os.WriteFile(outPath, data, 0644); err != nil {
		panic(err)
	}
	fmt.Printf("Wrote %d canonical fixtures to %s\n", len(fixtures), outPath)
}
