//go:build liveprobe

package engine

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

// TestLiveTransportThroughput measures prepare transport (DERP vs direct),
// tcpOnly, ipv6Egress, and a short tunnel download/upload through Client.DialTCP.
// Requires a live token; skips nothing — fails if the gateway is unreachable.
func TestLiveTransportThroughput(t *testing.T) {
	token := loadLiveToken(t)
	start := time.Now()
	if err := Prepare(token); err != nil {
		t.Fatalf("Prepare: %v", err)
	}
	t.Cleanup(func() { _ = Stop() })
	t.Logf("Prepare ok in %v", time.Since(start))

	statsRaw := GetStatsJSON()
	t.Log("stats:", statsRaw)
	var parsed map[string]any
	if err := json.Unmarshal([]byte(statsRaw), &parsed); err != nil {
		t.Fatal(err)
	}
	t.Logf("state=%v transport=%v tcpOnly=%v ipv6Egress=%v rttMs=%v derpRegion=%v directEndpoint=%v egress=%v",
		parsed["state"], parsed["transport"], parsed["tcpOnly"], parsed["ipv6Egress"],
		parsed["rttMs"], parsed["derpRegionCode"], parsed["directEndpoint"], parsed["tunnelEgressIp"])

	// state must be RUNNING for MeasureTunnel* helpers (requires attached pumps
	// on device). On host we exercise the same DialTCP path without TUN.
	client := globalCore.sess.client
	if client == nil {
		t.Fatal("prepared session has nil client")
	}

	// Throughput sample via the same helpers used by Android speed test, but
	// only if the engine is already RUNNING (device attach). On host, skip
	// MeasureTunnel* and use dialTCP path below.
	if globalCore.state == StateRunning {
		if ms, err := MeasureTunnelPingMS(); err == nil {
			t.Logf("tunnel ping: %d ms", ms)
		} else {
			t.Logf("tunnel ping error: %v", err)
		}
		if mbps, err := MeasureTunnelDownloadMbps(); err == nil {
			t.Logf("tunnel download: %.2f Mbps", mbps)
		} else {
			t.Logf("tunnel download error: %v", err)
		}
		if mbps, err := MeasureTunnelUploadMbps(); err == nil {
			t.Logf("tunnel upload: %.2f Mbps", mbps)
		} else {
			t.Logf("tunnel upload error: %v", err)
		}
	}

	// Host path: short download through DialTCP to speed.cloudflare.com.
	host, err := lookupSpeedHost(client)
	if err != nil {
		t.Fatalf("resolve speed host: %v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	n, elapsed, err := tunnelHTTPRead(ctx, client, host+":443", "speed.cloudflare.com", "/__down?bytes=25000000", 5*time.Second)
	if err != nil {
		t.Fatalf("download: %v", err)
	}
	if elapsed <= 0 || n <= 0 {
		t.Fatalf("download returned no data (n=%d elapsed=%v)", n, elapsed)
	}
	mbps := float64(n) * 8 / elapsed.Seconds() / 1_000_000
	t.Logf("DialTCP download: %d bytes in %v => %.2f Mbps", n, elapsed, mbps)
}
