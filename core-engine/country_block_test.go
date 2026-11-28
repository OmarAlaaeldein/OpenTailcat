package engine

import (
	"math/rand"
	"net/netip"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// resetCountryBlock restores the process-wide list after a test.
func resetCountryBlock(t *testing.T) {
	t.Helper()
	globalCore.countryBlock.Store(nil)
	t.Cleanup(func() { globalCore.countryBlock.Store(nil) })
}

func mustParseBlocklist(t *testing.T, text string) *countryBlocklist {
	t.Helper()
	list, err := parseCountryBlocklist(text)
	if err != nil {
		t.Fatalf("parseCountryBlocklist(%q): %v", text, err)
	}
	return list
}

func TestParseCountryBlocklistEmptyBlocksNothing(t *testing.T) {
	for _, in := range []string{"", " ", "\n\t \r\n"} {
		list, err := parseCountryBlocklist(in)
		if err != nil || list != nil {
			t.Fatalf("parseCountryBlocklist(%q) = %v, %v; want nil, nil", in, list, err)
		}
		if list.contains(netip.MustParseAddr("1.2.3.4")) {
			t.Fatal("a nil list must block nothing")
		}
	}
}

func TestParseCountryBlocklistRejectsBadInput(t *testing.T) {
	for name, in := range map[string]string{
		"not a prefix":         "garbage",
		"bare address":         "1.2.3.4",
		"host bits set v4":     "1.2.3.4/24",
		"host bits set v6":     "2001:db8::1/32",
		"default route v4":     "0.0.0.0/0",
		"too broad v4":         "0.0.0.0/3",
		"default route v6":     "::/0",
		"too broad v6":         "2000::/7",
		"ipv4-mapped":          "::ffff:1.2.3.0/120",
		"prefix too long":      "1.2.3.0/33",
		"zone":                 "fe80::%eth0/64",
		"good then bad":        "10.0.0.0/8 junk",
		"comma separated":      "10.0.0.0/8,11.0.0.0/8",
		"negative prefix bits": "10.0.0.0/-1",
	} {
		t.Run(name, func(t *testing.T) {
			list, err := parseCountryBlocklist(in)
			if err == nil || list != nil {
				t.Fatalf("parseCountryBlocklist(%q) = %v, %v; want an error", in, list, err)
			}
		})
	}
}

func TestParseCountryBlocklistErrorDoesNotEchoHugeInput(t *testing.T) {
	_, err := parseCountryBlocklist(strings.Repeat("x", 5000))
	if err == nil {
		t.Fatal("expected an error")
	}
	if len(err.Error()) > 200 {
		t.Fatalf("error repeats untrusted input at length %d", len(err.Error()))
	}
}

func TestParseCountryBlocklistAcceptsTheBoundsAndTheRealExtremes(t *testing.T) {
	// The shortest prefixes in the committed registry data are IPv4 /6 and
	// IPv6 /16; the engine's own bounds are looser (/4 and /8).
	list := mustParseBlocklist(t, "16.0.0.0/6 0.0.0.0/4 2630::/16 2000::/8 1.2.3.4/32 ::1/128")
	for _, ip := range []string{"16.0.0.1", "7.7.7.7", "2630::1", "2000::1", "1.2.3.4", "::1"} {
		if !list.contains(netip.MustParseAddr(ip)) {
			t.Fatalf("%s should be blocked", ip)
		}
	}
}

func TestParseCountryBlocklistEntryLimit(t *testing.T) {
	old := maxBlockPrefixes
	maxBlockPrefixes = 3
	t.Cleanup(func() { maxBlockPrefixes = old })
	if _, err := parseCountryBlocklist("10.0.0.0/8 11.0.0.0/8 12.0.0.0/8"); err != nil {
		t.Fatalf("exactly the limit must be accepted: %v", err)
	}
	if _, err := parseCountryBlocklist("10.0.0.0/8 11.0.0.0/8 12.0.0.0/8 13.0.0.0/8"); err == nil {
		t.Fatal("one prefix over the limit must be refused")
	}
}

func TestBlocklistContainsEdgesOfEveryRange(t *testing.T) {
	list := mustParseBlocklist(t,
		"10.0.0.0/24 192.168.0.0/16 2001:db8::/32 2606:4700::/32")
	for ip, want := range map[string]bool{
		"9.255.255.255":                          false,
		"10.0.0.0":                               true,
		"10.0.0.255":                             true,
		"10.0.1.0":                               false,
		"192.167.255.255":                        false,
		"192.168.0.0":                            true,
		"192.168.255.255":                        true,
		"192.169.0.0":                            false,
		"2001:db7:ffff:ffff:ffff:ffff:ffff:ffff": false,
		"2001:db8::":                             true,
		"2001:db8:ffff:ffff:ffff:ffff:ffff:ffff": true,
		"2001:db9::":                             false,
		"2606:4700::1111":                        true,
		"2606:4701::":                            false,
	} {
		if got := list.contains(netip.MustParseAddr(ip)); got != want {
			t.Errorf("contains(%s) = %v, want %v", ip, got, want)
		}
	}
}

func TestBlocklistMergesOverlappingAndAdjacentRanges(t *testing.T) {
	list := mustParseBlocklist(t, "10.0.1.0/24 10.0.0.128/25 10.0.0.0/25 10.0.0.0/24 10.0.0.64/26")
	if len(list.v4) != 1 || list.v4[0].lo != 0x0A000000 || list.v4[0].hi != 0x0A0001FF {
		t.Fatalf("want one range 10.0.0.0-10.0.1.255, got %+v", list.v4)
	}
	if list.entries != 5 {
		t.Fatalf("entries = %d, want 5", list.entries)
	}
	// Ranges that touch the end of the address space must not overflow.
	top := mustParseBlocklist(t, "240.0.0.0/4 255.255.255.0/24 ff00::/8 ffff::/16")
	if len(top.v4) != 1 || len(top.v6) != 1 {
		t.Fatalf("want one range per family, got v4=%+v v6=%+v", top.v4, top.v6)
	}
	if !top.contains(netip.MustParseAddr("255.255.255.255")) ||
		!top.contains(netip.MustParseAddr("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff")) {
		t.Fatal("the last address of each family must be blocked")
	}
}

func TestBlocklistJudgesMappedAndNAT64AddressesByTheirIPv4(t *testing.T) {
	list := mustParseBlocklist(t, "1.2.3.0/24")
	for ip, want := range map[string]bool{
		"::ffff:1.2.3.4":      true,  // IPv4-mapped
		"::ffff:8.8.8.8":      false, // IPv4-mapped, not listed
		"64:ff9b::102:304":    true,  // well-known NAT64 embedding 1.2.3.4
		"64:ff9b::808:808":    false, // NAT64 embedding 8.8.8.8
		"2606:4700:4700::111": false, // ordinary IPv6
	} {
		if got := list.contains(netip.MustParseAddr(ip)); got != want {
			t.Errorf("contains(%s) = %v, want %v", ip, got, want)
		}
	}
	if list.contains(netip.Addr{}) {
		t.Fatal("the zero address must not match")
	}
	if list.contains(netip.MustParseAddr("fe80::1%eth0")) {
		t.Fatal("a scoped link-local address must not match")
	}
}

// --- differential test against a brute-force scan ---

func addrStep(a netip.Addr, delta int) (netip.Addr, bool) {
	b := a.AsSlice()
	for i := len(b) - 1; i >= 0; i-- {
		b[i] += byte(delta)
		if (delta > 0 && b[i] != 0) || (delta < 0 && b[i] != 0xff) {
			out, _ := netip.AddrFromSlice(b)
			return out, true
		}
	}
	return netip.Addr{}, false
}

func lastAddr(p netip.Prefix) netip.Addr {
	b := p.Addr().AsSlice()
	for i := p.Bits(); i < len(b)*8; i++ {
		b[i/8] |= 0x80 >> uint(i%8)
	}
	out, _ := netip.AddrFromSlice(b)
	return out
}

func linearBlocked(prefixes []netip.Prefix, ip netip.Addr) bool {
	ip = ip.Unmap()
	if ip.Is6() && dnsWellKnownNAT64.Contains(ip) {
		a := ip.As16()
		ip = netip.AddrFrom4([4]byte{a[12], a[13], a[14], a[15]})
	}
	for _, p := range prefixes {
		if p.Contains(ip) {
			return true
		}
	}
	return false
}

func TestBlocklistMatchesBruteForceScan(t *testing.T) {
	rng := rand.New(rand.NewSource(20261127))
	var prefixes []netip.Prefix
	var text strings.Builder
	for i := 0; i < 600; i++ {
		var p netip.Prefix
		if i%2 == 0 {
			// Clustered in a small window so ranges overlap and touch.
			base := uint32(0x0A000000) + uint32(rng.Intn(1<<16))
			bits := 16 + rng.Intn(17)
			if rng.Intn(200) == 0 {
				bits = 4 + rng.Intn(4)
			}
			p = netip.PrefixFrom(netip.AddrFrom4([4]byte{byte(base >> 24), byte(base >> 16), byte(base >> 8), byte(base)}), bits).Masked()
		} else {
			var raw [16]byte
			raw[0], raw[1], raw[2], raw[3] = 0x20, 0x01, 0x0d, 0xb8
			raw[4], raw[5] = byte(rng.Intn(4)), byte(rng.Intn(256))
			raw[6], raw[7] = byte(rng.Intn(256)), byte(rng.Intn(256))
			p = netip.PrefixFrom(netip.AddrFrom16(raw), 32+rng.Intn(97)).Masked()
		}
		prefixes = append(prefixes, p)
		text.WriteString(p.String())
		text.WriteByte(' ')
	}
	list := mustParseBlocklist(t, text.String())

	check := func(ip netip.Addr) {
		t.Helper()
		if got, want := list.contains(ip), linearBlocked(prefixes, ip); got != want {
			t.Fatalf("contains(%s) = %v, brute force says %v", ip, got, want)
		}
	}
	for _, p := range prefixes {
		first, last := p.Addr(), lastAddr(p)
		check(first)
		check(last)
		if before, ok := addrStep(first, -1); ok {
			check(before)
		}
		if after, ok := addrStep(last, 1); ok {
			check(after)
		}
	}
	for i := 0; i < 20000; i++ {
		base := uint32(0x0A000000) + uint32(rng.Intn(1<<17))
		check(netip.AddrFrom4([4]byte{byte(base >> 24), byte(base >> 16), byte(base >> 8), byte(base)}))
		var raw [16]byte
		raw[0], raw[1], raw[2], raw[3] = 0x20, 0x01, 0x0d, 0xb8
		raw[4], raw[5] = byte(rng.Intn(5)), byte(rng.Intn(256))
		raw[6], raw[7] = byte(rng.Intn(256)), byte(rng.Intn(256))
		raw[15] = byte(rng.Intn(256))
		check(netip.AddrFrom16(raw))
	}
}

// --- SetCountryBlocklist ---

func TestSetCountryBlocklistInstallsReplacesAndClears(t *testing.T) {
	resetCountryBlock(t)
	if err := SetCountryBlocklist("198.51.100.0/24", ""); err != nil {
		t.Fatalf("SetCountryBlocklist: %v", err)
	}
	if !globalCore.countryBlock.Load().contains(netip.MustParseAddr("198.51.100.9")) {
		t.Fatal("installed list does not block its own range")
	}
	if err := SetCountryBlocklist("203.0.113.0/24", ""); err != nil {
		t.Fatalf("SetCountryBlocklist: %v", err)
	}
	list := globalCore.countryBlock.Load()
	if list.contains(netip.MustParseAddr("198.51.100.9")) || !list.contains(netip.MustParseAddr("203.0.113.9")) {
		t.Fatal("a new list must replace the old one, not add to it")
	}
	if err := SetCountryBlocklist("", ""); err != nil {
		t.Fatalf("clearing: %v", err)
	}
	if globalCore.countryBlock.Load() != nil {
		t.Fatal("an empty list must clear the filter")
	}
}

func TestSetCountryBlocklistFailureKeepsThePreviousList(t *testing.T) {
	resetCountryBlock(t)
	if err := SetCountryBlocklist("198.51.100.0/24", ""); err != nil {
		t.Fatal(err)
	}
	before := globalCore.countryBlock.Load()
	for name, args := range map[string][2]string{
		"bad prefix":       {"198.51.100.0/24 nonsense", ""},
		"resolver blocked": {"198.51.100.0/24", "198.51.100.53"},
		"resolver garbage": {"198.51.100.0/24", "not-an-ip"},
	} {
		if err := SetCountryBlocklist(args[0], args[1]); err == nil {
			t.Fatalf("%s: expected an error", name)
		}
		if globalCore.countryBlock.Load() != before {
			t.Fatalf("%s: a failed call must leave the previous list in place", name)
		}
	}
}

func TestSetCountryBlocklistRefusesAListThatBlocksTheDNSResolver(t *testing.T) {
	resetCountryBlock(t)
	err := SetCountryBlocklist("198.51.100.0/24", " 198.51.100.53 ")
	if err == nil || !strings.Contains(err.Error(), "198.51.100.53") {
		t.Fatalf("want an error naming the resolver, got %v", err)
	}
	if err := SetCountryBlocklist("198.51.100.0/24", "203.0.113.53"); err != nil {
		t.Fatalf("a resolver outside the list is fine: %v", err)
	}
	// The resolver is irrelevant when nothing is blocked.
	if err := SetCountryBlocklist("", "not-checked"); err != nil {
		t.Fatalf("an empty list never conflicts with a resolver: %v", err)
	}
}

func TestAttachTunSnapshotsTheCountryBlocklistIntoTheBridge(t *testing.T) {
	_ = Stop()
	resetCountryBlock(t)
	installClient(t, &prepareTestClient{})
	if err := SetCountryBlocklist("198.51.100.0/24", ""); err != nil {
		t.Fatal(err)
	}
	if err := Prepare(officialTestToken(t)); err != nil {
		t.Fatalf("Prepare: %v", err)
	}
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	defer w.Close()
	if err := AttachTun(int(r.Fd())); err != nil {
		t.Fatalf("AttachTun: %v", err)
	}
	bridgeList := func() *countryBlocklist {
		globalCore.mu.Lock()
		defer globalCore.mu.Unlock()
		return globalCore.sess.bridge.countryBlock.Load()
	}
	first := bridgeList()
	if !first.contains(netip.MustParseAddr("198.51.100.1")) {
		t.Fatal("the attached bridge must carry the list set before Prepare")
	}

	// A later change applies to the next attach, never to a running bridge.
	if err := SetCountryBlocklist("203.0.113.0/24", ""); err != nil {
		t.Fatal(err)
	}
	if bridgeList() != first {
		t.Fatal("a running bridge's list must not change under it")
	}
	r2, w2, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	defer r2.Close()
	defer w2.Close()
	if err := AttachTun(int(r2.Fd())); err != nil {
		t.Fatalf("reattach: %v", err)
	}
	second := bridgeList()
	if second.contains(netip.MustParseAddr("198.51.100.1")) || !second.contains(netip.MustParseAddr("203.0.113.1")) {
		t.Fatal("the routed re-attach must pick up the list set since")
	}
	if err := Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}
}

