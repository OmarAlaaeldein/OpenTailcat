package engine

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"strings"
	"sync/atomic"
	"testing"

	"tailscale.com/tailcfg"
)

// Fuzz targets for every parser that reads attacker- or network-controlled
// bytes. `go test ./...` runs only the seed corpus (fixtures plus
// testdata/fuzz); run a target for longer with, for example:
//
//	go test -run '^$' -fuzz '^FuzzParseToken$' -fuzztime 60s .
//
// Each target checks properties, not just "no panic": a recovered panic in
// an exported function is reported as an error, so it is detected explicitly.

func seedTokenFixtures(f *testing.F) {
	f.Helper()
	data, err := os.ReadFile("testdata/token_fixtures.json")
	if err != nil {
		f.Fatalf("read token fixtures: %v", err)
	}
	var cases []struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(data, &cases); err != nil {
		f.Fatalf("parse token fixtures: %v", err)
	}
	for _, c := range cases {
		f.Add(c.Token)
	}
}

func FuzzParseToken(f *testing.F) {
	seedTokenFixtures(f)
	f.Add("")
	f.Add("tc")
	f.Add("tc" + strings.Repeat("A", 100))

	f.Fuzz(func(t *testing.T, raw string) {
		pt, err := ParseToken(raw)
		if err != nil && strings.Contains(err.Error(), "panic") {
			t.Fatalf("ParseToken panicked: %v", err)
		}
		if pt == nil {
			t.Fatalf("ParseToken returned nil token (err=%v)", err)
		}

		switch pt.Classification {
		case ClassificationValidOfficialShort, ClassificationValidOfficialResolved:
			if err != nil {
				t.Fatalf("valid classification with error %v", err)
			}
			if pt.ErrorCode != ErrNone {
				t.Fatalf("valid classification with error code %s", pt.ErrorCode)
			}
			if !strings.HasPrefix(raw, "tc") {
				t.Fatalf("accepted token without tc prefix")
			}
			if strings.IndexFunc(raw, func(r rune) bool { return r <= ' ' || r == '=' }) >= 0 {
				t.Fatalf("accepted token with whitespace, control byte, or padding")
			}
			node, nerr := hex.DecodeString(pt.ServerPublicHex)
			disco, derr := hex.DecodeString(pt.ServerDiscoHex)
			if nerr != nil || derr != nil || len(node) != PublicKeySizeBytes || len(disco) != PublicKeySizeBytes {
				t.Fatalf("accepted token without 32-byte node/disco keys: %q %q", pt.ServerPublicHex, pt.ServerDiscoHex)
			}
			if isAllZero(node) || isAllZero(disco) {
				t.Fatalf("accepted token with an all-zero key")
			}
			if pt.ServerPublicHex == pt.ServerDiscoHex {
				t.Fatalf("accepted token whose disco key equals its node key")
			}
			if pt.Classification == ClassificationValidOfficialResolved {
				if !pt.HasEmbeddedRegion || len(pt.Region) == 0 {
					t.Fatalf("resolved token without embedded region")
				}
				if err := rejectUnsafeDERPNodes(pt.Region); err != nil {
					t.Fatalf("accepted unsafe embedded DERP node: %v", err)
				}
				for _, r := range pt.Region {
					for _, n := range r.Nodes {
						if n.InsecureForTests {
							t.Fatalf("accepted InsecureForTests DERP node")
						}
					}
				}
			} else if pt.HasEmbeddedRegion {
				t.Fatalf("short token reports an embedded region")
			}
			if pt.ExpiresAtUnixSec != nil && pt.IssuedAtUnixSec != nil &&
				*pt.ExpiresAtUnixSec < *pt.IssuedAtUnixSec {
				t.Fatalf("accepted exp < iat")
			}
		case ClassificationExpired:
			if pt.ExpiresAtUnixSec == nil {
				t.Fatalf("EXPIRED without an expiry")
			}
			if pt.IsConnectable() {
				t.Fatalf("expired token is connectable")
			}
		case ClassificationLegacyReissueRequired:
			if pt.IsConnectable() {
				t.Fatalf("legacy token is connectable")
			}
		case ClassificationInvalid:
			if pt.ErrorCode == ErrNone {
				t.Fatalf("INVALID without an error code")
			}
			if pt.IsConnectable() {
				t.Fatalf("invalid token is connectable")
			}
		default:
			t.Fatalf("unknown classification %q", pt.Classification)
		}

		// Classification is a pure function of the input.
		again, _ := ParseToken(raw)
		if again.Classification != pt.Classification || again.ErrorCode != pt.ErrorCode ||
			again.ServerPublicHex != pt.ServerPublicHex || again.ServerDiscoHex != pt.ServerDiscoHex ||
			again.RegionID != pt.RegionID {
			t.Fatalf("ParseToken is not deterministic")
		}
	})
}

