package engine

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/tailscale/tailcat"
)

// EngineStats encapsulates real measured telemetry reported to Android.
type EngineStats struct {
	Transport      string `json:"transport"`
	DerpRegionID   int    `json:"derpRegionId"`
	DerpRegionName string `json:"derpRegionName"`
	RTTMs          int64  `json:"rttMs"`
	JitterMs       int64  `json:"jitterMs"`
	TxBytes        int64  `json:"txBytes"`
	RxBytes        int64  `json:"rxBytes"`
	TxRateKbps     int64  `json:"txRateKbps"`
	RxRateKbps     int64  `json:"rxRateKbps"`
}

// TailcatCore orchestrates the two-phase lifecycle (Prepare -> AttachTun -> Stop).
type TailcatCore struct {
	mu       sync.Mutex
	running  bool
	prepared bool
	token    *ParsedToken
	client   *tailcat.Client
	bridge   *TunBridge
}

var globalCore = &TailcatCore{}

// GetCapabilitiesJSON returns the two-phase startup capability contract.
func GetCapabilitiesJSON() string {
	return `{"apiVersion":1,"dataPlane":true,"wireGuard":true,"magicsock":true,"twoPhaseStart":true}`
}

// Prepare completes token validation, initializes the client, and verifies the
// gateway handshake (Meow ping/pong) before Android creates a route.
func Prepare(tokenStr string) error {
	globalCore.mu.Lock()
	defer globalCore.mu.Unlock()

	// If already running, clean up previous session
	if globalCore.running {
		if globalCore.bridge != nil {
			globalCore.bridge.Stop()
			globalCore.bridge = nil
		}
		if globalCore.client != nil {
			globalCore.client.Close()
			globalCore.client = nil
		}
		globalCore.running = false
		globalCore.prepared = false
	}

	pt, err := ParseToken(tokenStr)
	if err != nil {
		return fmt.Errorf("invalid token: %w", err)
	}

	client := tailcat.NewClient(tailcat.ConnBlob(pt.RawToken))

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	res, err := client.Ping(ctx)
	if err != nil {
		client.Close()
		return fmt.Errorf("gateway handshake failed: %w", err)
	}

	if res.Latency <= 0 {
		client.Close()
		return fmt.Errorf("invalid reachability latency from gateway")
	}

	globalCore.token = pt
	globalCore.client = client
	globalCore.prepared = true
	return nil
}

// AttachTun connects the Android TUN file descriptor and starts packet pumps.
func AttachTun(tunFD int) error {
	globalCore.mu.Lock()
	defer globalCore.mu.Unlock()

	if !globalCore.prepared || globalCore.client == nil || globalCore.token == nil {
		return errors.New("cannot attach TUN: engine not prepared")
	}

	bridge, err := NewTunBridge(tunFD, globalCore.client, globalCore.token)
	if err != nil {
		return fmt.Errorf("create tun bridge: %w", err)
	}

	if err := bridge.Start(); err != nil {
		bridge.Stop()
		return fmt.Errorf("start tun bridge: %w", err)
	}

	globalCore.bridge = bridge
	globalCore.running = true
	return nil
}

// Stop closes all WireGuard sockets, packet pumps, and releases secrets.
func Stop() error {
	globalCore.mu.Lock()
	defer globalCore.mu.Unlock()

	if globalCore.bridge != nil {
		globalCore.bridge.Stop()
		globalCore.bridge = nil
	}
	if globalCore.client != nil {
		globalCore.client.Close()
		globalCore.client = nil
	}

	globalCore.running = false
	globalCore.prepared = false
	globalCore.token = nil
	return nil
}

// GetStatsJSON returns measured active telemetry in JSON format.
func GetStatsJSON() string {
	globalCore.mu.Lock()
	defer globalCore.mu.Unlock()

	if !globalCore.running || globalCore.bridge == nil {
		return `{"transport":"DISCONNECTED","derpRegionId":null,"derpRegionName":null,"rttMs":0,"jitterMs":0,"txBytes":0,"rxBytes":0,"txRateKbps":0,"rxRateKbps":0}`
	}

	stats := globalCore.bridge.GetStats()
	bytes, err := json.Marshal(stats)
	if err != nil {
		return `{"transport":"UNKNOWN","rttMs":0,"txBytes":0,"rxBytes":0}`
	}
	return string(bytes)
}
