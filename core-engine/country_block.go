package engine

import (
	"encoding/binary"
	"fmt"
	"net/netip"
	"sort"
	"strings"
)

// Country blocking.
//
// The user can choose countries whose IP ranges the tunnel refuses to connect
// to. Kotlin owns the data (a bundled asset generated from the Regional
// Internet Registries' delegated statistics) and hands the engine only the
// prefixes of the countries the user selected. The engine never resolves a
// country itself and never looks anything up online: a destination is either
// inside one of the given prefixes or it is not.
//
// This is a destination filter for traffic that enters the tunnel from the
// device. It does not touch the engine's own flows (the in-tunnel speed test
// and exit-IP audit) or the transport sockets to the gateway, and it makes no
// claim about where a server physically is: the registries record where a
// range was first allocated, so CDN, anycast and cloud ranges follow their
// operator's country.

const (
	// A real per-country delegation is never this short (the largest in the
	// registry data are IPv4 /6 and IPv6 /16). A shorter prefix means a
	// corrupt list, and 0.0.0.0/0 or ::/0 would blackhole every connection.
	minBlockPrefixBits4 = 4
	minBlockPrefixBits6 = 8
)

// maxBlockPrefixes bounds the list size. All 239 countries together are about
// 250,000 prefixes, so this leaves room while still refusing a runaway list.
// A variable only so tests can lower it.
var maxBlockPrefixes = 600_000

type u128 struct{ hi, lo uint64 }

func (a u128) less(b u128) bool { return a.hi < b.hi || (a.hi == b.hi && a.lo < b.lo) }

// next returns a+1. The caller guarantees a is not the maximum value.
func (a u128) next() u128 {
	a.lo++
	if a.lo == 0 {
		a.hi++
	}
	return a
}

var maxU128 = u128{^uint64(0), ^uint64(0)}

type range4 struct{ lo, hi uint32 }

type range6 struct{ lo, hi u128 }

// countryBlocklist is an immutable set of destination ranges. It is built once
// by parseCountryBlocklist, then only read, so flows consult it without locks.
// Ranges are sorted, non-overlapping and non-adjacent.
type countryBlocklist struct {
	v4 []range4
	v6 []range6
	// entries is the number of prefixes the list was built from, before
	// overlapping ones were merged.
	entries int
}

// parseCountryBlocklist parses whitespace-separated CIDR prefixes. Empty input
// is not an error: it returns a nil list, which blocks nothing.
//
// Parsing is strict because a wrong list fails in a way the user would only
// notice as broken connections. Every prefix must be canonical (no host bits
// set), must not be an IPv4-mapped IPv6 prefix, and must be longer than the
// bounds above.
func parseCountryBlocklist(text string) (*countryBlocklist, error) {
	list := &countryBlocklist{}
	rest := text
	for {
		rest = strings.TrimLeft(rest, " \t\r\n")
		if rest == "" {
			break
		}
		end := strings.IndexAny(rest, " \t\r\n")
		if end < 0 {
			end = len(rest)
		}
		token := rest[:end]
		rest = rest[end:]

		if list.entries >= maxBlockPrefixes {
			return nil, fmt.Errorf("country block list has more than %d prefixes", maxBlockPrefixes)
		}
		list.entries++
		prefix, err := netip.ParsePrefix(token)
		if err != nil {
			return nil, fmt.Errorf("country block list entry %d (%s) is not a CIDR prefix", list.entries, shortToken(token))
		}
		addr := prefix.Addr()
		if addr.Is4In6() {
			return nil, fmt.Errorf("country block list entry %d (%s) is an IPv4-mapped IPv6 prefix", list.entries, shortToken(token))
		}
		if prefix != prefix.Masked() {
			return nil, fmt.Errorf("country block list entry %d (%s) has host bits set", list.entries, shortToken(token))
		}
		bits := prefix.Bits()
		if addr.Is4() {
			if bits < minBlockPrefixBits4 {
				return nil, fmt.Errorf("country block list entry %d (%s) is too broad", list.entries, shortToken(token))
			}
			list.v4 = append(list.v4, prefixRange4(addr, bits))
		} else {
			if bits < minBlockPrefixBits6 {
				return nil, fmt.Errorf("country block list entry %d (%s) is too broad", list.entries, shortToken(token))
			}
			list.v6 = append(list.v6, prefixRange6(addr, bits))
		}
	}
	if list.entries == 0 {
		return nil, nil
	}
	list.v4 = mergeRanges4(list.v4)
	list.v6 = mergeRanges6(list.v6)
	return list, nil
}