func FuzzStrictCBORTokenMap(f *testing.F) {
	f.Add([]byte{0xa0})                         // {}
	f.Add([]byte{0xa1, 0x61, 0x70, 0x40})       // {"p": h''}
	f.Add([]byte{0xbf, 0xff})                   // indefinite map
	f.Add([]byte{0x9f, 0x9f, 0x9f, 0xff, 0xff}) // nested indefinite arrays
	f.Add([]byte{0xa1, 0x61, 0x70, 0x5b, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff})
	f.Fuzz(func(t *testing.T, data []byte) {
		if len(data) > MaxCborPayloadBytes {
			return
		}
		m, keys, code, err := parseStrictTokenMap(data)
		if err == nil {
			if code != ErrNone {
				t.Fatalf("success with error code %s", code)
			}
			if m == nil || len(keys) != len(m) {
				t.Fatalf("success with nil map or key order of %d for %d entries", len(keys), len(m))
			}
			for _, k := range keys {
				if _, ok := m[k]; !ok {
					t.Fatalf("key order lists %q, which the map lacks", k)
				}
			}
		} else if code == ErrNone {
			t.Fatalf("error %v without error code", err)
		}
	})
}

func FuzzDNSParsers(f *testing.F) {
	q, err := encodeDNSQueryA("example.com")
	if err != nil {
		f.Fatal(err)
	}
	f.Add(q, q)
	f.Add([]byte{}, []byte{})
	// Pointer loop at offset 12.
	loop := make([]byte, 12, 32)
	binary.BigEndian.PutUint16(loop[4:6], 1)
	loop = append(loop, 0xc0, 0x0c, 0, 1, 0, 1)
	f.Add(loop, loop)
	f.Fuzz(func(t *testing.T, query, resp []byte) {
		_, _ = parseDNSA(resp)
		_, _ = skipDNSName(resp, 0)
		_, _ = skipDNSName(resp, 12)

		limit := dnsUDPResponseLimit(query)
		if limit < dnsClassicUDPSize || limit > 0xffff {
			t.Fatalf("UDP response limit %d out of range", limit)
		}
		out := truncateDNSForUDP(query, resp)
		if len(resp) <= limit || len(resp) < 12 {
			if !bytes.Equal(out, resp) {
				t.Fatalf("fitting response was modified")
			}
			return
		}
		if len(out) > limit || len(out) > dnsClassicUDPSize {
			t.Fatalf("truncated response %d bytes exceeds limit %d", len(out), limit)
		}
		if len(out) < 12 || out[2]&0x02 == 0 {
			t.Fatalf("truncated response lacks TC bit")
		}
		if binary.BigEndian.Uint16(out[6:8]) != 0 || binary.BigEndian.Uint16(out[8:10]) != 0 ||
			binary.BigEndian.Uint16(out[10:12]) != 0 {
			t.Fatalf("truncated response still claims records")
		}
	})
}

func FuzzFilterFetchedDERPMap(f *testing.F) {
	f.Add([]byte(`{"Regions":{"1":{"RegionID":1,"RegionCode":"x","Nodes":[{"Name":"1a","RegionID":1,"HostName":"derp.example.com"}]}}}`))
	f.Add([]byte(`{"Regions":{"1":{"RegionID":1,"Nodes":[{"Name":"1a","RegionID":1,"HostName":"127.0.0.1","InsecureForTests":true}]}}}`))
	f.Add([]byte(`{"Regions":{"1":null}}`))
	f.Fuzz(func(t *testing.T, raw []byte) {
		out, err := filterFetchedDERPMapJSON(raw)
		if err != nil {
			return
		}
		var dm tailcfg.DERPMap
		if err := json.Unmarshal(out, &dm); err != nil {
			t.Fatalf("filtered map does not round-trip: %v", err)
		}
		if len(dm.Regions) == 0 {
			t.Fatalf("filter accepted a map with no regions")
		}
		for _, r := range dm.Regions {
			if r == nil || len(r.Nodes) == 0 {
				t.Fatalf("filter kept an empty region")
			}
			for _, n := range r.Nodes {
				if n == nil || n.InsecureForTests {
					t.Fatalf("filter kept an InsecureForTests or nil node")
				}
				for label, v := range map[string]string{"hostname": n.HostName, "IPv4": n.IPv4, "IPv6": n.IPv6} {
					if err := rejectUnsafeDERPEndpoint(label, v); err != nil {
						t.Fatalf("filter kept unsafe %s %q: %v", label, v, err)
					}
				}
			}
		}
	})
}

