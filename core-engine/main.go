package engine

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netmon"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

func init() {
	// On Android, /etc/resolv.conf does not exist, causing Go's pure Go resolver to query [::1]:53 or 127.0.0.1:53.
	// We configure a default DNS resolver fallback to 8.8.8.8 / 1.1.1.1 so outbound HTTP/DNS lookups succeed.
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			var d net.Dialer
			netStateMu.RLock()
			dnsList := append([]string(nil), customDNSList...)
			netStateMu.RUnlock()

			for _, s := range dnsList {
				target := s
				if _, _, err := net.SplitHostPort(target); err != nil {
					target = net.JoinHostPort(target, "53")
				}
				c, err := d.DialContext(ctx, "udp", target)
				if err == nil {
					return c, nil
				}
			}

			c, err := d.DialContext(ctx, "udp", "8.8.8.8:53")
			if err == nil {
				return c, nil
			}
			return d.DialContext(ctx, "udp", "1.1.1.1:53")
		},
	}

	// On Android (SDK 30+), standard netlink/net.Interfaces may fail under SEAndroid restrictions.
	// We query real system interfaces and allow the Android client to bridge dynamic LinkProperties
	// via UpdateNetworkState without fabricating fake static emulator interfaces.
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		netStateMu.RLock()
		if len(customIfs) > 0 {
			res := make([]netmon.Interface, len(customIfs))
			copy(res, customIfs)
			netStateMu.RUnlock()
			return res, nil
		}
		netStateMu.RUnlock()

		ifs, err := net.Interfaces()
		if err != nil {
			return nil, err
		}
		ret := make([]netmon.Interface, len(ifs))
		for i := range ifs {
			ret[i].Interface = &ifs[i]
		}
		return ret, nil
	})
}

var (
	netStateMu    sync.RWMutex
	customIfs     []netmon.Interface
	customDNSList []string
	activeMonitor *netmon.Monitor // nil until a live Tailcat client is prepared/started
)

// NetworkInterfaceInfo describes an active network interface reported by Android LinkProperties.
type NetworkInterfaceInfo struct {
	Name      string   `json:"name"`
	Addresses []string `json:"addresses"`
	Flags     string   `json:"flags,omitempty"`
	MTU       int      `json:"mtu,omitempty"`
}

// NetworkStatePayload represents dynamic network connectivity state bridged from Android.
type NetworkStatePayload struct {
	IsOnline    bool                   `json:"isOnline"`
	NetworkType string                 `json:"networkType"`
	Interfaces  []NetworkInterfaceInfo `json:"interfaces"`
	Gateways    []string               `json:"gateways,omitempty"`
	DNSServers  []string               `json:"dnsServers,omitempty"`
	DNSPolicy   string                 `json:"dnsPolicy,omitempty"`
	ForcedDNS   string                 `json:"forcedDns,omitempty"`
}

