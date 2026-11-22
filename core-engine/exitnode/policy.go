package exitnode

import (
	"errors"
	"net"
	"net/netip"
	"sync"
	"time"

	"tailscale.com/net/netmon"
)

// Destination policy.
//
// A guest asks the node to open a connection to an IP:port and the node dials
// it from the phone's own network. Without a policy, "exit through my phone"
// also means "reach my phone": 127.0.0.1 services, the home router's admin
// page, other devices on the LAN, the carrier's internal network. The upstream
// CLI deliberately serves localhost; a phone shared with a stranger must not.
//
// The library hands the handler a netip.AddrPort, never a hostname, so there is
// no resolution step a hostile name could redirect (no DNS rebinding): what is
// checked here is exactly what is dialed.

// Rejected for every configuration: these are never a valid unicast Internet
// destination, so there is no legitimate reason to dial them.
var alwaysRejected = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"),       // "this network", incl. 0.0.0.0
	netip.MustParsePrefix("169.254.0.0/16"),  // link-local, incl. cloud metadata
	netip.MustParsePrefix("224.0.0.0/4"),     // multicast
	netip.MustParsePrefix("240.0.0.0/4"),     // reserved, incl. 255.255.255.255
	netip.MustParsePrefix("fe80::/10"),       // link-local
	netip.MustParsePrefix("ff00::/8"),        // multicast
	netip.MustParsePrefix("::/128"),          // unspecified
	netip.MustParsePrefix("100::/64"),        // discard-only
	netip.MustParsePrefix("2001:db8::/32"),   // documentation
	netip.MustParsePrefix("192.0.2.0/24"),    // documentation
	netip.MustParsePrefix("198.51.100.0/24"), // documentation
	netip.MustParsePrefix("203.0.113.0/24"),  // documentation
	netip.MustParsePrefix("::ffff:0:0:0/96"), // RFC 2765 SIIT translated IPv4
	netip.MustParsePrefix("2001::/32"),       // Teredo: embeds an IPv4 relay and client
	netip.MustParsePrefix("2001:2::/48"),     // benchmarking
	netip.MustParsePrefix("2002::/16"),       // 6to4: embeds an arbitrary IPv4 address
	netip.MustParsePrefix("3fff::/20"),       // documentation (RFC 9637)
	netip.MustParsePrefix("5f00::/16"),       // SRv6 SIDs
}

// ipv4Compatible is the deprecated IPv4-compatible IPv6 block ::/96. Apart
// from ::1 (loopback, judged by localOnly) and :: (unspecified), every address
// in it embeds an IPv4 address the IPv4 rules would otherwise have to judge,
// so it is refused outright.
var ipv4Compatible = netip.MustParsePrefix("::/96")

// localUseNAT64 is the RFC 8215 local-use NAT64 prefix. Its translator is by
// definition operated by the host's own network, so it is local whatever IPv4
// address it embeds.
var localUseNAT64 = netip.MustParsePrefix("64:ff9b:1::/48")

// Rejected unless Config.AllowLocalDestinations is set. These are the host's
// own machine and networks.
var localOnly = []netip.Prefix{
	netip.MustParsePrefix("127.0.0.0/8"),    // loopback
	netip.MustParsePrefix("10.0.0.0/8"),     // RFC 1918
	netip.MustParsePrefix("172.16.0.0/12"),  // RFC 1918
	netip.MustParsePrefix("192.168.0.0/16"), // RFC 1918
	netip.MustParsePrefix("100.64.0.0/10"),  // CGNAT: carrier-internal on cellular
	netip.MustParsePrefix("192.0.0.0/24"),   // IETF protocol assignments
	netip.MustParsePrefix("198.18.0.0/15"),  // benchmarking
	netip.MustParsePrefix("::1/128"),        // loopback
	netip.MustParsePrefix("fc00::/7"),       // unique local (also Tailcat's own range)
	netip.MustParsePrefix("fec0::/10"),      // deprecated site-local
}

// Prefixes this policy can decode without knowing the active network: the
// well-known NAT64 prefix and the RFC 8215 local-use prefix. A carrier-specific
// prefix is only known at runtime, which the node does not receive yet.
var nat64Prefixes = []netip.Prefix{
	netip.MustParsePrefix("64:ff9b::/96"),
	netip.MustParsePrefix("64:ff9b:1::/48"),
}

// embeddedIPv4 returns the IPv4 address an RFC 6052 address carries, if ip is
// inside prefix and the prefix length is one RFC 6052 defines. The u octet at
// bits 64-71 is skipped for prefix lengths shorter than /96.
func embeddedIPv4(prefix netip.Prefix, ip netip.Addr) (netip.Addr, bool) {
	if !ip.Is6() || !prefix.Contains(ip) {
		return netip.Addr{}, false
	}
	b := ip.As16()
	switch prefix.Bits() {
	case 32:
		return netip.AddrFrom4([4]byte{b[4], b[5], b[6], b[7]}), true
	case 40:
		return netip.AddrFrom4([4]byte{b[5], b[6], b[7], b[9]}), true
	case 48:
		return netip.AddrFrom4([4]byte{b[6], b[7], b[9], b[10]}), true
	case 56:
		return netip.AddrFrom4([4]byte{b[7], b[9], b[10], b[11]}), true
	case 64:
		return netip.AddrFrom4([4]byte{b[9], b[10], b[11], b[12]}), true
	case 96:
		return netip.AddrFrom4([4]byte{b[12], b[13], b[14], b[15]}), true
	default:
		return netip.Addr{}, false
	}
}

