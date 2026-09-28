// pins prints the verified chains (leaf to root) and base64 SPKI SHA-256 pins
// for the hosts OpenTailcat pins, over IPv4 and IPv6. Use it when updating
// core-engine/tls_pin.go and core/tls/SpkiPins.kt:
//
//	cd scripts/tls/pins && go run .
package main

import (
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"fmt"
	"net"
	"time"
)

func main() {
	targets := []struct{ addr, sni string }{
		{"1.1.1.1:443", ""},
		{"1.0.0.1:443", ""},
		{"one.one.one.one:443", "one.one.one.one"},
		{"cloudflare-dns.com:443", "cloudflare-dns.com"},
		{"speed.cloudflare.com:443", "speed.cloudflare.com"},
	}
	for _, t := range targets {
		for _, network := range []string{"tcp4", "tcp6"} {
			name := t.sni
			if name == "" {
				name, _, _ = net.SplitHostPort(t.addr)
			}
			d := &net.Dialer{Timeout: 5 * time.Second}
			conn, err := tls.DialWithDialer(d, network, t.addr, &tls.Config{ServerName: name})
			if err != nil {
				fmt.Printf("=== %s %s: %v\n", t.addr, network, err)
				continue
			}
			cs := conn.ConnectionState()
			fmt.Printf("=== %s %s (verified chains: %d)\n", t.addr, network, len(cs.VerifiedChains))
			for i, chain := range cs.VerifiedChains {
				for j, c := range chain {
					sum := sha256.Sum256(c.RawSubjectPublicKeyInfo)
					fmt.Printf("  chain %d [%d] %s | CN=%s | issuer CN=%s | until %s\n", i, j,
						base64.StdEncoding.EncodeToString(sum[:]), c.Subject.CommonName, c.Issuer.CommonName,
						c.NotAfter.Format("2006-01-02"))
				}
			}
			conn.Close()
		}
	}
}
