package engine

import (
	"bufio"
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/netip"
	"strings"
	"time"
)

const maxEgressProbeBody = 16 << 10

// probeTunnelEgressIP performs the audit through Client.DialTCP, so the result
// is the gateway's public egress rather than the Android app UID's bypass path.
func (b *TunBridge) probeTunnelEgressIP(ctx context.Context) (netip.Addr, error) {
	if ip, err := b.probeIPify(ctx); err == nil {
		return ip, nil
	}

	cloudflare := netip.MustParseAddrPort("1.1.1.1:443")
	conn, err := b.client.DialTCP(ctx, cloudflare)
	if err != nil {
		return netip.Addr{}, fmt.Errorf("dial egress probe through Tailcat: %w", err)
	}
	defer conn.Close()

	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	tlsConn := tls.Client(conn, &tls.Config{
		MinVersion: tls.VersionTLS12,
		ServerName: "1.1.1.1",
	})
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		return netip.Addr{}, fmt.Errorf("authenticate egress probe: %w", err)
	}
	if _, err := io.WriteString(tlsConn,
		"GET /cdn-cgi/trace HTTP/1.1\r\nHost: 1.1.1.1\r\nUser-Agent: Tailcat-Android/1.0\r\nConnection: close\r\n\r\n",
	); err != nil {
		return netip.Addr{}, fmt.Errorf("write egress probe: %w", err)
	}

	response, err := http.ReadResponse(bufio.NewReader(tlsConn), &http.Request{Method: http.MethodGet})
	if err != nil {
		return netip.Addr{}, fmt.Errorf("read egress probe response: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return netip.Addr{}, fmt.Errorf("egress probe returned HTTP %d", response.StatusCode)
	}

	body, err := io.ReadAll(io.LimitReader(response.Body, maxEgressProbeBody))
	if err != nil {
		return netip.Addr{}, fmt.Errorf("read egress probe body: %w", err)
	}
	return parseEgressTrace(string(body))
}

func (b *TunBridge) probeIPify(ctx context.Context) (netip.Addr, error) {
	conn, err := b.client.Dial(ctx, "tcp", "api.ipify.org:80")
	if err != nil {
		return netip.Addr{}, fmt.Errorf("dial ipify through Tailcat: %w", err)
	}
	defer conn.Close()
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	if _, err := io.WriteString(conn,
		"GET / HTTP/1.1\r\nHost: api.ipify.org\r\nUser-Agent: Tailcat-Android/1.0\r\nConnection: close\r\n\r\n",
	); err != nil {
		return netip.Addr{}, fmt.Errorf("write ipify probe: %w", err)
	}
	response, err := http.ReadResponse(bufio.NewReader(conn), &http.Request{Method: http.MethodGet})
	if err != nil {
		return netip.Addr{}, fmt.Errorf("read ipify response: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return netip.Addr{}, fmt.Errorf("ipify returned HTTP %d", response.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxEgressProbeBody))
	if err != nil {
		return netip.Addr{}, fmt.Errorf("read ipify body: %w", err)
	}
	ip, err := netip.ParseAddr(strings.TrimSpace(string(body)))
	if err != nil {
		return netip.Addr{}, fmt.Errorf("ipify returned invalid IP: %w", err)
	}
	return ip.Unmap(), nil
}

func (b *TunBridge) egressProbeLoop() {
	const retryDelay = 3 * time.Second
	for attempt := 1; ; attempt++ {
		probeCtx, cancel := context.WithTimeout(b.ctx, 15*time.Second)
		ip, err := b.probeTunnelEgressIP(probeCtx)
		cancel()
		if err == nil {
			b.egressIP.Store(ip.String())
			return
		}
		log.Printf("Tailcat egress audit attempt %d failed: %v", attempt, err)
		select {
		case <-b.ctx.Done():
			return
		case <-time.After(retryDelay):
		}
	}
}

func parseEgressTrace(body string) (netip.Addr, error) {
	for line := range strings.Lines(body) {
		value, found := strings.CutPrefix(strings.TrimSpace(line), "ip=")
		if !found {
			continue
		}
		ip, err := netip.ParseAddr(value)
		if err != nil {
			return netip.Addr{}, fmt.Errorf("egress probe returned invalid IP: %w", err)
		}
		return ip.Unmap(), nil
	}
	return netip.Addr{}, fmt.Errorf("egress probe response omitted ip")
}
