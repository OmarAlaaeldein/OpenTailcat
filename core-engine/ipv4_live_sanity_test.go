//go:build liveprobe

package engine

import (
	"context"
	"crypto/tls"
	"net/netip"
	"testing"
	"time"
)

func TestLiveIPv4EgressStillWorks(t *testing.T) {
	token := loadLiveToken(t)
	if err := Prepare(token); err != nil {
		t.Fatalf("Prepare: %v", err)
	}
	t.Cleanup(func() { _ = Stop() })

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	conn, err := globalCore.sess.client.DialTCP(ctx, netip.MustParseAddrPort("1.1.1.1:443"))
	if err != nil || isNilConn(conn) {
		t.Fatalf("IPv4 DialTCP: %v", err)
	}
	defer closeConn(conn)
	_ = conn.SetDeadline(time.Now().Add(8 * time.Second))
	tlsConn := tls.Client(conn, pinnedTLSConfig("1.1.1.1"))
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		t.Fatalf("IPv4 TLS: %v", err)
	}
	t.Log("IPv4 TLS handshake through gateway OK")
}
