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

		// So is a NAT64-embedded one.
		{"[64:ff9b::7f00:1]:80", false, errBlockedLocal},
		{"[64:ff9b::a9fe:a9fe]:80", true, errBlockedAlways},
		{"[64:ff9b::808:808]:80", false, nil},

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
