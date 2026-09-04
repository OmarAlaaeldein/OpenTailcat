package engine

import (
	"net/netip"
	"testing"
)

func TestEncodeAndParseDNSA(t *testing.T) {
	q, err := encodeDNSQueryA("speed.cloudflare.com")
	if err != nil {
		t.Fatal(err)
	}
	if len(q) < 12 {
		t.Fatalf("query too short: %d", len(q))
	}
	want := netip.MustParseAddr("1.2.3.4")
	msg := buildDNSResponse(0x1111, "speed.cloudflare.com", false, want, 0)
	got, err := parseDNSA(msg)
	if err != nil {
		t.Fatal(err)
	}
	if got != want {
		t.Fatalf("got %s want %s", got, want)
	}
}

func TestParseDNSARejectsTruncated(t *testing.T) {
	if _, err := parseDNSA([]byte{0, 1, 2}); err == nil {
		t.Fatal("expected error")
	}
}
