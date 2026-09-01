package engine

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"sync"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/net/netmon"
)

func init() {
	// On Android, /etc/resolv.conf does not exist, causing Go's pure Go resolver to query [::1]:53 or 127.0.0.1:53.
	// We configure a default DNS resolver fallback to 8.8.8.8 / 1.1.1.1 so outbound HTTP/DNS lookups succeed.
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			var d net.Dialer
			c, err := d.DialContext(ctx, "udp", "8.8.8.8:53")
			if err == nil {
				return c, nil
			}
			return d.DialContext(ctx, "udp", "1.1.1.1:53")
		},
	}

	// On Android (SDK 30+), standard netlink/net.Interfaces fails with permission denied under SEAndroid.
	// We register an InterfaceGetter fallback so Tailscale netmon and netcheck have a valid network state.
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifs, err := net.Interfaces()
		if err == nil && len(ifs) > 0 {
			ret := make([]netmon.Interface, len(ifs))
			for i := range ifs {
				ret[i].Interface = &ifs[i]
			}
			return ret, nil
		}
		return []netmon.Interface{
			{
				Interface: &net.Interface{
					Index: 1,
					Name:  "android0",
					Flags: net.FlagUp | net.FlagBroadcast | net.FlagRunning,
				},
				AltAddrs: []net.Addr{
					&net.IPNet{IP: net.ParseIP("10.0.2.15"), Mask: net.CIDRMask(24, 32)},
				},
			},
		}, nil
	})
}

// EngineStats encapsulates real measured telemetry reported to Android.
type EngineStats struct {
	Transport      string `json:"transport"`
	DerpRegionID   int    `json:"derpRegionId"`
	DerpRegionName string `json:"derpRegionName"`
	TunnelEgressIP string `json:"tunnelEgressIp,omitempty"`
	RTTMs          int64  `json:"rttMs"`
	JitterMs       int64  `json:"jitterMs"`
	TxBytes        int64  `json:"txBytes"`
	RxBytes        int64  `json:"rxBytes"`
	TxRateKbps     int64  `json:"txRateKbps"`
	RxRateKbps     int64  `json:"rxRateKbps"`
}

// TailcatCore orchestrates the two-phase lifecycle (Prepare -> AttachTun -> Stop).
type TailcatCore struct {
	mu        sync.Mutex
	running   bool
	prepared  bool
	token     *ParsedToken
	client    *tailcat.Client
	bridge    *TunBridge
	transport string
	rttMs     int64
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

	client := tailcat.NewClient(tailcat.ConnBlob(pt.CanonicalToken()))

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

	transport := "DERP_RELAY"
	rttMs := res.Latency.Milliseconds()
	discoCtx, discoCancel := context.WithTimeout(context.Background(), 4*time.Second)
	if disco, discoErr := client.DiscoPing(discoCtx); discoErr == nil {
		if disco.Endpoint != "" {
			transport = "DIRECT_P2P"
		}
		if disco.LatencySeconds > 0 {
			rttMs = int64(disco.LatencySeconds * 1_000)
		}
	}
	discoCancel()

	globalCore.token = pt
	globalCore.client = client
	globalCore.transport = transport
	globalCore.rttMs = rttMs
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

	bridge, err := NewTunBridge(
		tunFD,
		globalCore.client,
		globalCore.token,
		globalCore.transport,
		globalCore.rttMs,
	)
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
	globalCore.transport = ""
	globalCore.rttMs = 0
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
