package engine

import (
	"testing"
	"time"
)

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

func TestEgressRetryDelayBacksOffToCap(t *testing.T) {
	for _, tc := range []struct {
		attempt int
		want    time.Duration
	}{
		{1, 3 * time.Second},
		{2, 6 * time.Second},
		{3, 12 * time.Second},
		{7, 192 * time.Second},
		{8, 5 * time.Minute},
		{1000, 5 * time.Minute},
	} {
		if got := egressRetryDelay(tc.attempt); got != tc.want {
			t.Errorf("egressRetryDelay(%d) = %s, want %s", tc.attempt, got, tc.want)
		}
	}
}