// UpdateNetworkState receives dynamic network changes from Android (LinkProperties, active network type,
// interface addresses, and routes) and injects them into Tailscale netmon to trigger path re-evaluation.
// This method is exported to Java via Go Mobile as: Engine.updateNetworkState(String).
func UpdateNetworkState(networkStateJSON string) error {
	if strings.TrimSpace(networkStateJSON) == "" {
		netStateMu.Lock()
		customIfs = nil
		customDNSList = nil
		netStateMu.Unlock()
		return nil
	}

	var payload NetworkStatePayload
	if err := json.Unmarshal([]byte(networkStateJSON), &payload); err != nil {
		return fmt.Errorf("invalid network state JSON: %w", err)
	}

	var ifs []netmon.Interface
	for i, ifInfo := range payload.Interfaces {
		if ifInfo.Name == "" {
			continue
		}

		var addrs []net.Addr
		for _, addrStr := range ifInfo.Addresses {
			addrStr = strings.TrimSpace(addrStr)
			if addrStr == "" {
				continue
			}
			// Handle CIDR notation if present (e.g. 192.168.1.50/24)
			if ip, ipNet, err := net.ParseCIDR(addrStr); err == nil {
				addrs = append(addrs, &net.IPNet{IP: ip, Mask: ipNet.Mask})
				continue
			}
			if ip := net.ParseIP(addrStr); ip != nil {
				mask := net.CIDRMask(32, 32)
				if ip.To4() == nil {
					mask = net.CIDRMask(128, 128)
				}
				addrs = append(addrs, &net.IPNet{IP: ip, Mask: mask})
			}
		}

		flags := net.FlagUp | net.FlagBroadcast | net.FlagRunning
		if strings.Contains(strings.ToUpper(ifInfo.Flags), "LOOPBACK") {
			flags |= net.FlagLoopback
		}
		if strings.Contains(strings.ToUpper(ifInfo.Flags), "POINTTOPOINT") {
			flags |= net.FlagPointToPoint
		}

		ifs = append(ifs, netmon.Interface{
			Interface: &net.Interface{
				Index: i + 1,
				Name:  ifInfo.Name,
				Flags: flags,
				MTU:   ifInfo.MTU,
			},
			AltAddrs: addrs,
		})
	}

	netStateMu.Lock()
	customIfs = ifs
	customDNSList = payload.DNSServers
	mon := activeMonitor
	netStateMu.Unlock()

	// Update active bridge DNS configuration if running
	globalCore.mu.Lock()
	if globalCore.bridge != nil {
		policy := "PROFILE_RESOLVER"
		if strings.EqualFold(payload.DNSPolicy, "FORCED_RESOLVER") {
			policy = "FORCED_RESOLVER"
		}
		var forcedAP netip.AddrPort
		if payload.ForcedDNS != "" {
			if ap, err := netip.ParseAddrPort(payload.ForcedDNS); err == nil {
				forcedAP = ap
			} else if ip, err := netip.ParseAddr(payload.ForcedDNS); err == nil {
				forcedAP = netip.AddrPortFrom(ip, 53)
			}
		}
		globalCore.bridge.SetDNSConfig(DNSConfig{
			Policy:    policy,
			ForcedDNS: forcedAP,
		})
	}
	globalCore.mu.Unlock()

	// Notify active netmon monitor to trigger Magicsock path and endpoint re-evaluation
	if mon != nil {
		mon.InjectEvent()
	}

	return nil
}

// DropCounters records packet drops and flow rejections by category.
type DropCounters struct {
	MalformedIP      int64 `json:"malformedIp"`
	MTUExceeded      int64 `json:"mtuExceeded"`
	QueueExhaustion  int64 `json:"queueExhaustion"`
	PolicyRejections int64 `json:"policyRejections"`
}

// EngineStats encapsulates authoritative measured telemetry reported to Android.
type EngineStats struct {
	Version                 int          `json:"version"`
	SessionID               int64        `json:"sessionId"`
	State                   string       `json:"state"`
	Transport               string       `json:"transport"`
	DirectEndpoint          string       `json:"directEndpoint,omitempty"`
	DerpRegionID            int          `json:"derpRegionId"`
	DerpRegionCode          string       `json:"derpRegionCode,omitempty"`
	DerpRegionName          string       `json:"derpRegionName"`
	TunnelEgressIP          string       `json:"tunnelEgressIp,omitempty"`
	RTTMs                   int64        `json:"rttMs"`
	JitterMs                *int64       `json:"jitterMs"`
	LastHandshakeSec        int64        `json:"lastHandshakeSec"`
	WireguardTxBytes        int64        `json:"wireguardTxBytes"`
	WireguardRxBytes        int64        `json:"wireguardRxBytes"`
	TunTxBytes              int64        `json:"tunTxBytes"`
	TunRxBytes              int64        `json:"tunRxBytes"`
	TxBytes                 int64        `json:"txBytes"`
	RxBytes                 int64        `json:"rxBytes"`
	TxRateKbps              int64        `json:"txRateKbps"`
	RxRateKbps              int64        `json:"rxRateKbps"`
	TCPPackets              int64        `json:"tcpPackets"`
	UDPPackets              int64        `json:"udpPackets"`
	DNSQueries              int64        `json:"dnsQueries"`
	DropCounters            DropCounters `json:"dropCounters"`
	EgressAuditTimestampSec int64        `json:"egressAuditTimestampSec,omitempty"`
	EgressAuditError        string       `json:"egressAuditError,omitempty"`
}

// TailcatCore orchestrates the two-phase lifecycle (Prepare -> AttachTun -> Stop).
type TailcatCore struct {
	mu        sync.Mutex
	sessionID int64
	running   bool
	prepared  bool
	token     *ParsedToken
	client    preparedClient
	bridge    *TunBridge
	transport string
	rttMs     int64
}

