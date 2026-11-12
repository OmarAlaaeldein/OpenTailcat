package engine

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"math/rand/v2"
	"os"
	"strings"
	"testing"
	"time"
)

// The differential corpus pins Go's verdict on thousands of deterministic
// mutations of the shared token fixtures. The Kotlin suite
// (TokenDifferentialTest) parses the same file and must reach the same
// classification, error code, keys, and region, so the two parsers cannot
// silently drift apart on inputs nobody wrote a fixture for.
//
// Regenerate after an intentional parser change, then run both suites:
//
//	OTC_UPDATE_FIXTURES=1 go test -run TestTokenDifferentialCorpus .

const (
	differentialCorpusPath = "testdata/token_differential.json"
	differentialPerSeed    = 30
)

type differentialCase struct {
	Token          string `json:"token"`
	Classification string `json:"classification"`
	ErrorCode      string `json:"errorCode,omitempty"`
	NodeKeyHex     string `json:"nodeKeyHex,omitempty"`
	DiscoKeyHex    string `json:"discoKeyHex,omitempty"`
	RegionID       int    `json:"regionId,omitempty"`
}

const b64URLAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

func mutateToken(rng *rand.Rand, tok string) string {
	payload := strings.TrimPrefix(tok, "tc")
	cbor, err := base64.RawURLEncoding.DecodeString(payload)
	if err != nil || len(cbor) == 0 || rng.IntN(5) == 0 {
		// String-level mutation of the Base64URL text.
		b := []byte(tok)
		if len(b) <= 2 {
			return tok + string(b64URLAlphabet[rng.IntN(len(b64URLAlphabet))])
		}
		i := 2 + rng.IntN(len(b)-2)
		switch rng.IntN(3) {
		case 0:
			b[i] = b64URLAlphabet[rng.IntN(len(b64URLAlphabet))]
		case 1:
			b = append(b[:i], b[i+1:]...)
		default:
			b = append(b[:i], append([]byte{b64URLAlphabet[rng.IntN(len(b64URLAlphabet))]}, b[i:]...)...)
		}
		return string(b)
	}
	c := append([]byte(nil), cbor...)
	n := 1 + rng.IntN(3)
	for range n {
		if len(c) == 0 {
			c = append(c, byte(rng.IntN(256)))
			continue
		}
		i := rng.IntN(len(c))
		switch rng.IntN(8) {
		case 0: // bit flip
			c[i] ^= 1 << rng.IntN(8)
		case 1: // random byte
			c[i] = byte(rng.IntN(256))
		case 2: // delete
			c = append(c[:i], c[i+1:]...)
		case 3: // insert
			c = append(c[:i], append([]byte{byte(rng.IntN(256))}, c[i:]...)...)
		case 4: // truncate
			c = c[:i]
		case 5: // append trailing bytes
			c = append(c, byte(rng.IntN(256)))
		case 6: // CBOR header byte of interest
			heads := []byte{0xa0, 0xa1, 0xbf, 0xff, 0x40, 0x58, 0x60, 0x78, 0x18, 0x19, 0x1a, 0x1b, 0x3a, 0xf9, 0xfa, 0xfb, 0xf4, 0xf5, 0xf6, 0xc1}
			c[i] = heads[rng.IntN(len(heads))]
		default: // duplicate a slice (repeats keys/values)
			j := i + rng.IntN(len(c)-i)
			c = append(c[:j], append(append([]byte(nil), c[i:j]...), c[j:]...)...)
		}
	}
	return "tc" + base64.RawURLEncoding.EncodeToString(c)
}

func buildDifferentialCorpus(t *testing.T) []differentialCase {
	t.Helper()
	data, err := os.ReadFile("testdata/token_fixtures.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixtures []struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(data, &fixtures); err != nil {
		t.Fatal(err)
	}
	rng := rand.New(rand.NewPCG(0x07a1ca7, 0xd1ff))
	now := time.Now().Unix()
	seen := map[string]bool{}
	var out []differentialCase
	for _, f := range fixtures {
		for range differentialPerSeed {
			tok := mutateToken(rng, f.Token)
			if seen[tok] || len(tok) > 4096 {
				continue
			}
			seen[tok] = true
			pt, _ := ParseToken(tok)
			// A token whose expiry is still ahead would change verdict
			// when it passes; keep the corpus time-independent.
			if pt.ExpiresAtUnixSec != nil && *pt.ExpiresAtUnixSec > now {
				continue
			}
			c := differentialCase{
				Token:          tok,
				Classification: string(pt.Classification),
				ErrorCode:      string(pt.ErrorCode),
			}
			if pt.Classification == ClassificationValidOfficialShort ||
				pt.Classification == ClassificationValidOfficialResolved {
				c.NodeKeyHex = pt.ServerPublicHex
				c.DiscoKeyHex = pt.ServerDiscoHex
				c.RegionID = int(pt.RegionID)
			}
			out = append(out, c)
		}
	}
	return out
}

func encodeDifferentialCorpus(cases []differentialCase) []byte {
	var buf bytes.Buffer
	buf.WriteString("[\n")
	for i, c := range cases {
		line, _ := json.Marshal(c)
		buf.Write(line)
		if i < len(cases)-1 {
			buf.WriteByte(',')
		}
		buf.WriteByte('\n')
	}
	buf.WriteString("]\n")
	return buf.Bytes()
}

func TestTokenDifferentialCorpus(t *testing.T) {
	cases := buildDifferentialCorpus(t)
	encoded := encodeDifferentialCorpus(cases)
	if os.Getenv("OTC_UPDATE_FIXTURES") == "1" {
		if err := os.WriteFile(differentialCorpusPath, encoded, 0o644); err != nil {
			t.Fatal(err)
		}
		counts := map[string]int{}
		for _, c := range cases {
			counts[c.Classification]++
		}
		t.Logf("wrote %d cases: %v", len(cases), counts)
		return
	}
	committed, err := os.ReadFile(differentialCorpusPath)
	if err != nil {
		t.Fatalf("read %s: %v (regenerate with OTC_UPDATE_FIXTURES=1)", differentialCorpusPath, err)
	}
	if !bytes.Equal(committed, encoded) {
		var old []differentialCase
		_ = json.Unmarshal(committed, &old)
		byToken := map[string]differentialCase{}
		for _, c := range old {
			byToken[c.Token] = c
		}
		shown := 0
		for _, c := range cases {
			if o, ok := byToken[c.Token]; ok && o != c && shown < 10 {
				t.Errorf("verdict changed for %s: committed %+v, now %+v", c.Token, o, c)
				shown++
			}
		}
		t.Fatalf("%s no longer matches the Go parser (%d committed, %d generated); "+
			"if the change is intended, regenerate with OTC_UPDATE_FIXTURES=1 and run the Kotlin TokenDifferentialTest",
			differentialCorpusPath, len(old), len(cases))
	}
}
