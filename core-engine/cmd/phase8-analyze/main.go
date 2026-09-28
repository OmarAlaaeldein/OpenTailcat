// Command phase8-analyze checks a Phase 8 dual capture (phone uplink +
// gateway, classic pcap). Exit status: 0 PASS, 1 FAIL, 2 usage or capture
// error, 3 INCONCLUSIVE.
package main

import (
	"errors"
	"flag"
	"fmt"
	"net/netip"
	"os"
	"strings"
	"time"

	"com.tailcat.vpn/engine/phase8"
)

func main() {
	fs := flag.NewFlagSet("phase8-analyze", flag.ContinueOnError)
	fs.Usage = func() {
		fmt.Fprintln(fs.Output(), "usage: phase8-analyze --uplink up.pcap --gateway gw.pcap --probe 1.1.1.1,8.8.8.8 --device-ip PHONE_IP --tunnel-peer GATEWAY_OR_DERP_IP[,...]")
		fs.PrintDefaults()
	}
	uplink := fs.String("uplink", "", "phone uplink capture (classic pcap)")
	gateway := fs.String("gateway", "", "gateway capture taken at the same time (classic pcap)")
	probes := fs.String("probe", "", "comma-separated probe destination IPs")
	devices := fs.String("device-ip", "", "comma-separated phone addresses on the uplink capture")
	peers := fs.String("tunnel-peer", "", "comma-separated gateway and DERP endpoint IPs the tunnel uses")
	gwSources := fs.String("gateway-src", "", "optional comma-separated gateway tunnel-side source IPs; other gateway packets are not probe hits")
	allowDNS := fs.String("allow-dns", "", "optional comma-separated resolvers the phone may query in plaintext outside the tunnel (the uplink network's own resolver); reported, not failed")
	minTunnel := fs.Int("min-tunnel-packets", 5, "uplink device->peer packets required in the probe window")
	skew := fs.Duration("max-skew", 2*time.Second, "allowed clock difference between the capture hosts")
	if err := fs.Parse(os.Args[1:]); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			os.Exit(0)
		}
		os.Exit(2)
	}
	if fs.NArg() > 0 {
		fatal("unknown arg " + fs.Arg(0))
	}
	if *uplink == "" || *probes == "" {
		fatal("need --uplink and --probe")
	}
	// Simultaneous gateway capture is mandatory. An empty/optional gateway
	// previously allowed false PASS on leaking or empty uplink (AUDIT H7).
	if *gateway == "" {
		fatal("gateway capture is required (--gateway file.pcap)")
	}
	cfg := phase8.Config{
		Probes:           addrs("--probe", *probes),
		DeviceIPs:        addrs("--device-ip", *devices),
		TunnelPeers:      addrs("--tunnel-peer", *peers),
		GatewaySources:   addrs("--gateway-src", *gwSources),
		AllowedDNS:       addrs("--allow-dns", *allowDNS),
		MinTunnelPackets: *minTunnel,
		MaxClockSkew:     *skew,
	}
	if len(cfg.DeviceIPs) == 0 || len(cfg.TunnelPeers) == 0 {
		fatal("need --device-ip and --tunnel-peer for the positive control")
	}
	uf, err := os.Open(*uplink)
	if err != nil {
		fatal(err.Error())
	}
	defer uf.Close()
	gf, err := os.Open(*gateway)
	if err != nil {
		fatal(err.Error())
	}
	defer gf.Close()
	res, err := phase8.Analyze(uf, gf, cfg)
	if err != nil {
		fatal(err.Error())
	}

	if len(res.ProbeLeaks) > 0 {
		fmt.Fprintf(os.Stderr, "FAIL uplink leak dests: %v\n", res.ProbeLeaks)
	}
	if len(res.DNSLeaks) > 0 {
		fmt.Fprintf(os.Stderr, "FAIL uplink plaintext DNS outside the tunnel to: %v\n", res.DNSLeaks)
	}
	if len(res.AllowedDNSSeen) > 0 {
		fmt.Printf("NOTE uplink plaintext DNS to allowed resolvers: %v\n", res.AllowedDNSSeen)
	}
	if len(res.ProbeLeaks) == 0 && len(res.DNSLeaks) == 0 {
		fmt.Println("PASS uplink: probe destinations and plaintext DNS absent")
	}
	if len(res.MissingOnGateway) > 0 {
		fmt.Fprintf(os.Stderr, "FAIL gateway missing probe dests: %v\n", res.MissingOnGateway)
	} else {
		fmt.Println("PASS gateway: probe destinations present")
	}
	switch res.Verdict {
	case phase8.Fail:
		fmt.Fprintln(os.Stderr, "RESULT: FAIL")
		os.Exit(1)
	case phase8.Inconclusive:
		fmt.Fprintf(os.Stderr, "INCONCLUSIVE positive control: %s\n", res.Reason)
		fmt.Fprintln(os.Stderr, "RESULT: INCONCLUSIVE (not a pass)")
		os.Exit(3)
	case phase8.Pass:
		fmt.Printf("PASS positive control: %d device->tunnel packets in the probe window\n", res.TunnelPackets)
		fmt.Println("RESULT: PASS")
	default:
		fatal("unknown verdict " + string(res.Verdict))
	}
}

func addrs(name, list string) []netip.Addr {
	var out []netip.Addr
	for _, s := range strings.Split(list, ",") {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		ip, err := netip.ParseAddr(s)
		if err != nil {
			fatal(name + ": " + err.Error())
		}
		out = append(out, ip)
	}
	return out
}

func fatal(msg string) {
	fmt.Fprintln(os.Stderr, msg)
	os.Exit(2)
}