// --- the gate, end to end through real packets ---

func newCountryBlockTestBridge(t *testing.T, list string) (*TunBridge, *atomic.Int64, *atomic.Int64) {
	t.Helper()
	bridge, dialTCP, dialUDP, cleanup := newIPv6DropTestBridge(t)
	t.Cleanup(cleanup)
	bridge.countryBlock.Store(mustParseBlocklist(t, list))
	return bridge, dialTCP, dialUDP
}

var countryTestSrc4 = netip.MustParseAddrPort("100.64.0.2:54321")
var countryTestSrc6 = netip.MustParseAddrPort("[fd7a:115c:a1e0::2]:54321")

func TestCountryBlockRefusesTCPToABlockedRangeAndAllowsTheRest(t *testing.T) {
	bridge, dialTCP, _ := newCountryBlockTestBridge(t, "198.51.100.0/24")

	before := bridge.policyRejections.Load()
	bridge.handleOutboundPacket(buildIPv4TCPSyn(countryTestSrc4, netip.MustParseAddrPort("198.51.100.7:443")))
	time.Sleep(200 * time.Millisecond)
	if dialTCP.Load() != 0 {
		t.Fatalf("a blocked destination must never be dialed, got %d dials", dialTCP.Load())
	}
	if bridge.policyRejections.Load() <= before {
		t.Fatal("a blocked destination must count as a policy rejection")
	}

	bridge.handleOutboundPacket(buildIPv4TCPSyn(countryTestSrc4, netip.MustParseAddrPort("198.51.101.7:443")))
	waitAtomic(t, dialTCP, 1, 2*time.Second, "DialTCP for a destination outside the list")
}

