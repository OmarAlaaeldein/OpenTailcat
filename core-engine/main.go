package engine

import (
	"encoding/json"
	"fmt"
	"sync"
)

type EngineStats struct {
	Transport      string `json:"transport"`
	DerpRegionID   int    `json:"derpRegionId"`
	DerpRegionName string `json:"derpRegionName"`
	RTTMs          int64  `json:"rttMs"`
	JitterMs       int64  `json:"jitterMs"`
	TxBytes        int64  `json:"txBytes"`
	RxBytes        int64  `json:"rxBytes"`
}

type TailcatCore struct {
	mu        sync.Mutex
	running   bool
	tunFD     int
	token     string
	transport string
}

var globalCore = &TailcatCore{}

// GetCapabilitiesJSON is a mandatory handshake used by the Android client before it creates
// a full-device VPN route. The scaffold deliberately advertises no data plane so it can never
// be mistaken for a working WireGuard/Magicsock implementation.
func GetCapabilitiesJSON() string {
	return `{"apiVersion":1,"dataPlane":false,"wireGuard":false,"magicsock":false,"twoPhaseStart":false}`
}

// Prepare must complete the authenticated gateway and transport handshake without creating a
// device route. The scaffold always fails because it has no data plane.
func Prepare(token string) error {
	return fmt.Errorf("Tailcat data plane is not implemented in this source tree")
}

// AttachTun starts packet pumps for a prepared transport. It must be fast and atomic.
func AttachTun(tunFD int) error {
	return fmt.Errorf("Tailcat data plane is not implemented in this source tree")
}

// Stop closes all WireGuard sockets and stops packet pumps
func Stop() error {
	globalCore.mu.Lock()
	defer globalCore.mu.Unlock()

	if !globalCore.running {
		return nil
	}

	globalCore.running = false
	globalCore.tunFD = -1
	return nil
}

// GetStatsJSON returns active telemetry in JSON format
func GetStatsJSON() string {
	globalCore.mu.Lock()
	defer globalCore.mu.Unlock()

	if !globalCore.running {
		return `{"transport":"DISCONNECTED","rttMs":0,"txBytes":0,"rxBytes":0}`
	}

	stats := EngineStats{Transport: "DISCONNECTED"}

	bytes, _ := json.Marshal(stats)
	return string(bytes)
}
