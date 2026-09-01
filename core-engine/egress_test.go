package engine

import "testing"

func TestParseEgressTrace(t *testing.T) {
	ip, err := parseEgressTrace("fl=123\nip=104.28.214.49\nloc=CA\n")
	if err != nil {
		t.Fatalf("parseEgressTrace: %v", err)
	}
	if got, want := ip.String(), "104.28.214.49"; got != want {
		t.Fatalf("IP = %q, want %q", got, want)
	}
}

func TestParseEgressTraceRejectsMissingOrInvalidIP(t *testing.T) {
	for _, body := range []string{"loc=CA\n", "ip=not-an-ip\n"} {
		if _, err := parseEgressTrace(body); err == nil {
			t.Fatalf("parseEgressTrace(%q) unexpectedly succeeded", body)
		}
	}
}
