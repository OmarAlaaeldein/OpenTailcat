package engine

import (
	"context"
	"net/netip"
	"testing"
)

func TestIsPublicIPv6Destination(t *testing.T) {
	cases := []struct {
		ap   string
		want bool
	}{
		{"[2606:4700:4700::1111]:443", true},
		{"[2001:4860:4860::8888]:53", true},
		{"[fd7a:115c:a1e0::2]:443", false}, // ULA TUN
		{"[fe80::1]:443", false},
		{"[::1]:443", false},
		{"1.1.1.1:443", false},
		{"[::ffff:1.1.1.1]:443", false},
	}
	for _, tc := range cases {
		got := isPublicIPv6Destination(netip.MustParseAddrPort(tc.ap))
		if got != tc.want {
			t.Errorf("%s: got %v want %v", tc.ap, got, tc.want)
		}
	}
}

func TestProbeIPv6EgressNilClient(t *testing.T) {
	if probeIPv6Egress(context.Background(), nil) {
		t.Fatal("nil client must not report IPv6 egress")
	}
}
