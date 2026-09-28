// probe runs live data-plane checks from an Android shell (uid 2000 is routed
// through the VPN while it is connected). Build and run it with
// scripts/emulator/probe.sh.
//
//	probe burst N            N DNS queries to 1.1.1.1:53, each from a fresh port
//	probe stun SIZE...       STUN binding requests of SIZE bytes, DF set
//	probe halfclose HOST     HTTP/1.0 GET, shut down the write side, read reply
//	probe http HOST          the same without the half-close
package main

import (
	"context"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"syscall"
	"time"
)

func dnsQuery(id uint16) []byte {
	q := []byte{0, 0, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0}
	binary.BigEndian.PutUint16(q[0:2], id)
	q = append(q, 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1)
	return q
}

func burst(n int) {
	answered, refused, silent := 0, 0, 0
	start := time.Now()
	for i := 0; i < n; i++ {
		c, err := net.Dial("udp4", "1.1.1.1:53")
		if err != nil {
			fmt.Println("dial:", err)
			return
		}
		_ = c.SetDeadline(time.Now().Add(3 * time.Second))
		_, _ = c.Write(dnsQuery(uint16(i)))
		buf := make([]byte, 1500)
		_, err = c.Read(buf)
		switch {
		case err == nil:
			answered++
		case errors.Is(err, syscall.ECONNREFUSED):
			refused++
		default:
			silent++
		}
		c.Close()
	}
	fmt.Printf("burst n=%d answered=%d refused(ICMP unreachable)=%d silent=%d in %s\n",
		n, answered, refused, silent, time.Since(start).Round(time.Millisecond))
}

func stunRequest(size int) []byte {
	// Binding request with an unknown comprehension-optional attribute
	// (0x8000 range) as padding, so the server still answers.
	attrLen := size - 20 - 4
	if attrLen < 0 {
		attrLen = 0
	}
	attrLen &^= 3
	msg := make([]byte, 20+4+attrLen)
	binary.BigEndian.PutUint16(msg[0:2], 0x0001)
	binary.BigEndian.PutUint16(msg[2:4], uint16(4+attrLen))
	binary.BigEndian.PutUint32(msg[4:8], 0x2112A442)
	_, _ = rand.Read(msg[8:20])
	binary.BigEndian.PutUint16(msg[20:22], 0x8028+0x10)
	binary.BigEndian.PutUint16(msg[22:24], uint16(attrLen))
	return msg
}

func stun(sizes []int) {
	addrs, err := net.LookupIP("stun.cloudflare.com")
	var dst *net.UDPAddr
	for _, a := range addrs {
		if a.To4() != nil {
			dst = &net.UDPAddr{IP: a, Port: 3478}
			break
		}
	}
	if err != nil || dst == nil {
		dst = &net.UDPAddr{IP: net.ParseIP("162.159.207.0"), Port: 3478}
	}
	for _, size := range sizes {
		c, err := net.DialUDP("udp4", nil, dst)
		if err != nil {
			fmt.Println("dial:", err)
			return
		}
		raw, _ := c.SyscallConn()
		_ = raw.Control(func(fd uintptr) {
			// IP_MTU_DISCOVER = 10, IP_PMTUDISC_DO = 2: always set DF.
			_ = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IP, 10, 2)
		})
		msg := stunRequest(size)
		result := ""
		for attempt := 0; attempt < 3 && result == ""; attempt++ {
			_ = c.SetDeadline(time.Now().Add(2 * time.Second))
			if _, err := c.Write(msg); err != nil {
				result = "send error: " + err.Error()
				break
			}
			buf := make([]byte, 2048)
			n, err := c.Read(buf)
			if err == nil {
				result = fmt.Sprintf("reply %d B", n)
			} else if !isTimeout(err) {
				result = "read error: " + err.Error()
			}
		}
		if result == "" {
			result = "no reply (3 tries)"
		}
		fmt.Printf("stun payload=%d B: %s\n", len(msg), result)
		c.Close()
	}
}

func isTimeout(err error) bool {
	var ne net.Error
	return errors.As(err, &ne) && ne.Timeout()
}

func httpGet(host string, half bool) {
	start := time.Now()
	c, err := net.DialTimeout("tcp4", net.JoinHostPort(host, "80"), 10*time.Second)
	if err != nil {
		fmt.Println("dial:", err)
		return
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(15 * time.Second))
	fmt.Fprintf(c, "GET / HTTP/1.0\r\nHost: %s\r\nConnection: close\r\n\r\n", host)
	if half {
		_ = c.(*net.TCPConn).CloseWrite()
	}
	body, err := io.ReadAll(c)
	status := ""
	if len(body) >= 12 {
		status = string(body[:12])
	}
	fmt.Printf("http %s half=%v: read %d B, status %q, err=%v, %s\n", host, half, len(body), status, err,
		time.Since(start).Round(time.Millisecond))
}

func main() {
	// Android has no /etc/resolv.conf; resolve through 1.1.1.1 (tunneled).
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, network, "1.1.1.1:53")
	}}
	usage := func() {
		fmt.Println("usage: probe burst N | stun SIZE... | halfclose HOST | http HOST")
		os.Exit(2)
	}
	if len(os.Args) < 3 {
		usage()
	}
	switch os.Args[1] {
	case "burst":
		n, _ := strconv.Atoi(os.Args[2])
		burst(n)
	case "stun":
		var sizes []int
		for _, a := range os.Args[2:] {
			s, _ := strconv.Atoi(a)
			sizes = append(sizes, s)
		}
		stun(sizes)
	case "halfclose":
		httpGet(os.Args[2], true)
	case "http":
		httpGet(os.Args[2], false)
	default:
		usage()
	}
}
