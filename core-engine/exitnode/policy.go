package exitnode

import (
	"errors"
	"net/netip"
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
}

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
}

var nat64 = netip.MustParsePrefix("64:ff9b::/96")

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
	ip = ip.Unmap() // ::ffff:127.0.0.1 is 127.0.0.1
	ip = ip.WithZone("")
	// A NAT64-mapped address is judged by the IPv4 address it embeds, so
	// 64:ff9b::7f00:1 cannot smuggle loopback past the IPv4 rules.
	if ip.Is6() && nat64.Contains(ip) {
		b := ip.As16()
		ip = netip.AddrFrom4([4]byte{b[12], b[13], b[14], b[15]})
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
