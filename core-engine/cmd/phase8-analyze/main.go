package main

import (
	"fmt"
	"net/netip"
	"os"
	"strings"

	engine "com.tailcat.vpn/engine"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintf(os.Stderr, "usage: phase8-analyze --uplink file.pcap --probe 1.1.1.1,8.8.8.8 [--gateway file.pcap]\n")
		os.Exit(2)
	}
	var uplink, gateway string
	var probes []netip.Addr
	for i := 1; i < len(os.Args); i++ {
		switch os.Args[i] {
		case "--uplink":
			i++
			if i >= len(os.Args) {
				fatal("missing --uplink path")
			}
			uplink = os.Args[i]
		case "--gateway":
			i++
			if i >= len(os.Args) {
				fatal("missing --gateway path")
			}
			gateway = os.Args[i]
		case "--probe":
			i++
			if i >= len(os.Args) {
				fatal("missing --probe list")
			}
			for _, p := range strings.Split(os.Args[i], ",") {
				p = strings.TrimSpace(p)
				if p == "" {
					continue
				}
				ip, err := netip.ParseAddr(p)
				if err != nil {
					fatal("probe IP: " + err.Error())
				}
				probes = append(probes, ip)
			}
		default:
			fatal("unknown arg " + os.Args[i])
		}
	}
	if uplink == "" || len(probes) == 0 {
		fatal("need --uplink and --probe")
	}
	uf, err := os.Open(uplink)
	if err != nil {
		fatal(err.Error())
	}
	defer uf.Close()
	leaked, err := engine.ProbeIPsOnUplink(uf, probes)
	if err != nil {
		fatal(err.Error())
	}
	if len(leaked) > 0 {
		fmt.Fprintf(os.Stderr, "FAIL uplink leak dests: %v\n", leaked)
		os.Exit(1)
	}
	fmt.Println("PASS uplink: probe destinations absent")
	if gateway == "" {
		return
	}
	gf, err := os.Open(gateway)
	if err != nil {
		fatal(err.Error())
	}
	defer gf.Close()
	found, err := engine.ProbeIPsPresent(gf, probes)
	if err != nil {
		fatal(err.Error())
	}
	var missing []netip.Addr
	for ip, ok := range found {
		if !ok {
			missing = append(missing, ip)
		}
	}
	if len(missing) > 0 {
		fmt.Fprintf(os.Stderr, "FAIL gateway missing probe dests: %v\n", missing)
		os.Exit(1)
	}
	fmt.Println("PASS gateway: probe destinations present")
}

func fatal(msg string) {
	fmt.Fprintln(os.Stderr, msg)
	os.Exit(2)
}