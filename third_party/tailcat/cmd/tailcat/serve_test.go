// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

package main

import (
	"bufio"
	"bytes"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"time"
)

// startEchoListener starts a TCP echo server on a 127.0.0.1 ephemeral
// port, closed with the test, and returns its port.
func startEchoListener(t *testing.T) uint16 {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				io.Copy(c, c)
				c.Close()
			}()
		}
	}()
	return uint16(ln.Addr().(*net.TCPAddr).Port)
}

// runClient runs an unstarted tailcat client command with payload on
// its stdin and a 30 second watchdog, returning its stdout and error.
func runClient(t *testing.T, client *exec.Cmd, payload string) (string, error) {
	t.Helper()
	client.Stdin = strings.NewReader(payload)
	var stdout, stderr bytes.Buffer
	client.Stdout = &stdout
	client.Stderr = &stderr
	if err := client.Start(); err != nil {
		t.Fatal(err)
	}
	done := make(chan error, 1)
	go func() { done <- client.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			err = fmt.Errorf("%w\nclient stderr:\n%s", err, stderr.String())
		}
		return stdout.String(), err
	case <-time.After(30 * time.Second):
		client.Process.Kill()
		t.Fatalf("client did not exit within 30s\nclient stderr:\n%s", stderr.String())
		panic("unreachable")
	}
}

// TestServePorts verifies that a server with an explicit port list
// (given to the serve subcommand) proxies connections on a served
// port to the same local port, and refuses connections to ports
// outside the list.
func TestServePorts(t *testing.T) {
	e := newTestEnv(t)
	port := startEchoListener(t)

	// Grab a second port that's free but not served.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	unservedPort := ln.Addr().(*net.TCPAddr).Port
	ln.Close()

	_, blob, _ := e.startServer("serve", strconv.Itoa(int(port)))

	const payload = "echo through a served port"
	got, err := runClient(t, e.cmd("--key=new", "--derpmap-url="+e.derpMapURL, blob, strconv.Itoa(int(port))), payload)
	if err != nil {
		t.Fatalf("client to served port: %v", err)
	}
	if got != payload {
		t.Errorf("served port echoed %q; want %q", got, payload)
	}

	got, err = runClient(t, e.cmd("--key=new", "--derpmap-url="+e.derpMapURL, blob, strconv.Itoa(unservedPort)), payload)
	if err == nil {
		t.Errorf("client to unserved port %v succeeded with output %q; want connection failure", unservedPort, got)
	}
}

// TestServeExitNode verifies that a --serve=exit-node server forwards
// connections to arbitrary IP:port destinations, both for a plain
// client given an IP:port argument and through the SOCKS5 proxy that
// "tailcat socks" runs.
func TestServeExitNode(t *testing.T) {
	e := newTestEnv(t)
	port := startEchoListener(t)
	dst := netip.AddrPortFrom(netip.MustParseAddr("127.0.0.1"), port)

	_, blob, _ := e.startServer("--serve=exit-node")

	t.Run("client_ipport", func(t *testing.T) {
		const payload = "echo through the exit node"
		got, err := runClient(t, e.cmd("--key=new", "--derpmap-url="+e.derpMapURL, blob, dst.String()), payload)
		if err != nil {
			t.Fatalf("client to %v: %v", dst, err)
		}
		if got != payload {
			t.Errorf("exit node echoed %q; want %q", got, payload)
		}
	})

	t.Run("socks5", func(t *testing.T) {
		socks := e.cmd("--key=new", "--derpmap-url="+e.derpMapURL, "socks", blob)
		socksErr, err := socks.StderrPipe()
		if err != nil {
			t.Fatal(err)
		}
		if err := socks.Start(); err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { socks.Process.Kill() })

		// The proxy logs its listen address to stderr once it's ready.
		addrRx := regexp.MustCompile(`socks5h://(\S+)`)
		proxyAddr := ""
		scanner := bufio.NewScanner(socksErr)
		for scanner.Scan() {
			line := scanner.Text()
			t.Logf("socks: %s", line)
			if m := addrRx.FindStringSubmatch(line); m != nil {
				proxyAddr = m[1]
				break
			}
		}
		if proxyAddr == "" {
			t.Fatal("never saw the SOCKS proxy address on stderr")
		}

		c := socks5Connect(t, proxyAddr, dst)
		defer c.Close()
		const payload = "echo through SOCKS and the exit node"
		if _, err := c.Write([]byte(payload)); err != nil {
			t.Fatal(err)
		}
		c.SetReadDeadline(time.Now().Add(30 * time.Second))
		buf := make([]byte, len(payload))
		if _, err := io.ReadFull(c, buf); err != nil {
			t.Fatalf("reading echo reply: %v", err)
		}
		if got := string(buf); got != payload {
			t.Errorf("SOCKS echoed %q; want %q", got, payload)
		}
	})
}

// socks5Connect dials the SOCKS5 proxy at proxyAddr and issues a
// CONNECT to the IPv4 destination dst, returning the proxied
// connection. It hand-rolls the tiny client side of RFC 1928 rather
// than adding a dependency on golang.org/x/net/proxy.
func socks5Connect(t *testing.T, proxyAddr string, dst netip.AddrPort) net.Conn {
	t.Helper()
	c, err := net.Dial("tcp", proxyAddr)
	if err != nil {
		t.Fatal(err)
	}
	c.SetDeadline(time.Now().Add(30 * time.Second))

	if _, err := c.Write([]byte{5, 1, 0}); err != nil { // version 5, one method: no auth
		t.Fatal(err)
	}
	buf := make([]byte, 2)
	if _, err := io.ReadFull(c, buf); err != nil {
		t.Fatal(err)
	}
	if buf[0] != 5 || buf[1] != 0 {
		t.Fatalf("SOCKS5 method reply = %v; want [5 0]", buf)
	}

	ip4 := dst.Addr().As4()
	req := append([]byte{5, 1, 0, 1}, ip4[:]...) // CONNECT, IPv4
	req = append(req, byte(dst.Port()>>8), byte(dst.Port()))
	if _, err := c.Write(req); err != nil {
		t.Fatal(err)
	}
	reply := make([]byte, 4) // version, code, reserved, bind address type
	if _, err := io.ReadFull(c, reply); err != nil {
		t.Fatal(err)
	}
	if reply[0] != 5 || reply[1] != 0 {
		t.Fatalf("SOCKS5 CONNECT reply code = %d; want 0", reply[1])
	}
	var bindLen int
	switch reply[3] {
	case 1: // IPv4
		bindLen = 4
	case 4: // IPv6
		bindLen = 16
	default:
		t.Fatalf("SOCKS5 CONNECT reply address type = %d; want 1 or 4", reply[3])
	}
	if _, err := io.ReadFull(c, make([]byte, bindLen+2)); err != nil { // bind address and port
		t.Fatal(err)
	}
	c.SetDeadline(time.Time{})
	return c
}