var (
	errBadDestination = errors.New("exitnode: invalid destination")
	errBlockedAlways  = errors.New("exitnode: destination is not routable on the Internet")
	errBlockedLocal   = errors.New("exitnode: destination is on the host's own network")
)

// checkDestination reports why dst may not be dialed, or nil when it may.
func checkDestination(dst netip.AddrPort, allowLocal bool) error {
	ip := dst.Addr()
	if !ip.IsValid() || dst.Port() == 0 {
		return errBadDestination
	}
	// A scoped address names an interface on the host, which is never an
	// Internet destination.
	if ip.Zone() != "" {
		return errBadDestination
	}
	ip = ip.Unmap() // ::ffff:127.0.0.1 is 127.0.0.1
	orig := ip
	// A NAT64-mapped address is judged by the IPv4 address it embeds, so
	// 64:ff9b::7f00:1 or 64:ff9b:1:7f00:0:100:: cannot smuggle loopback past
	// the IPv4 rules.
	for _, p := range nat64Prefixes {
		if v4, ok := embeddedIPv4(p, ip); ok {
			ip = v4
			break
		}
	}
	if !allowLocal && localUseNAT64.Contains(orig) {
		return errBlockedLocal
	}
	if ip.Is6() && ipv4Compatible.Contains(ip) && ip != netip.IPv6Loopback() {
		return errBlockedAlways
	}
	for _, p := range alwaysRejected {
		if p.Contains(ip) {
			return errBlockedAlways
		}
	}
	if !allowLocal {
		for _, p := range localOnly {
			if p.Contains(ip) {
				return errBlockedLocal
			}
		}
	}
	return nil
}

// hostLocalRefresh bounds how stale the host's own address list may be. The
// forwarding hooks run per flow, so the interface list is not re-read on
// every dial.
const hostLocalRefresh = 5 * time.Second

// hostLocal remembers the host's own interface addresses and on-link LAN
// prefixes. A public address on the phone itself (an IPv6 global address on
// Wi-Fi, a public IPv4 on cellular) or on its LAN is not in any fixed range
// above, but is just as much "the host's own network".
type hostLocal struct {
	source func() []netip.Prefix
	now    func() time.Time

	mu      sync.Mutex
	fetched time.Time
	have    bool
	pfxs    []netip.Prefix
}

func (h *hostLocal) contains(ip netip.Addr) bool {
	if h == nil || h.source == nil || !ip.IsValid() {
		return false
	}
	ip = ip.Unmap().WithZone("")
	var embedded netip.Addr
	for _, p := range nat64Prefixes {
		if v4, ok := embeddedIPv4(p, ip); ok {
			embedded = v4
			break
		}
	}
	now := h.now()
	h.mu.Lock()
	if !h.have || now.Sub(h.fetched) >= hostLocalRefresh || now.Before(h.fetched) {
		h.pfxs = h.source()
		h.fetched = now
		h.have = true
	}
	pfxs := h.pfxs
	h.mu.Unlock()
	for _, p := range pfxs {
		if p.Contains(ip) || (embedded.IsValid() && p.Contains(embedded)) {
			return true
		}
	}
	return false
}

// interfacePrefixes lists the host's own addresses (as host prefixes) and,
// for broadcast-style interfaces, the on-link prefix each address sits in.
// Point-to-point links (cellular, tunnels) have no LAN neighbours, so only
// their own address is listed. Very short prefixes are not widened: an
// interface claiming a /4 must not turn the policy into "block everything".
//
// On Android the interface list comes from the LinkProperties the app pushes
// (see core-engine/main.go); when no list is available nothing extra is
// blocked and the fixed ranges above still apply.
func interfacePrefixes() []netip.Prefix {
	ifs, err := netmon.GetInterfaceList()
	if err != nil {
		return nil
	}
	var out []netip.Prefix
	_ = ifs.ForeachInterface(func(i netmon.Interface, pfxs []netip.Prefix) {
		if !i.IsUp() {
			return
		}
		p2p := i.Interface != nil && i.Flags&net.FlagPointToPoint != 0
		for _, p := range pfxs {
			a := p.Addr().Unmap().WithZone("")
			if !a.IsValid() {
				continue
			}
			out = append(out, netip.PrefixFrom(a, a.BitLen()))
			if p2p || i.IsLoopback() {
				continue
			}
			minBits := 16
			if a.Is6() {
				minBits = 48
			}
			bits := p.Bits()
			if a.Is4() && p.Addr().Is4In6() {
				bits -= 96
			}
			if bits >= minBits && bits < a.BitLen() {
				out = append(out, netip.PrefixFrom(a, bits).Masked())
			}
		}
	})
	return out
}
