package engine

import (
	"bufio"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"time"
)

func runningClient() (TunnelClient, error) {
	globalCore.mu.Lock()
	defer globalCore.mu.Unlock()
	if globalCore.state != StateRunning || globalCore.sess == nil || globalCore.sess.client == nil {
		return nil, errors.New("VPN tunnel is not running")
	}
	return globalCore.sess.client, nil
}

func MeasureTunnelPingMS() (int64, error) {
	client, err := runningClient()
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	start := time.Now()
	if err := tunnelHTTPGet(ctx, client, "1.1.1.1:443", "1.1.1.1", "/cdn-cgi/trace", 16<<10); err != nil {
		return 0, err
	}
	ms := time.Since(start).Milliseconds()
	if ms < 1 {
		ms = 1
	}
	return ms, nil
}

func MeasureTunnelDownloadMbps() (float64, error) {
	client, err := runningClient()
	if err != nil {
		return 0, err
	}
	host, err := lookupSpeedHost(client)
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	n, elapsed, err := tunnelHTTPRead(ctx, client, net.JoinHostPort(host, "443"), "speed.cloudflare.com", "/__down?bytes=25000000", 5*time.Second)
	if err != nil {
		return 0, err
	}
	if elapsed <= 0 || n <= 0 {
		return 0, errors.New("download test returned no data")
	}
	return float64(n) * 8 / elapsed.Seconds() / 1_000_000, nil
}

func MeasureTunnelUploadMbps() (float64, error) {
	client, err := runningClient()
	if err != nil {
		return 0, err
	}
	host, err := lookupSpeedHost(client)
	if err != nil {
		return 0, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	n, elapsed, err := tunnelHTTPPost(ctx, client, net.JoinHostPort(host, "443"), "speed.cloudflare.com", "/__up", 4*time.Second)
	if err != nil {
		return 0, err
	}
	if elapsed <= 0 || n <= 0 {
		return 0, errors.New("upload test returned no data")
	}
	return float64(n) * 8 / elapsed.Seconds() / 1_000_000, nil
}

func lookupSpeedHost(client TunnelClient) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	ip, err := tunnelLookupA(ctx, client, "speed.cloudflare.com")
	if err != nil {
		return "", fmt.Errorf("resolve speed.cloudflare.com through tunnel: %w", err)
	}
	if !ip.Is4() {
		return "", errors.New("no IPv4 for speed.cloudflare.com")
	}
	return ip.String(), nil
}

func tunnelDialTLS(ctx context.Context, client TunnelClient, addr, sni string) (*tls.Conn, error) {
	ap, err := netip.ParseAddrPort(addr)
	if err != nil {
		return nil, err
	}
	conn, err := client.DialTCP(ctx, ap)
	if err != nil {
		return nil, fmt.Errorf("DialTCP %s: %w", addr, err)
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	tlsConn := tls.Client(conn, &tls.Config{MinVersion: tls.VersionTLS12, ServerName: sni})
	if err := tlsConn.HandshakeContext(ctx); err != nil {
		_ = conn.Close()
		return nil, fmt.Errorf("tls handshake: %w", err)
	}
	return tlsConn, nil
}

func tunnelHTTPGet(ctx context.Context, client TunnelClient, addr, sni, path string, limit int64) error {
	tlsConn, err := tunnelDialTLS(ctx, client, addr, sni)
	if err != nil {
		return err
	}
	defer tlsConn.Close()
	req := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: %s\r\nConnection: close\r\n\r\n", path, sni, androidUserAgent)
	if _, err := io.WriteString(tlsConn, req); err != nil {
		return err
	}
	resp, err := http.ReadResponse(bufio.NewReader(tlsConn), &http.Request{Method: http.MethodGet})
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	_, err = io.Copy(io.Discard, io.LimitReader(resp.Body, limit))
	return err
}

func tunnelHTTPRead(ctx context.Context, client TunnelClient, addr, sni, path string, duration time.Duration) (int64, time.Duration, error) {
	tlsConn, err := tunnelDialTLS(ctx, client, addr, sni)
	if err != nil {
		return 0, 0, err
	}
	defer tlsConn.Close()
	req := fmt.Sprintf("GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: %s\r\nConnection: close\r\n\r\n", path, sni, androidUserAgent)
	if _, err := io.WriteString(tlsConn, req); err != nil {
		return 0, 0, err
	}
	resp, err := http.ReadResponse(bufio.NewReader(tlsConn), &http.Request{Method: http.MethodGet})
	if err != nil {
		return 0, 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 0, 0, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	deadline := time.Now().Add(duration)
	buf := make([]byte, 32*1024)
	var n int64
	start := time.Now()
	for time.Now().Before(deadline) {
		_ = tlsConn.SetReadDeadline(time.Now().Add(2 * time.Second))
		got, readErr := resp.Body.Read(buf)
		if got > 0 {
			n += int64(got)
		}
		if readErr != nil {
			break
		}
	}
	return n, time.Since(start), nil
}

func tunnelHTTPPost(ctx context.Context, client TunnelClient, addr, sni, path string, duration time.Duration) (int64, time.Duration, error) {
	tlsConn, err := tunnelDialTLS(ctx, client, addr, sni)
	if err != nil {
		return 0, 0, err
	}
	defer tlsConn.Close()
	if _, err := io.WriteString(tlsConn, fmt.Sprintf(
		"POST %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: %s\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n",
		path, sni, androidUserAgent,
	)); err != nil {
		return 0, 0, err
	}
	chunk := make([]byte, 16*1024)
	deadline := time.Now().Add(duration)
	var n int64
	start := time.Now()
	for time.Now().Before(deadline) {
		hdr := fmt.Sprintf("%x\r\n", len(chunk))
		if _, err := io.WriteString(tlsConn, hdr); err != nil {
			break
		}
		if _, err := tlsConn.Write(chunk); err != nil {
			break
		}
		if _, err := io.WriteString(tlsConn, "\r\n"); err != nil {
			break
		}
		n += int64(len(chunk))
	}
	_, _ = io.WriteString(tlsConn, "0\r\n\r\n")
	resp, err := http.ReadResponse(bufio.NewReader(tlsConn), &http.Request{Method: http.MethodPost})
	if err != nil {
		return 0, 0, err
	}
	_ = resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return n, time.Since(start), fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return n, time.Since(start), nil
}