var globalCore = &TailcatCore{}

type preparedClient interface {
	TunnelClient
	Ping(context.Context) (tailcat.PingResult, error)
	DiscoPing(context.Context) (*ipnstate.PingResult, error)
	HasServerCap(uint8) bool
	NetMon() *netmon.Monitor
	Status() *ipnstate.Status
	ServerNodeKey() key.NodePublic
	DERPMap() *tailcfg.DERPMap
}

var newTailcatClient = func(blob tailcat.ConnBlob) preparedClient {
	return tailcat.NewClient(blob)
}

// Capabilities represents the native data-plane capability contract (API v2).
type Capabilities struct {
	APIVersion          int  `json:"apiVersion"`
	DataPlane           bool `json:"dataPlane"`
	WireGuard           bool `json:"wireGuard"`
	Magicsock           bool `json:"magicsock"`
	TwoPhaseStart       bool `json:"twoPhaseStart"`
	IPv4                bool `json:"ipv4"`
	IPv6                bool `json:"ipv6"`
	TCP                 bool `json:"tcp"`
	UDP                 bool `json:"udp"`
	DNS                 bool `json:"dns"`
	LiveStats           bool `json:"liveStats"`
	CancelSafeLifecycle bool `json:"cancelSafeLifecycle"`
}

// GetCapabilitiesJSON returns the capability contract.
// Under Phase 0 fail-closed semantics, every unproven capability stays false
// until its promotion-table evidence in handoff.md exists. Implementation
// present for DNS routing, telemetry, or two-phase start is not promotion.
func GetCapabilitiesJSON() string {
	caps := Capabilities{
		APIVersion:          2,
		DataPlane:           false,
		WireGuard:           false,
		Magicsock:           false,
		TwoPhaseStart:       false,
		IPv4:                false,
		IPv6:                false,
		TCP:                 false,
		UDP:                 false,
		DNS:                 false,
		LiveStats:           false,
		CancelSafeLifecycle: false,
	}
	bytes, err := json.Marshal(caps)
	if err != nil {
		return `{"apiVersion":2,"dataPlane":false}`
	}
	return string(bytes)
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
		return fmt.Errorf("token rejected: %w", err)
	}
	if !pt.IsConnectable() {
		return fmt.Errorf("token classification %s cannot be used for connection: %s", pt.Classification, pt.ErrorMessage)
	}

	// Pass the exact original validated official token bytes directly to upstream Tailcat
	client := newTailcatClient(tailcat.ConnBlob(pt.RawToken))

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

	if !client.HasServerCap(tailcat.CapExitUDP) {
		client.Close()
		return errors.New("gateway does not support tunneled UDP data plane (TCP-only exit node)")
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
	globalCore.sessionID++
	globalCore.prepared = true

	netStateMu.Lock()
	if nm := client.NetMon(); nm != nil {
		activeMonitor = nm
	}
	netStateMu.Unlock()

	return nil
}

// AttachTun connects the Android TUN file descriptor and starts packet pumps.
func AttachTun(tunFD int) error {
	globalCore.mu.Lock()
	defer globalCore.mu.Unlock()

	if !globalCore.prepared || globalCore.client == nil || globalCore.token == nil {
		return errors.New("cannot attach TUN: engine not prepared")
	}

	bridge, err := newTunBridge(
		tunFD,
		globalCore.client,
		globalCore.token,
		globalCore.transport,
		globalCore.rttMs,
		globalCore.sessionID,
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

	netStateMu.Lock()
	activeMonitor = nil
	netStateMu.Unlock()

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
		sessionID := globalCore.sessionID
		state := "STOPPED"
		if globalCore.prepared {
			state = "PREPARED"
		}
		stats := EngineStats{
			Version:   2,
			SessionID: sessionID,
			State:     state,
			Transport: "DISCONNECTED",
		}
		b, _ := json.Marshal(stats)
		return string(b)
	}

	stats := globalCore.bridge.GetStats()
	bytes, err := json.Marshal(stats)
	if err != nil {
		return `{"version":2,"state":"ERROR","transport":"UNKNOWN","rttMs":0,"txBytes":0,"rxBytes":0}`
	}
	return string(bytes)
}