func FuzzIPv6ExtensionHeaders(f *testing.F) {
	base := make([]byte, 48)
	base[0] = 0x60
	base[6] = 0 // hop-by-hop
	base[40] = 17
	f.Add(base)
	frag := append([]byte(nil), base...)
	frag[6] = 44
	f.Add(frag)
	f.Fuzz(func(t *testing.T, pkt []byte) {
		nh, off, _, ok := ipv6FinalNextHeader(pkt)
		if !ok {
			return
		}
		if off < 40 || off > len(pkt) {
			t.Fatalf("upper-layer offset %d outside packet of %d bytes", off, len(pkt))
		}
		if nh == 0 || nh == 43 || nh == 44 || nh == 51 || nh == 60 {
			t.Fatalf("returned extension header %d as the upper-layer protocol", nh)
		}
	})
}

// FuzzOutboundPacket feeds arbitrary TUN packets through the bridge's
// classifier and the gVisor stack. Properties: no panic, ICMP echo never
// reaches the gateway, every locally generated reply fits the IPv6 minimum
// MTU, and no flow ever leaves through anything but the TunnelClient (the
// mock is the only dialer the bridge holds).
func FuzzOutboundPacket(f *testing.F) {
	v4 := make([]byte, 28)
	v4[0] = 0x45
	binary.BigEndian.PutUint16(v4[2:4], 28)
	v4[6] = 0x40
	v4[8] = 64
	v4[9] = 17
	copy(v4[12:16], []byte{100, 64, 0, 2})
	copy(v4[16:20], []byte{1, 1, 1, 1})
	binary.BigEndian.PutUint16(v4[10:12], checksum(v4[:20]))
	f.Add(v4)
	big := append(append([]byte(nil), v4...), make([]byte, 1400)...)
	binary.BigEndian.PutUint16(big[2:4], uint16(len(big)))
	f.Add(big)
	icmp := append([]byte(nil), v4...)
	icmp[9] = 1
	f.Add(icmp)
	v6 := make([]byte, 48)
	v6[0] = 0x60
	binary.BigEndian.PutUint16(v6[4:6], 8)
	v6[6] = 58
	v6[7] = 64
	f.Add(v6)

	var dials atomic.Int64
	client := &mockTunnelClient{
		dialTCPFn: func(context.Context, netip.AddrPort) (net.Conn, error) {
			dials.Add(1)
			return nil, errors.New("fuzz: no gateway")
		},
		dialUDPFn: func(context.Context, netip.AddrPort) (net.Conn, error) {
			dials.Add(1)
			return nil, errors.New("fuzz: no gateway")
		},
	}
	r, w, err := os.Pipe()
	if err != nil {
		f.Fatal(err)
	}
	var oversizeReply atomic.Int64
	drained := make(chan struct{})
	go func() {
		defer close(drained)
		// A pipe is a byte stream: several replies can arrive in one read.
		// Split it by each packet's own IP length field.
		var stream []byte
		buf := make([]byte, 65536)
		for {
			n, err := r.Read(buf)
			stream = append(stream, buf[:n]...)
			for len(stream) >= 6 {
				var size int
				switch stream[0] >> 4 {
				case 4:
					size = int(binary.BigEndian.Uint16(stream[2:4]))
				case 6:
					size = 40 + int(binary.BigEndian.Uint16(stream[4:6]))
				default:
					size = -1
				}
				if size < 20 || size > ipv6MinMTU {
					oversizeReply.Add(1) // unparseable or oversized local reply
					stream = nil
					break
				}
				if len(stream) < size {
					break
				}
				stream = stream[size:]
			}
			if err == io.EOF || errors.Is(err, os.ErrClosed) || err != nil {
				return
			}
		}
	}()
	ctx, cancel := context.WithCancel(context.Background())
	b := &TunBridge{ctx: ctx, cancel: cancel, client: client, token: &ParsedToken{RegionID: 1}, mtu: 1280, tunFile: w}
	b.ipv6Egress.Store(true)
	proxy, err := newNetstackProxy(b)
	if err != nil {
		f.Fatal(err)
	}
	b.netstack = proxy
	f.Cleanup(func() {
		proxy.Close()
		cancel()
		_ = w.Close()
		<-drained
		_ = r.Close()
	})

	f.Fuzz(func(t *testing.T, pkt []byte) {
		if len(pkt) > 65535 {
			return
		}
		before := b.policyRejections.Load()
		b.handleOutboundPacket(append([]byte(nil), pkt...))
		if len(pkt) >= 20 && (b.mtu <= 0 || len(pkt) <= b.mtu) {
			isEcho := (pkt[0]>>4 == 4 && int(pkt[0]&0x0f)*4 >= 20 && len(pkt) >= int(pkt[0]&0x0f)*4 && pkt[9] == 1)
			if isEcho && b.policyRejections.Load() != before+1 {
				t.Fatalf("IPv4 ICMP was not rejected by policy")
			}
		}
		if oversizeReply.Load() != 0 {
			t.Fatalf("locally generated reply is malformed or larger than %d bytes", ipv6MinMTU)
		}
	})
}
