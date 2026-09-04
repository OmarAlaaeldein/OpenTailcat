package engine

import (
	"bytes"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net/netip"
	"strings"
	"time"

	"github.com/tailscale/tailcat"
	"go4.org/mem"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

const (
	MaxTokenStringLength = 65536
	MaxCborPayloadBytes  = 32768
	MaxUnixTimestampSec  = 253402300799
	PublicKeySizeBytes   = 32
)

type TokenClassification string

const (
	ClassificationValidOfficialShort    TokenClassification = "VALID_OFFICIAL_SHORT"
	ClassificationValidOfficialResolved TokenClassification = "VALID_OFFICIAL_RESOLVED"
	ClassificationLegacyReissueRequired TokenClassification = "LEGACY_REISSUE_REQUIRED"
	ClassificationExpired               TokenClassification = "EXPIRED"
	ClassificationInvalid               TokenClassification = "INVALID"
)

type TokenErrorCode string

const (
	ErrNone                    TokenErrorCode = ""
	ErrTokenLength             TokenErrorCode = "ERR_TOKEN_LENGTH"
	ErrWhitespace              TokenErrorCode = "ERR_WHITESPACE"
	ErrInvalidPrefix           TokenErrorCode = "ERR_INVALID_PREFIX"
	ErrBase64Char              TokenErrorCode = "ERR_BASE64_CHAR"
	ErrBase64Padded            TokenErrorCode = "ERR_BASE64_PADDED"
	ErrBase64Decode            TokenErrorCode = "ERR_BASE64_DECODE"
	ErrCborTooLarge            TokenErrorCode = "ERR_CBOR_TOO_LARGE"
	ErrCborMalformed           TokenErrorCode = "ERR_CBOR_MALFORMED"
	ErrNotMap                  TokenErrorCode = "ERR_NOT_MAP"
	ErrDuplicateKey            TokenErrorCode = "ERR_DUPLICATE_KEY"
	ErrTrailingData            TokenErrorCode = "ERR_TRAILING_DATA"
	ErrMissingNodeKey          TokenErrorCode = "ERR_MISSING_NODE_KEY"
	ErrInvalidNodeKey          TokenErrorCode = "ERR_INVALID_NODE_KEY"
	ErrMissingDiscoKey         TokenErrorCode = "ERR_MISSING_DISCO_KEY"
	ErrInvalidDiscoKey         TokenErrorCode = "ERR_INVALID_DISCO_KEY"
	ErrSyntheticDiscoKey       TokenErrorCode = "ERR_SYNTHETIC_DISCO_KEY"
	ErrMissingRegion           TokenErrorCode = "ERR_MISSING_REGION"
	ErrInvalidRegionId         TokenErrorCode = "ERR_INVALID_REGION_ID"
	ErrInvalidRegionType       TokenErrorCode = "ERR_INVALID_REGION_TYPE"
	ErrInvalidStructuredRegion TokenErrorCode = "ERR_INVALID_STRUCTURED_REGION"
	ErrInvalidExpiration       TokenErrorCode = "ERR_INVALID_EXPIRATION"
	ErrInvalidIssuedAt         TokenErrorCode = "ERR_INVALID_ISSUED_AT"
	ErrExpBeforeIat            TokenErrorCode = "ERR_EXP_BEFORE_IAT"
	ErrUnknownField            TokenErrorCode = "ERR_UNKNOWN_FIELD"
	ErrInvalidPresharedKey     TokenErrorCode = "ERR_INVALID_PRESHARED_KEY"
)

// ParsedToken represents a validated Tailcat token with verified classification.
type ParsedToken struct {
	RawToken          string                `json:"rawToken"`
	Classification    TokenClassification   `json:"classification"`
	ErrorCode         TokenErrorCode        `json:"errorCode,omitempty"`
	ErrorMessage      string                `json:"errorMessage,omitempty"`
	ServerPublic      key.NodePublic        `json:"-"`
	ServerPublicHex   string                `json:"serverPublicHex,omitempty"`
	ServerDiscoPublic key.DiscoPublic       `json:"-"`
	ServerDiscoHex    string                `json:"serverDiscoHex,omitempty"`
	RegionID          tailcfg.DERPRegionID  `json:"regionId,omitempty"`
	Region            []*tailcfg.DERPRegion `json:"-"`
	HasEmbeddedRegion bool                  `json:"hasEmbeddedRegion"`
	ExpiresAtUnixSec  *int64                `json:"expiresAtUnixSec,omitempty"`
	IssuedAtUnixSec   *int64                `json:"issuedAtUnixSec,omitempty"`
}

func (t *ParsedToken) IsExpired() bool {
	if t.ExpiresAtUnixSec == nil {
		return false
	}
	return time.Now().Unix() >= *t.ExpiresAtUnixSec
}

func (t *ParsedToken) IsConnectable() bool {
	return (t.Classification == ClassificationValidOfficialShort ||
		t.Classification == ClassificationValidOfficialResolved) &&
		!t.IsExpired()
}

// ParseToken parses and classifies a Tailcat connection token using upstream Tailcat v0.4.0
// ParseConnBlob as the authority. No silent trimming or mutations are performed.
func ParseToken(raw string) (*ParsedToken, error) {
	if len(raw) == 0 {
		return &ParsedToken{
			RawToken:       raw,
			Classification: ClassificationInvalid,
			ErrorCode:      ErrTokenLength,
			ErrorMessage:   "token cannot be empty",
		}, errors.New("token cannot be empty")
	}

	if len(raw) > MaxTokenStringLength {
		return &ParsedToken{
			RawToken:       raw,
			Classification: ClassificationInvalid,
			ErrorCode:      ErrTokenLength,
			ErrorMessage:   "token exceeds maximum length",
		}, errors.New("token exceeds maximum length")
	}

	// Reject leading or trailing whitespace (no silent trimming)
	if raw[0] <= ' ' || raw[len(raw)-1] <= ' ' {
		return &ParsedToken{
			RawToken:       raw,
			Classification: ClassificationInvalid,
			ErrorCode:      ErrWhitespace,
			ErrorMessage:   "token must not contain leading or trailing whitespace",
		}, errors.New("token contains surrounding whitespace")
	}

	// Exact lowercase "tc" prefix required (reject uppercase "TC" or mixed case)
	if !strings.HasPrefix(raw, "tc") {
		return &ParsedToken{
			RawToken:       raw,
			Classification: ClassificationInvalid,
			ErrorCode:      ErrInvalidPrefix,
			ErrorMessage:   "token must start with exact lowercase \"tc\" prefix",
		}, errors.New("token must start with exact lowercase \"tc\" prefix")
	}

	b64Payload := raw[2:]
	if len(b64Payload) == 0 {
		return &ParsedToken{
			RawToken:       raw,
			Classification: ClassificationInvalid,
			ErrorCode:      ErrTokenLength,
			ErrorMessage:   "token payload cannot be empty",
		}, errors.New("token payload cannot be empty")
	}

	// Validate Base64URL characters and reject '=' padding (upstream uses base64.RawURLEncoding)
	for i := 0; i < len(b64Payload); i++ {
		c := b64Payload[i]
		if c == '=' {
			return &ParsedToken{
				RawToken:       raw,
				Classification: ClassificationInvalid,
				ErrorCode:      ErrBase64Padded,
				ErrorMessage:   "Base64URL padding '=' is forbidden; upstream requires unpadded base64.RawURLEncoding",
			}, errors.New("Base64URL padding is forbidden")
		}
		if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' {
			continue
		}
		return &ParsedToken{
			RawToken:       raw,
			Classification: ClassificationInvalid,
			ErrorCode:      ErrBase64Char,
			ErrorMessage:   fmt.Sprintf("invalid character %q in Base64URL token payload", c),
		}, fmt.Errorf("invalid character %q in Base64URL token payload", c)
	}

	if len(b64Payload)%4 == 1 {
		return &ParsedToken{
			RawToken:       raw,
			Classification: ClassificationInvalid,
			ErrorCode:      ErrBase64Decode,
			ErrorMessage:   "invalid unpadded Base64URL length (mod 4 is 1)",
		}, errors.New("invalid unpadded Base64URL length (mod 4 is 1)")
	}

	cborBytes, err := base64.RawURLEncoding.DecodeString(b64Payload)
	if err != nil {
		return &ParsedToken{
			RawToken:       raw,
			Classification: ClassificationInvalid,
			ErrorCode:      ErrBase64Decode,
			ErrorMessage:   fmt.Sprintf("base64 decode: %v", err),
		}, fmt.Errorf("base64 decode: %w", err)
	}

	rawMap, code, err := parseStrictTokenMap(cborBytes)
	if err != nil {
		return &ParsedToken{
			RawToken:       raw,
			Classification: ClassificationInvalid,
			ErrorCode:      code,
			ErrorMessage:   err.Error(),
		}, err
	}

	var (
		pubKeyBytes         []byte
		discoKeyBytes       []byte
		regionIDNum         int64
		hasExplicitRegionID bool
		hasNumericR         bool
		structuredRegions   []*tailcfg.DERPRegion
		hasStructuredR      bool
		expSec              *int64
		iatSec              *int64
	)

	// Extract and validate each permitted canonical field
	for k, v := range rawMap {
		switch k {
		case "p":
			b, ok := v.([]byte)
			if !ok || len(b) != PublicKeySizeBytes {
				return &ParsedToken{
					RawToken:       raw,
					Classification: ClassificationInvalid,
					ErrorCode:      ErrInvalidNodeKey,
					ErrorMessage:   fmt.Sprintf("node public key 'p' must be exactly %d bytes", PublicKeySizeBytes),
				}, errors.New("invalid node public key")
			}
			if isAllZero(b) {
				return &ParsedToken{
					RawToken:       raw,
					Classification: ClassificationInvalid,
					ErrorCode:      ErrInvalidNodeKey,
					ErrorMessage:   "node public key cannot be all zero bytes",
				}, errors.New("node public key cannot be all zero")
			}
			pubKeyBytes = b

		case "k":
			b, ok := v.([]byte)
			if !ok || len(b) != PublicKeySizeBytes {
				return &ParsedToken{
					RawToken:       raw,
					Classification: ClassificationInvalid,
					ErrorCode:      ErrInvalidDiscoKey,
					ErrorMessage:   fmt.Sprintf("disco public key 'k' must be exactly %d bytes", PublicKeySizeBytes),
				}, errors.New("invalid disco public key")
			}
			if isAllZero(b) {
				return &ParsedToken{
					RawToken:       raw,
					Classification: ClassificationInvalid,
					ErrorCode:      ErrInvalidDiscoKey,
					ErrorMessage:   "disco public key cannot be all zero bytes",
				}, errors.New("disco public key cannot be all zero")
			}
			discoKeyBytes = b

		case "q":
			b, ok := v.([]byte)
			if !ok || len(b) != PublicKeySizeBytes {
				return &ParsedToken{
					RawToken:       raw,
					Classification: ClassificationInvalid,
					ErrorCode:      ErrInvalidPresharedKey,
					ErrorMessage:   fmt.Sprintf("preshared key 'q' must be exactly %d bytes", PublicKeySizeBytes),
				}, errors.New("invalid preshared key")
			}
			if isAllZero(b) {
				return &ParsedToken{
					RawToken:       raw,
					Classification: ClassificationInvalid,
					ErrorCode:      ErrInvalidPresharedKey,
					ErrorMessage:   "preshared key cannot be all zero bytes",
				}, errors.New("preshared key cannot be all zero")
			}

		case "i":
			switch num := v.(type) {
			case uint64:
				if num < 1 || num > 65535 {
					return &ParsedToken{
						RawToken:       raw,
						Classification: ClassificationInvalid,
						ErrorCode:      ErrInvalidRegionId,
						ErrorMessage:   "region ID 'i' must be in range 1..65535",
					}, errors.New("region ID out of range")
				}
				regionIDNum = int64(num)
				hasExplicitRegionID = true
			case int64:
				if num < 1 || num > 65535 {
					return &ParsedToken{
						RawToken:       raw,
						Classification: ClassificationInvalid,
						ErrorCode:      ErrInvalidRegionId,
						ErrorMessage:   "region ID 'i' must be in range 1..65535",
					}, errors.New("region ID out of range")
				}
				regionIDNum = num
				hasExplicitRegionID = true
			default:
				return &ParsedToken{
					RawToken:       raw,
					Classification: ClassificationInvalid,
					ErrorCode:      ErrInvalidRegionId,
					ErrorMessage:   "region ID 'i' must be a positive integer",
				}, errors.New("invalid region ID type")
			}

		case "r":
			switch rVal := v.(type) {
			case uint64:
				if rVal < 1 || rVal > 65535 {
					return &ParsedToken{
						RawToken:       raw,
						Classification: ClassificationInvalid,
						ErrorCode:      ErrInvalidRegionId,
						ErrorMessage:   "numeric legacy region 'r' out of range",
					}, errors.New("numeric region out of range")
				}
				regionIDNum = int64(rVal)
				hasNumericR = true
			case int64:
				if rVal < 1 || rVal > 65535 {
					return &ParsedToken{
						RawToken:       raw,
						Classification: ClassificationInvalid,
						ErrorCode:      ErrInvalidRegionId,
						ErrorMessage:   "numeric legacy region 'r' out of range",
					}, errors.New("numeric region out of range")
				}
				regionIDNum = rVal
				hasNumericR = true
			case []any:
				if len(rVal) == 0 {
					return &ParsedToken{
						RawToken:       raw,
						Classification: ClassificationInvalid,
						ErrorCode:      ErrInvalidStructuredRegion,
						ErrorMessage:   "embedded region array 'r' cannot be empty",
					}, errors.New("empty embedded region array")
				}
				regions, err := parseStructuredDERPRegions(rVal)
				if err != nil {
					return &ParsedToken{
						RawToken:       raw,
						Classification: ClassificationInvalid,
						ErrorCode:      ErrInvalidStructuredRegion,
						ErrorMessage:   err.Error(),
					}, err
				}
				structuredRegions = regions
				hasStructuredR = true
			default:
				return &ParsedToken{
					RawToken:       raw,
					Classification: ClassificationInvalid,
					ErrorCode:      ErrInvalidRegionType,
					ErrorMessage:   "region 'r' must be an integer (legacy) or array of DERP regions",
				}, errors.New("invalid region field type")
			}

		case "exp":
			switch num := v.(type) {
			case uint64:
				if num < 1 || num > MaxUnixTimestampSec {
					return &ParsedToken{
						RawToken:       raw,
						Classification: ClassificationInvalid,
						ErrorCode:      ErrInvalidExpiration,
						ErrorMessage:   "expiration timestamp out of range",
					}, errors.New("expiration timestamp out of range")
				}
				sec := int64(num)
				expSec = &sec
			case int64:
				if num < 1 || num > MaxUnixTimestampSec {
					return &ParsedToken{
						RawToken:       raw,
						Classification: ClassificationInvalid,
						ErrorCode:      ErrInvalidExpiration,
						ErrorMessage:   "expiration timestamp out of range",
					}, errors.New("expiration timestamp out of range")
				}
				sec := num
				expSec = &sec
			default:
				return &ParsedToken{
					RawToken:       raw,
					Classification: ClassificationInvalid,
					ErrorCode:      ErrInvalidExpiration,
					ErrorMessage:   "expiration timestamp must be an integer",
				}, errors.New("invalid expiration format")
			}

		case "iat":
			switch num := v.(type) {
			case uint64:
				if num < 1 || num > MaxUnixTimestampSec {
					return &ParsedToken{
						RawToken:       raw,
						Classification: ClassificationInvalid,
						ErrorCode:      ErrInvalidIssuedAt,
						ErrorMessage:   "issued-at timestamp out of range",
					}, errors.New("issued-at timestamp out of range")
				}
				sec := int64(num)
				iatSec = &sec
			case int64:
				if num < 1 || num > MaxUnixTimestampSec {
					return &ParsedToken{
						RawToken:       raw,
						Classification: ClassificationInvalid,
						ErrorCode:      ErrInvalidIssuedAt,
						ErrorMessage:   "issued-at timestamp out of range",
					}, errors.New("issued-at timestamp out of range")
				}
				sec := num
				iatSec = &sec
			default:
				return &ParsedToken{
					RawToken:       raw,
					Classification: ClassificationInvalid,
					ErrorCode:      ErrInvalidIssuedAt,
					ErrorMessage:   "issued-at timestamp must be an integer",
				}, errors.New("invalid issued-at format")
			}

		default:
			return &ParsedToken{
				RawToken:       raw,
				Classification: ClassificationInvalid,
				ErrorCode:      ErrUnknownField,
				ErrorMessage:   fmt.Sprintf("unknown token field %q", k),
			}, fmt.Errorf("unknown token field %q", k)
		}
	}

	if pubKeyBytes == nil {
		return &ParsedToken{
			RawToken:       raw,
			Classification: ClassificationInvalid,
			ErrorCode:      ErrMissingNodeKey,
			ErrorMessage:   "missing required node public key 'p'",
		}, errors.New("missing required node public key")
	}

	if expSec != nil && iatSec != nil && *expSec < *iatSec {
		return &ParsedToken{
			RawToken:       raw,
			Classification: ClassificationInvalid,
			ErrorCode:      ErrExpBeforeIat,
			ErrorMessage:   "expiration timestamp cannot be earlier than issued-at timestamp",
		}, errors.New("expiration timestamp earlier than issued-at")
	}

	// Reject synthetic disco key where k == p
	if discoKeyBytes != nil && bytes.Equal(discoKeyBytes, pubKeyBytes) {
		return &ParsedToken{
			RawToken:       raw,
			Classification: ClassificationInvalid,
			ErrorCode:      ErrSyntheticDiscoKey,
			ErrorMessage:   "synthetic disco key equal to node public key is forbidden",
		}, errors.New("synthetic disco key equal to node public key")
	}

	nodePub := key.NodePublicFromRaw32(mem.B(pubKeyBytes))
	var discoPub key.DiscoPublic
	if discoKeyBytes != nil {
		discoPub = key.DiscoPublicFromRaw32(mem.B(discoKeyBytes))
	}

	effectiveRegionID := regionIDNum
	if effectiveRegionID == 0 && len(structuredRegions) > 0 && structuredRegions[0] != nil {
		effectiveRegionID = int64(structuredRegions[0].RegionID)
	}

	pt := &ParsedToken{
		RawToken:          raw,
		ServerPublic:      nodePub,
		ServerPublicHex:   hex.EncodeToString(pubKeyBytes),
		ServerDiscoPublic: discoPub,
		RegionID:          tailcfg.DERPRegionID(effectiveRegionID),
		Region:            structuredRegions,
		HasEmbeddedRegion: hasStructuredR,
		ExpiresAtUnixSec:  expSec,
		IssuedAtUnixSec:   iatSec,
	}
	if discoKeyBytes != nil {
		pt.ServerDiscoHex = hex.EncodeToString(discoKeyBytes)
	}

	// 1. Check expiration
	if pt.IsExpired() {
		pt.Classification = ClassificationExpired
		pt.ErrorMessage = "connection token has expired"
		return pt, errors.New("connection token has expired")
	}

	// 2. Check legacy numeric-r token schema
	if hasNumericR {
		if discoKeyBytes != nil {
			pt.Classification = ClassificationInvalid
			pt.ErrorCode = ErrInvalidRegionType
			pt.ErrorMessage = "official tokens with disco key must use 'i' or structured 'r', not numeric 'r'"
			return pt, errors.New("official token cannot use numeric r")
		}
		pt.Classification = ClassificationLegacyReissueRequired
		pt.ErrorMessage = "legacy numeric-r token schema lacks separate disco key; reissue required"
		return pt, errors.New("legacy token requires reissue")
	}

	// 3. Official token requirements: must have separate disco key 'k'
	if discoKeyBytes == nil {
		pt.Classification = ClassificationInvalid
		pt.ErrorCode = ErrMissingDiscoKey
		pt.ErrorMessage = "official token missing required disco key 'k'"
		return pt, errors.New("missing required disco key 'k'")
	}

	// 4. Region presence check
	if !hasExplicitRegionID && !hasStructuredR {
		pt.Classification = ClassificationInvalid
		pt.ErrorCode = ErrMissingRegion
		pt.ErrorMessage = "missing region specification (neither 'i' nor valid 'r' present)"
		return pt, errors.New("missing region specification")
	}

	// 5. Official short vs resolved classification
	if hasStructuredR {
		pt.Classification = ClassificationValidOfficialResolved
	} else {
		pt.Classification = ClassificationValidOfficialShort
	}

	// 6. Verify that upstream tailcat.ParseConnBlob accepts this exact unmutated token
	ci, err := tailcat.ParseConnBlob(tailcat.ConnBlob(raw))
	if err != nil {
		pt.Classification = ClassificationInvalid
		pt.ErrorCode = ErrCborMalformed
		pt.ErrorMessage = fmt.Sprintf("upstream tailcat parser rejected token: %v", err)
		return pt, fmt.Errorf("upstream tailcat parser rejected token: %w", err)
	}
	if err := rejectUnsafeDERPNodes(ci.Region); err != nil {
		pt.Classification = ClassificationInvalid
		pt.ErrorCode = ErrInvalidStructuredRegion
		pt.ErrorMessage = err.Error()
		return pt, err
	}

	return pt, nil
}

