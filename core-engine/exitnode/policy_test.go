package exitnode

import (
	"net/netip"
	"testing"
)

func TestCheckDestination(t *testing.T) {
	cases := []struct {
		dst        string
		allowLocal bool
		want       error
	}{
		// Public destinations pass in both modes.
		{"8.8.8.8:53", false, nil},
		{"1.1.1.1:443", false, nil},
		{"[2606:4700:4700::1111]:443", false, nil},
		{"8.8.8.8:53", true, nil},

		// The host's own machine and networks are refused by default...
		{"127.0.0.1:22", false, errBlockedLocal},
		{"127.255.255.254:80", false, errBlockedLocal},
		{"[::1]:8080", false, errBlockedLocal},
		{"10.0.0.1:80", false, errBlockedLocal},
		{"172.16.0.1:80", false, errBlockedLocal},
		{"172.31.255.255:80", false, errBlockedLocal},
		{"192.168.1.1:80", false, errBlockedLocal},
		{"100.64.0.1:80", false, errBlockedLocal},
		{"100.127.255.255:80", false, errBlockedLocal},
		{"[fd7a:115c:a1e0::1]:80", false, errBlockedLocal},
		{"[fc00::1]:80", false, errBlockedLocal},
		// ...and reachable only when a test opts in.
		{"127.0.0.1:22", true, nil},
		{"192.168.1.1:80", true, nil},

		// The neighbours of the private ranges are public.
		{"172.15.255.255:80", false, nil},
		{"172.32.0.1:80", false, nil},
		{"100.63.255.255:80", false, nil},
		{"100.128.0.1:80", false, nil},

		// Never dialable, whatever the opt-in.
		{"0.0.0.0:80", true, errBlockedAlways},
		{"0.1.2.3:80", true, errBlockedAlways},
		{"169.254.169.254:80", true, errBlockedAlways},
		{"224.0.0.1:5353", true, errBlockedAlways},
		{"239.255.255.250:1900", true, errBlockedAlways},
		{"255.255.255.255:67", true, errBlockedAlways},
		{"240.0.0.1:80", true, errBlockedAlways},
		{"[fe80::1]:80", true, errBlockedAlways},
		{"[ff02::1]:80", true, errBlockedAlways},
		{"[::]:80", true, errBlockedAlways},

		// A 4-in-6 address is judged as the IPv4 address it wraps.
		{"[::ffff:127.0.0.1]:80", false, errBlockedLocal},
		{"[::ffff:10.1.2.3]:80", false, errBlockedLocal},
		{"[::ffff:169.254.169.254]:80", true, errBlockedAlways},
		{"[::ffff:8.8.8.8]:80", false, nil},

		// So is a NAT64-embedded one; RFC 8215 64:ff9b:1::/48 is always local-use.
		{"[64:ff9b::7f00:1]:80", false, errBlockedLocal},
		{"[64:ff9b::a9fe:a9fe]:80", true, errBlockedAlways},
		{"[64:ff9b::808:808]:80", false, nil},
		{"[64:ff9b:1:7f00:0:100::]:80", false, errBlockedLocal},
		{"[64:ff9b:1:c0a8:1:100::]:80", false, errBlockedLocal},
		{"[64:ff9b:1:a9fe:a9:fe00::]:80", true, errBlockedAlways},
		{"[64:ff9b:1:808:8:800::]:80", false, errBlockedLocal},
		{"[64:ff9b:1:808:8:800::]:80", true, nil},
		{"[64:ff9b:1::808:808]:80", false, errBlockedLocal},
		{"[64:ff9b:1:c0a8:1:100::]:80", true, nil},

		// Zone-scoped addresses and special-purpose IPv6 blocks are rejected.
		{"[fe80::1%eth0]:80", true, errBadDestination},
		{"[2606:4700:4700::1111%wlan0]:443", false, errBadDestination},
		{"[::8.8.8.8]:80", true, errBlockedAlways},
		{"[::ffff:0:8.8.8.8]:80", true, errBlockedAlways},
		{"[fec0::1]:80", false, errBlockedLocal},
		{"[fec0::1]:80", true, nil},
		{"[2001::1]:80", true, errBlockedAlways},
		{"[2001:2::1]:80", true, errBlockedAlways},
		{"[2002:0808:0808::]:80", true, errBlockedAlways},
		{"[3fff::1]:80", true, errBlockedAlways},
		{"[5f00::1]:80", true, errBlockedAlways},

		// Port 0 is not a destination.
		{"8.8.8.8:0", false, errBadDestination},
	}
	for _, tc := range cases {
		dst := netip.MustParseAddrPort(tc.dst)
		if got := checkDestination(dst, tc.allowLocal); got != tc.want {
			t.Errorf("checkDestination(%s, allowLocal=%v) = %v, want %v", tc.dst, tc.allowLocal, got, tc.want)
		}
	}
}

func TestCheckDestinationInvalid(t *testing.T) {
	if err := checkDestination(netip.AddrPort{}, true); err != errBadDestination {
		t.Fatalf("zero AddrPort: got %v, want errBadDestination", err)
	}
}

// RFC 6052 section 2.4: every prefix length the specification defines must
// decode to the same IPv4 address. Only /96 and /48 are used by the policy
// today; the rest keep the decoder honest for a runtime network prefix.
func TestEmbeddedIPv4RFC6052(t *testing.T) {
	cases := []struct {
		prefix string
		addr   string
		want   string
	}{
		{"2001:db8::/32", "2001:db8:c000:221::", "192.0.2.33"},
		{"2001:db8:100::/40", "2001:db8:1c0:2:21::", "192.0.2.33"},
		{"2001:db8:122::/48", "2001:db8:122:c000:2:2100::", "192.0.2.33"},
		{"2001:db8:122:300::/56", "2001:db8:122:3c0:0:221::", "192.0.2.33"},
		{"2001:db8:122:344::/64", "2001:db8:122:344:c0:2:2100:0", "192.0.2.33"},
		{"2001:db8:122:344::/96", "2001:db8:122:344::c000:221", "192.0.2.33"},
	}
	for _, tc := range cases {
		prefix := netip.MustParsePrefix(tc.prefix)
		got, ok := embeddedIPv4(prefix, netip.MustParseAddr(tc.addr))
		if !ok || got != netip.MustParseAddr(tc.want) {
			t.Errorf("%s in %s = %v, %v; want %s, true", tc.addr, tc.prefix, got, ok, tc.want)
		}
	}

	if _, ok := embeddedIPv4(
		netip.MustParsePrefix("64:ff9b::/120"),
		netip.MustParseAddr("64:ff9b::1:2"),
	); ok {
		t.Error("an RFC 6052-invalid prefix length must not decode an IPv4 address")
	}
	if _, ok := embeddedIPv4(
		netip.MustParsePrefix("64:ff9b::/96"),
		netip.MustParseAddr("8.8.8.8"),
	); ok {
		t.Error("an IPv4 address must not be decoded as NAT64")
	}
	if _, ok := embeddedIPv4(
		netip.MustParsePrefix("64:ff9b::/96"),
		netip.MustParseAddr("64:ff9b:1::808:808"),
	); ok {
		t.Error("an address outside the prefix must not decode")
	}
}