func TestCountryBlockRefusesUDPToABlockedRangeAndAllowsTheRest(t *testing.T) {
	bridge, _, dialUDP := newCountryBlockTestBridge(t, "198.51.100.0/24")

	before := bridge.policyRejections.Load()
	bridge.handleOutboundPacket(buildIPv4UDPPacket(countryTestSrc4, netip.MustParseAddrPort("198.51.100.7:443"), []byte("quic")))
	time.Sleep(200 * time.Millisecond)
	if dialUDP.Load() != 0 {
		t.Fatalf("a blocked UDP destination must never be dialed, got %d dials", dialUDP.Load())
	}
	if bridge.policyRejections.Load() <= before {
		t.Fatal("a blocked UDP destination must count as a policy rejection")
	}

	bridge.handleOutboundPacket(buildIPv4UDPPacket(countryTestSrc4, netip.MustParseAddrPort("198.51.101.7:443"), []byte("quic")))
	waitAtomic(t, dialUDP, 1, 2*time.Second, "DialUDP for a destination outside the list")
}

func TestCountryBlockCoversIPv6AndNAT64(t *testing.T) {
	bridge, dialTCP, dialUDP := newCountryBlockTestBridge(t, "198.51.100.0/24 2001:db8:abcd::/48")

	before := bridge.policyRejections.Load()
	bridge.handleOutboundPacket(buildIPv6TCPSyn(countryTestSrc6, netip.MustParseAddrPort("[2001:db8:abcd::1]:443")))
	bridge.handleOutboundPacket(buildIPv6UDPPacket(countryTestSrc6, netip.MustParseAddrPort("[2001:db8:abcd::1]:443"), []byte("quic")))
	// 64:ff9b::c633:6407 is the well-known NAT64 form of 198.51.100.7.
	bridge.handleOutboundPacket(buildIPv6TCPSyn(countryTestSrc6, netip.MustParseAddrPort("[64:ff9b::c633:6407]:443")))
	time.Sleep(250 * time.Millisecond)
	if dialTCP.Load() != 0 || dialUDP.Load() != 0 {
		t.Fatalf("blocked IPv6 and NAT64 destinations must not dial, tcp=%d udp=%d", dialTCP.Load(), dialUDP.Load())
	}
	if got := bridge.policyRejections.Load() - before; got < 3 {
		t.Fatalf("want at least 3 policy rejections, got %d", got)
	}

	// Outside the list: another IPv6 prefix, and NAT64 of an unlisted IPv4.
	bridge.handleOutboundPacket(buildIPv6TCPSyn(countryTestSrc6, netip.MustParseAddrPort("[2001:db8:ffff::1]:443")))
	bridge.handleOutboundPacket(buildIPv6TCPSyn(countryTestSrc6, netip.MustParseAddrPort("[64:ff9b::c633:6507]:443")))
	waitAtomic(t, dialTCP, 2, 2*time.Second, "DialTCP for IPv6 and NAT64 outside the list")
}