func rejectUnsafeDERPNodes(regions []*tailcfg.DERPRegion) error {
	for _, r := range regions {
		if r == nil {
			continue
		}
		for _, n := range r.Nodes {
			if n == nil {
				continue
			}
			if n.InsecureForTests {
				return errors.New("embedded DERP node InsecureForTests is forbidden")
			}
			if err := rejectUnsafeDERPEndpoint("hostname", n.HostName); err != nil {
				return err
			}
			if err := rejectUnsafeDERPEndpoint("IPv4", n.IPv4); err != nil {
				return err
			}
			if err := rejectUnsafeDERPEndpoint("IPv6", n.IPv6); err != nil {
				return err
			}
		}
	}
	return nil
}

func rejectUnsafeDERPEndpoint(label, value string) error {
	if value == "" || value == "none" {
		return nil
	}
	lower := strings.ToLower(value)
	if lower == "localhost" || strings.HasSuffix(lower, ".localhost") || lower == "ip6-localhost" {
		return fmt.Errorf("embedded DERP %s is a loopback name", label)
	}
	addr, err := netip.ParseAddr(value)
	if err != nil {
		return nil
	}
	switch {
	case addr.IsLoopback():
		return fmt.Errorf("embedded DERP %s is a loopback address", label)
	case !addr.IsValid() || addr.IsUnspecified():
		return fmt.Errorf("embedded DERP %s is unspecified", label)
	case addr.IsLinkLocalUnicast(), addr.IsLinkLocalMulticast():
		return fmt.Errorf("embedded DERP %s is a link-local address", label)
	case addr.IsMulticast():
		return fmt.Errorf("embedded DERP %s is a multicast address", label)
	}
	return nil
}

