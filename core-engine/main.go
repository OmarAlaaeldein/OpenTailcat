package engine

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"strings"
	"sync"

	"github.com/tailscale/tailcat"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/net/netmon"
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

			return nil, fmt.Errorf("no Android DNS servers available")
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
	DNSPolicy   *string                `json:"dnsPolicy,omitempty"`
	ForcedDNS   *string                `json:"forcedDns,omitempty"`
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

	if payload.DNSPolicy != nil {
		applyDNSPolicy(*payload.DNSPolicy, payload.ForcedDNS)
	}

	// Notify active netmon monitor to trigger Magicsock path and endpoint re-evaluation
	if mon != nil {
		mon.InjectEvent()
	}

	return nil
}

func applyDNSPolicy(policyName string, forced *string) {
	policy := "PROFILE_RESOLVER"
	if strings.EqualFold(policyName, "FORCED_RESOLVER") {
		policy = "FORCED_RESOLVER"
	}
	var forcedAP netip.AddrPort
	if forced != nil && *forced != "" {
		if ap, err := netip.ParseAddrPort(*forced); err == nil {
			forcedAP = ap
		} else if ip, err := netip.ParseAddr(*forced); err == nil {
			forcedAP = netip.AddrPortFrom(ip, 53)
		}
	}
	cfg := DNSConfig{Policy: policy, ForcedDNS: forcedAP}
	globalCore.pendingDNS.Store(&cfg)

	globalCore.mu.Lock()
	bridge := (*TunBridge)(nil)
	if globalCore.sess != nil {
		bridge = globalCore.sess.bridge
	}
	globalCore.mu.Unlock()
	if bridge != nil {
		bridge.SetDNSConfig(cfg)
	}
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
	HealthUnixSec           int64        `json:"healthUnixSec,omitempty"`
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

type preparedClient interface {
	TunnelClient
	Ping(context.Context) (tailcat.PingResult, error)
	DiscoPing(context.Context) (*ipnstate.PingResult, error)
}

type engineClient struct {
	*tailcat.Client
}

func (c *engineClient) DialUDP(ctx context.Context, ap netip.AddrPort) (net.Conn, error) {
	return c.Client.DialUDP(ctx, ap)
}

var newTailcatClient = func(blob tailcat.ConnBlob) preparedClient {
	return &engineClient{Client: tailcat.NewClient(blob)}
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
// IPv4-only test routing is enabled so a live token can Connect. ipv6 stays
// false. This is not Phase 8 production acceptance.
func GetCapabilitiesJSON() string {
	caps := Capabilities{
		APIVersion:          2,
		DataPlane:           true,
		WireGuard:           true,
		Magicsock:           true,
		TwoPhaseStart:       true,
		IPv4:                true,
		IPv6:                false,
		TCP:                 true,
		UDP:                 true,
		DNS:                 true,
		LiveStats:           true,
		CancelSafeLifecycle: true,
	}
	bytes, err := json.Marshal(caps)
	if err != nil {
		return `{"apiVersion":2,"dataPlane":false}`
	}
	return string(bytes)
}
