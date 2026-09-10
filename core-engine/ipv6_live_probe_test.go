//go:build liveprobe

package engine

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
	"time"
)

func TestLiveIPv6EgressProbe(t *testing.T) {
	raw, err := os.ReadFile("/workspace/.opentailcat-private/live-token.txt")
	if err != nil {
		t.Fatal(err)
	}
	token := strings.TrimSpace(string(raw))
	t.Log("capabilities:", GetCapabilitiesJSON())
	start := time.Now()
	if err := Prepare(token); err != nil {
		t.Fatalf("Prepare: %v", err)
	}
	t.Cleanup(func() { _ = Stop() })
	t.Logf("Prepare ok in %v", time.Since(start))
	stats := GetStatsJSON()
	t.Log("stats:", stats)
	var parsed map[string]any
	if err := json.Unmarshal([]byte(stats), &parsed); err != nil {
		t.Fatal(err)
	}
	t.Logf("ipv6Egress=%v tcpOnly=%v state=%v transport=%v",
		parsed["ipv6Egress"], parsed["tcpOnly"], parsed["state"], parsed["transport"])
}