var allowedEmbeddedRegionFields = map[string]bool{
	"i": true,
	"c": true,
	"m": true,
	"N": true,
}

var allowedEmbeddedNodeFields = map[string]bool{
	"n": true,
	"i": true,
	"h": true,
	"t": true,
	"4": true,
	"6": true,
	"s": true,
	"d": true,
}

func parseStructuredDERPRegions(rawList []any) ([]*tailcfg.DERPRegion, error) {
	var regions []*tailcfg.DERPRegion
	for ri, item := range rawList {
		m, ok := item.(map[string]any)
		if !ok {
			return nil, errors.New("embedded region entry must be a map")
		}
		for k := range m {
			if !allowedEmbeddedRegionFields[k] {
				return nil, fmt.Errorf("embedded region has unknown field %q", k)
			}
		}
		var rID int64
		if idVal, ok := m["i"]; ok {
			switch v := idVal.(type) {
			case uint64:
				rID = int64(v)
			case int64:
				rID = v
			}
		}
		if rID == 0 {
			rID = int64(ri + 1)
		}
		if rID < 1 || rID > 65535 {
			return nil, fmt.Errorf("embedded region has invalid region ID: %d", rID)
		}

		reg := &tailcfg.DERPRegion{
			RegionID: tailcfg.DERPRegionID(rID),
		}
		if codeVal, ok := m["c"]; ok {
			if s, ok := codeVal.(string); ok {
				reg.RegionCode = s
			}
		}
		if reg.RegionCode == "" {
			reg.RegionCode = fmt.Sprint(reg.RegionID)
		}
		if nameVal, ok := m["m"]; ok {
			if s, ok := nameVal.(string); ok {
				reg.RegionName = s
			}
		}

		if nodesVal, ok := m["N"]; ok {
			nodeList, ok := nodesVal.([]any)
			if ok {
				if len(nodeList) == 0 {
					return nil, errors.New("embedded region has empty nodes array")
				}
				for _, nItem := range nodeList {
					nM, ok := nItem.(map[string]any)
					if !ok {
						return nil, errors.New("embedded DERP node must be a map")
					}
					if _, hasX := nM["x"]; hasX {
						return nil, errors.New("embedded DERP node InsecureForTests is forbidden")
					}
					for k := range nM {
						if !allowedEmbeddedNodeFields[k] {
							return nil, fmt.Errorf("embedded DERP node has unknown field %q", k)
						}
					}
					node := &tailcfg.DERPNode{
						RegionID: reg.RegionID,
					}
					if nName, ok := nM["n"].(string); ok {
						node.Name = nName
					}
					if nHost, ok := nM["h"].(string); ok {
						node.HostName = nHost
					}
					if node.HostName == "" {
						return nil, errors.New("embedded DERP node missing required HostName 'h'")
					}
					if err := rejectUnsafeDERPEndpoint("hostname", node.HostName); err != nil {
						return nil, err
					}
					if node.Name == "" {
						node.Name = node.HostName
					}
					if n4, ok := nM["4"].(string); ok {
						node.IPv4 = n4
					}
					if err := rejectUnsafeDERPEndpoint("IPv4", node.IPv4); err != nil {
						return nil, err
					}
					if n6, ok := nM["6"].(string); ok {
						node.IPv6 = n6
					}
					if err := rejectUnsafeDERPEndpoint("IPv6", node.IPv6); err != nil {
						return nil, err
					}
					reg.Nodes = append(reg.Nodes, node)
				}
			}
		}

		regions = append(regions, reg)
	}
	return regions, nil
}

func isAllZero(b []byte) bool {
	for _, x := range b {
		if x != 0 {
			return false
		}
	}
	return true
}
