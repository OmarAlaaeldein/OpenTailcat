package engine

import (
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/fxamacker/cbor/v2"
	"github.com/tailscale/tailcat"
	"go4.org/mem"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

const maxUnixTimestampSec = 253402300799

// ParsedToken represents a validated Tailcat connection token with metadata.
type ParsedToken struct {
	RawToken          string
	ServerPublic      key.NodePublic
	ServerDiscoPublic key.DiscoPublic
	RegionID          tailcfg.DERPRegionID
	HasEmbeddedRegion bool
	ExpiresAtUnixSec  *int64
	IssuedAtUnixSec   *int64
}

// IsExpired returns whether the token's expiration timestamp is in the past.
func (t *ParsedToken) IsExpired() bool {
	if t.ExpiresAtUnixSec == nil {
		return false
	}
	return time.Now().Unix() >= *t.ExpiresAtUnixSec
}

// ParseToken parses and validates a Tailcat token (tc-prefixed Base64URL-encoded CBOR).
// Supports official Tailcat v0.4.0 tokens (short with p, k, i and resolved with p, k, r)
// as well as legacy schemas with numeric r and optional exp/iat timestamps.
func ParseToken(raw string) (*ParsedToken, error) {
	trimmed := strings.TrimSpace(raw)
	if !strings.HasPrefix(trimmed, "tc") {
		return nil, errors.New("token must start with \"tc\" prefix")
	}

	payloadB64 := trimmed[2:]
	if payloadB64 == "" {
		return nil, errors.New("token payload cannot be empty")
	}

	cborBytes, err := base64.RawURLEncoding.DecodeString(payloadB64)
	if err != nil {
		return nil, fmt.Errorf("base64 decode: %w", err)
	}

	var rawMap map[string]any
	if err := cbor.Unmarshal(cborBytes, &rawMap); err != nil {
		return nil, fmt.Errorf("cbor decode map: %w", err)
	}

	var expSec *int64
	var iatSec *int64

	if expVal, ok := rawMap["exp"]; ok {
		switch v := expVal.(type) {
		case uint64:
			sec := int64(v)
			if sec < 1 || sec > maxUnixTimestampSec {
				return nil, errors.New("expiration timestamp out of range")
			}
			expSec = &sec
		case int64:
			if v < 1 || v > maxUnixTimestampSec {
				return nil, errors.New("expiration timestamp out of range")
			}
			expSec = &v
		case float64:
			sec := int64(v)
			if sec < 1 || sec > maxUnixTimestampSec {
				return nil, errors.New("expiration timestamp out of range")
			}
			expSec = &sec
		default:
			return nil, errors.New("invalid expiration format")
		}
	}

	if iatVal, ok := rawMap["iat"]; ok {
		switch v := iatVal.(type) {
		case uint64:
			sec := int64(v)
			if sec < 1 || sec > maxUnixTimestampSec {
				return nil, errors.New("issued-at timestamp out of range")
			}
			iatSec = &sec
		case int64:
			if v < 1 || v > maxUnixTimestampSec {
				return nil, errors.New("issued-at timestamp out of range")
			}
			iatSec = &v
		case float64:
			sec := int64(v)
			if sec < 1 || sec > maxUnixTimestampSec {
				return nil, errors.New("issued-at timestamp out of range")
			}
			iatSec = &sec
		default:
			return nil, errors.New("invalid issued-at format")
		}
	}

	if expSec != nil && iatSec != nil && *expSec < *iatSec {
		return nil, errors.New("expiration timestamp cannot be earlier than issued-at timestamp")
	}

	// Try parsing standard official tailcat ConnBlob
	ci, err := tailcat.ParseConnBlob(tailcat.ConnBlob(trimmed))
	if err != nil {
		// Fallback for legacy format with numeric "r"
		if rVal, ok := rawMap["r"]; ok {
			if rNum, ok := rVal.(uint64); ok && rNum > 0 {
				if pVal, ok := rawMap["p"]; ok {
					if pBytes, ok := pVal.([]byte); ok && len(pBytes) == 32 {
						pub := key.NodePublicFromRaw32(mem.B(pBytes))
						pt := &ParsedToken{
							RawToken:          trimmed,
							ServerPublic:      pub,
							RegionID:          tailcfg.DERPRegionID(rNum),
							HasEmbeddedRegion: false,
							ExpiresAtUnixSec:  expSec,
							IssuedAtUnixSec:   iatSec,
						}
						if pt.IsExpired() {
							return nil, errors.New("connection token has expired")
						}
						return pt, nil
					}
				}
			}
		}
		return nil, fmt.Errorf("parse connection token: %w", err)
	}

	hasEmbedded := len(ci.Region) > 0
	regionID := ci.RegionID
	if regionID == 0 && hasEmbedded && ci.Region[0] != nil {
		regionID = ci.Region[0].RegionID
	}

	pt := &ParsedToken{
		RawToken:          trimmed,
		ServerPublic:      ci.ServerPublic.NodePublic,
		ServerDiscoPublic: ci.ServerDiscoPublic.DiscoPublic,
		RegionID:          regionID,
		HasEmbeddedRegion: hasEmbedded,
		ExpiresAtUnixSec:  expSec,
		IssuedAtUnixSec:   iatSec,
	}

	if pt.IsExpired() {
		return nil, errors.New("connection token has expired")
	}

	return pt, nil
}
