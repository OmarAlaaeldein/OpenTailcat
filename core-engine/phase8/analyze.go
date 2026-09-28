package phase8

import (
	"errors"
	"fmt"
	"io"
	"net/netip"
	"slices"
	"time"
)

// Config describes one dual-capture run.
type Config struct {
	Probes      []netip.Addr // destinations a second app contacted while connected
	DeviceIPs   []netip.Addr // the phone's addresses as seen on the uplink capture
	TunnelPeers []netip.Addr // gateway and DERP endpoints the tunnel uses
	// GatewaySources, when set, counts only gateway packets from these
	// addresses (the gateway's tunnel-side egress address) as probe hits.
	GatewaySources []netip.Addr
	// MinTunnelPackets is the positive control: uplink packets from the
	// phone to a tunnel peer required inside the gateway probe window.
	MinTunnelPackets int
	// MaxClockSkew is the allowed clock difference between the two capture
	// hosts when their time windows are compared.
	MaxClockSkew time.Duration
}

// Verdict is the analyzer outcome.
type Verdict string

const (
	Pass         Verdict = "PASS"
	Fail         Verdict = "FAIL"
	Inconclusive Verdict = "INCONCLUSIVE"
)

// Result reports each check. Verdict is FAIL when a leak or a missing
// gateway probe was found, INCONCLUSIVE when the uplink capture cannot show
// the phone's tunnel traffic during the probes, and PASS otherwise.
type Result struct {
	Verdict Verdict
	// ProbeLeaks are probe addresses seen on the uplink, any port.
	ProbeLeaks []netip.Addr
	// DNSLeaks are servers the phone sent plaintext DNS to outside the tunnel.
	DNSLeaks []netip.Addr
	// MissingOnGateway are probes with no non-DNS packet on the gateway.
	MissingOnGateway []netip.Addr
	// ProbeWindow spans the gateway probe hits.
	ProbeWindowStart, ProbeWindowEnd time.Time
	// TunnelPackets counts uplink phone->peer packets in the probe window.
	TunnelPackets int
	// Reason explains an INCONCLUSIVE verdict.
	Reason string
}

// Analyze reads the uplink and gateway captures and applies cfg.
func Analyze(uplink, gateway io.Reader, cfg Config) (*Result, error) {
	if len(cfg.Probes) == 0 {
		return nil, errors.New("no probe addresses")
	}
	if len(cfg.DeviceIPs) == 0 {
		return nil, errors.New("no device address: the positive control needs the phone's uplink IP")
	}
	if len(cfg.TunnelPeers) == 0 {
		return nil, errors.New("no tunnel peer: the positive control needs the gateway/DERP endpoint")
	}
	if cfg.MinTunnelPackets < 1 {
		cfg.MinTunnelPackets = 1
	}
	up, err := ReadCapture(uplink)
	if err != nil {
		return nil, fmt.Errorf("uplink: %w", err)
	}
	gw, err := ReadCapture(gateway)
	if err != nil {
		return nil, fmt.Errorf("gateway: %w", err)
	}

	res := &Result{}
	leaks := map[netip.Addr]bool{}
	dnsLeaks := map[netip.Addr]bool{}
	for _, p := range up.Packets {
		for _, a := range []netip.Addr{p.Src, p.Dst} {
			if slices.Contains(cfg.Probes, a) {
				leaks[a] = true
			}
		}
		if !p.IsDNS() {
			continue
		}
		// Plaintext DNS between the phone and anything but a tunnel peer
		// left the tunnel.
		if p.DstPort == 53 && slices.Contains(cfg.DeviceIPs, p.Src) && !slices.Contains(cfg.TunnelPeers, p.Dst) {
			dnsLeaks[p.Dst] = true
		}
		if p.SrcPort == 53 && slices.Contains(cfg.DeviceIPs, p.Dst) && !slices.Contains(cfg.TunnelPeers, p.Src) {
			dnsLeaks[p.Src] = true
		}
	}
	res.ProbeLeaks = sortedAddrs(leaks)
	res.DNSLeaks = sortedAddrs(dnsLeaks)

	// The gateway resolver's own DNS to a probe address is not a probe hit.
	hit := map[netip.Addr]bool{}
	for _, p := range gw.Packets {
		if p.IsDNS() || !slices.Contains(cfg.Probes, p.Dst) {
			continue
		}
		if len(cfg.GatewaySources) > 0 && !slices.Contains(cfg.GatewaySources, p.Src) {
			continue
		}
		hit[p.Dst] = true
		if res.ProbeWindowStart.IsZero() || p.Time.Before(res.ProbeWindowStart) {
			res.ProbeWindowStart = p.Time
		}
		if p.Time.After(res.ProbeWindowEnd) {
			res.ProbeWindowEnd = p.Time
		}
	}
	for _, a := range cfg.Probes {
		if !hit[a] && !slices.Contains(res.MissingOnGateway, a) {
			res.MissingOnGateway = append(res.MissingOnGateway, a)
		}
	}

	if len(res.ProbeLeaks) > 0 || len(res.DNSLeaks) > 0 || len(res.MissingOnGateway) > 0 {
		res.Verdict = Fail
		return res, nil
	}

	// Positive control: the uplink must have been recording the phone's
	// tunnel traffic while the gateway saw the probes. Otherwise an idle
	// interface, the wrong vantage point or a stale capture would pass.
	skew := cfg.MaxClockSkew
	from, to := res.ProbeWindowStart.Add(-skew), res.ProbeWindowEnd.Add(skew)
	upFirst, upLast := up.Packets[0].Time, up.Packets[0].Time
	for _, p := range up.Packets {
		if p.Time.Before(upFirst) {
			upFirst = p.Time
		}
		if p.Time.After(upLast) {
			upLast = p.Time
		}
		if slices.Contains(cfg.DeviceIPs, p.Src) && slices.Contains(cfg.TunnelPeers, p.Dst) &&
			!p.Time.Before(from) && !p.Time.After(to) {
			res.TunnelPackets++
		}
	}
	switch {
	case upFirst.After(res.ProbeWindowStart.Add(skew)) || upLast.Before(res.ProbeWindowEnd.Add(-skew)):
		res.Verdict = Inconclusive
		res.Reason = fmt.Sprintf("uplink capture %s..%s does not cover the gateway probe window %s..%s (max skew %s)",
			stamp(upFirst), stamp(upLast), stamp(res.ProbeWindowStart), stamp(res.ProbeWindowEnd), skew)
	case res.TunnelPackets < cfg.MinTunnelPackets:
		res.Verdict = Inconclusive
		res.Reason = fmt.Sprintf("uplink has %d packets from the device to a tunnel peer in the probe window, need %d: wrong capture point, wrong --device-ip/--tunnel-peer, or the phone was not connected",
			res.TunnelPackets, cfg.MinTunnelPackets)
	default:
		res.Verdict = Pass
	}
	return res, nil
}

func sortedAddrs(m map[netip.Addr]bool) []netip.Addr {
	var out []netip.Addr
	for a := range m {
		out = append(out, a)
	}
	slices.SortFunc(out, func(a, b netip.Addr) int { return a.Compare(b) })
	return out
}

func stamp(t time.Time) string {
	return t.UTC().Format("2006-01-02T15:04:05.000Z")
}
