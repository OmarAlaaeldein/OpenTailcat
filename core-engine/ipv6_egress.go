package engine

import (
	"context"
	"crypto/tls"
	"errors"
	"log"
	"net/netip"
	"time"
)

// Well-known Cloudflare DNS anycast IPv6 used to prove gateway IPv6 WAN.
// DialTCP through Tailcat can succeed to the gateway before the remote dial
// completes; a TLS handshake is required to confirm end-to-end IPv6 egress.
var ipv6EgressProbeDst = netip.MustParseAddrPort("[2606:4700:4700::1111]:443")

const ipv6EgressProbeTimeout = 3 * time.Second

// probeIPv6Egress reports whether the gateway can complete an authenticated
// TCP/TLS exchange to a public IPv6 destination through Tailcat DialTCP.
// Returns false on any failure (no IPv6 WAN, filtered SNI, slow path, etc.).
func probeIPv6Egress(ctx context.Context, client TunnelClient) bool {
	if client == nil {
		return false
	}
	probeCtx, cancel := context.WithTimeout(ctx, ipv6EgressProbeTimeout)
	defer cancel()

	conn, err := client.DialTCP(probeCtx, ipv6EgressProbeDst)
	if err != nil || isNilConn(conn) {
		if err == nil {
			err = errors.New("gateway dial returned nil connection without error")
		}
		log.Printf("Tailcat IPv6 egress probe dial failed: %v", err)
		return false
	}
	defer closeConn(conn)

	if deadline, ok := probeCtx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}

	// SNI must be a hostname Cloudflare presents; dialing the literal alone is
	// not enough because DialTCP returns once the tunnel TCP is up.
	tlsConn := tls.Client(conn, pinnedTLSConfig("one.one.one.one"))
	if err := tlsConn.HandshakeContext(probeCtx); err != nil {
		log.Printf("Tailcat IPv6 egress probe TLS failed: %v", err)
		return false
	}
	_ = tlsConn.Close()
	return true
}

// isPublicIPv6Destination reports destinations that need gateway IPv6 WAN.
// ULA/link-local/etc. are not Internet Happy-Eyeballs targets.
func isPublicIPv6Destination(dst netip.AddrPort) bool {
	ip := dst.Addr()
	if !ip.IsValid() || ip.Is4() || ip.Is4In6() {
		return false
	}
	ip = ip.Unmap()
	if !ip.Is6() {
		return false
	}
	if ip.IsLoopback() || ip.IsMulticast() || ip.IsLinkLocalUnicast() ||
		ip.IsLinkLocalMulticast() || ip.IsPrivate() || ip.IsUnspecified() {
		return false
	}
	return true
}