func TestCountryBlockAppliesToDNSFlowsLikeAnyOther(t *testing.T) {
	bridge, _, dialUDP := newCountryBlockTestBridge(t, "9.9.9.0/24")
	resolver := netip.MustParseAddrPort("9.9.9.9:53")

	before := bridge.policyRejections.Load()
	bridge.handleOutboundPacket(buildIPv4UDPPacket(countryTestSrc4, resolver, []byte("query")))
	time.Sleep(200 * time.Millisecond)
	if dialUDP.Load() != 0 || bridge.policyRejections.Load() <= before {
		t.Fatalf("a resolver inside the list is refused like any destination: dials=%d", dialUDP.Load())
	}

	// Control: the same flow with no list is dialed, so the refusal above
	// came from the list and not from some other gate.
	bridge.countryBlock.Store(nil)
	bridge.handleOutboundPacket(buildIPv4UDPPacket(netip.MustParseAddrPort("100.64.0.2:54322"), resolver, []byte("query")))
	waitAtomic(t, dialUDP, 1, 2*time.Second, "DialUDP for the same resolver once the list is gone")
}

func TestCountryBlockJudgesTheRedirectedDNSDestination(t *testing.T) {
	bridge, _, dialUDP := newCountryBlockTestBridge(t, "9.9.9.0/24")
	asked := netip.MustParseAddrPort("8.8.8.8:53") // what the application asked for

	// FORCED_RESOLVER redirects every port-53 flow. The application asked an
	// unlisted resolver, but the redirect lands inside the list, and that is
	// where the packet would really go, so it is refused.
	bridge.SetDNSConfig(DNSConfig{Policy: "FORCED_RESOLVER", ForcedDNS: netip.MustParseAddrPort("9.9.9.9:53")})
	before := bridge.policyRejections.Load()
	bridge.handleOutboundPacket(buildIPv4UDPPacket(countryTestSrc4, asked, []byte("query")))
	time.Sleep(200 * time.Millisecond)
	if dialUDP.Load() != 0 || bridge.policyRejections.Load() <= before {
		t.Fatalf("a redirect into the list must be refused: dials=%d", dialUDP.Load())
	}

	// Control: the identical flow and redirect with no list is dialed, so the
	// refusal above came from the list and not from the forced-DNS gate.
	bridge.countryBlock.Store(mustParseBlocklist(t, ""))
	bridge.handleOutboundPacket(buildIPv4UDPPacket(netip.MustParseAddrPort("100.64.0.2:54322"), asked, []byte("query")))
	waitAtomic(t, dialUDP, 1, 2*time.Second, "DialUDP for the same redirect once the list is gone")

	// The converse: asking a listed resolver is fine when the redirect moves
	// the query outside the list. Only the real destination is judged.
	bridge.countryBlock.Store(mustParseBlocklist(t, "9.9.9.0/24"))
	bridge.SetDNSConfig(DNSConfig{Policy: "FORCED_RESOLVER", ForcedDNS: netip.MustParseAddrPort("1.1.1.1:53")})
	bridge.handleOutboundPacket(buildIPv4UDPPacket(netip.MustParseAddrPort("100.64.0.2:54323"), netip.MustParseAddrPort("9.9.9.9:53"), []byte("query")))
	waitAtomic(t, dialUDP, 2, 2*time.Second, "DialUDP when the redirect leaves the list")
}

func TestNoCountryListBlocksNothing(t *testing.T) {
	bridge, dialTCP, _, cleanup := newIPv6DropTestBridge(t)
	defer cleanup()
	if bridge.countryBlock.Load() != nil {
		t.Fatal("a bridge starts without a list")
	}
	bridge.handleOutboundPacket(buildIPv4TCPSyn(countryTestSrc4, netip.MustParseAddrPort("198.51.100.7:443")))
	waitAtomic(t, dialTCP, 1, 2*time.Second, "DialTCP with no list installed")
}