// shortToken bounds what an error message repeats of untrusted input.
func shortToken(token string) string {
	const limit = 48
	if len(token) > limit {
		return token[:limit] + "..."
	}
	return token
}

func prefixRange4(addr netip.Addr, bits int) range4 {
	a := addr.As4()
	lo := binary.BigEndian.Uint32(a[:])
	return range4{lo: lo, hi: lo | (^uint32(0) >> uint(bits))}
}

func prefixRange6(addr netip.Addr, bits int) range6 {
	a := addr.As16()
	lo := u128{binary.BigEndian.Uint64(a[:8]), binary.BigEndian.Uint64(a[8:])}
	hi := lo
	if bits <= 64 {
		hi.hi |= ^uint64(0) >> uint(bits)
		hi.lo = ^uint64(0)
	} else {
		hi.lo |= ^uint64(0) >> uint(bits-64)
	}
	return range6{lo: lo, hi: hi}
}

func mergeRanges4(ranges []range4) []range4 {
	if len(ranges) == 0 {
		return nil
	}
	sort.Slice(ranges, func(i, j int) bool { return ranges[i].lo < ranges[j].lo })
	merged := ranges[:1]
	for _, r := range ranges[1:] {
		last := &merged[len(merged)-1]
		if last.hi == ^uint32(0) || r.lo <= last.hi+1 {
			if r.hi > last.hi {
				last.hi = r.hi
			}
			continue
		}
		merged = append(merged, r)
	}
	return merged
}

func mergeRanges6(ranges []range6) []range6 {
	if len(ranges) == 0 {
		return nil
	}
	sort.Slice(ranges, func(i, j int) bool { return ranges[i].lo.less(ranges[j].lo) })
	merged := ranges[:1]
	for _, r := range ranges[1:] {
		last := &merged[len(merged)-1]
		if last.hi == maxU128 || !last.hi.next().less(r.lo) {
			if last.hi.less(r.hi) {
				last.hi = r.hi
			}
			continue
		}
		merged = append(merged, r)
	}
	return merged
}

// contains reports whether ip is inside a blocked range. A nil list contains
// nothing. IPv4-mapped IPv6 addresses are judged as the IPv4 address they map,
// and a well-known NAT64 address (64:ff9b::/96) by the IPv4 address it embeds,
// so neither form is a way around an IPv4 range.
func (b *countryBlocklist) contains(ip netip.Addr) bool {
	if b == nil || !ip.IsValid() {
		return false
	}
	ip = ip.Unmap().WithZone("")
	if ip.Is6() && dnsWellKnownNAT64.Contains(ip) {
		a := ip.As16()
		ip = netip.AddrFrom4([4]byte{a[12], a[13], a[14], a[15]})
	}
	if ip.Is4() {
		a := ip.As4()
		x := binary.BigEndian.Uint32(a[:])
		r := b.v4
		i := sort.Search(len(r), func(i int) bool { return r[i].hi >= x })
		return i < len(r) && r[i].lo <= x
	}
	a := ip.As16()
	x := u128{binary.BigEndian.Uint64(a[:8]), binary.BigEndian.Uint64(a[8:])}
	r := b.v6
	i := sort.Search(len(r), func(i int) bool { return !r[i].hi.less(x) })
	return i < len(r) && !x.less(r[i].lo)
}

// SetCountryBlocklist installs the destination ranges the tunnel refuses. It
// is exported to Kotlin through gomobile.
//
// prefixes is whitespace-separated CIDR prefixes; empty clears any earlier
// list. resolverIP is the DNS resolver the TUN will advertise: if it falls
// inside the list the call fails, because every lookup would then be refused
// and the user would see a working-looking tunnel with no names resolving.
//
// The list is read when the TUN is attached, so Kotlin sets it before every
// start, and a change takes effect on the next start. A failed call leaves the
// previous list untouched and the caller must not start the tunnel.
func SetCountryBlocklist(prefixes string, resolverIP string) (err error) {
	defer recoverExported("SetCountryBlocklist", &err)
	list, err := parseCountryBlocklist(prefixes)
	if err != nil {
		return err
	}
	if resolver := strings.TrimSpace(resolverIP); list != nil && resolver != "" {
		ip, perr := netip.ParseAddr(resolver)
		if perr != nil {
			return fmt.Errorf("country block list: DNS resolver %q is not an IP address", shortToken(resolver))
		}
		if list.contains(ip) {
			return fmt.Errorf("DNS resolver %s is inside a blocked country; choose another resolver or unblock that country", ip)
		}
	}
	globalCore.countryBlock.Store(list)
	return nil
}
