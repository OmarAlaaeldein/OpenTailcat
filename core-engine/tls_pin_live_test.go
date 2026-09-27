//go:build liveprobe

package engine

import (
	"crypto/tls"
	"net"
	"testing"
	"time"
)

// Direct (not tunneled) handshakes with the pinned hosts, to catch a
// Cloudflare CA change before users do.
func TestLivePinnedHostsHandshake(t *testing.T) {
	for _, c := range []struct{ addr, sni string }{
		{"1.1.1.1:443", "1.1.1.1"},
		{"one.one.one.one:443", "one.one.one.one"},
		{"speed.cloudflare.com:443", "speed.cloudflare.com"},
	} {
		// The package init replaces net.DefaultResolver with Android's list,
		// which is empty on a host; use the system resolver here.
		d := &net.Dialer{Timeout: 5 * time.Second, Resolver: &net.Resolver{}}
		conn, err := tls.DialWithDialer(d, "tcp4", c.addr, pinnedTLSConfig(c.sni))
		if err != nil {
			t.Errorf("%s: %v", c.addr, err)
			continue
		}
		_ = conn.Close()
	}
}
