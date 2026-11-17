package engine

import (
	"context"
	"net"
	"testing"
	"time"
)

func withDNSServers(t *testing.T, servers []string) {
	t.Helper()
	netStateMu.Lock()
	prev := customDNSList
	customDNSList = servers
	netStateMu.Unlock()
	dnsPreferred.Store(0)
	t.Cleanup(func() {
		netStateMu.Lock()
		customDNSList = prev
		netStateMu.Unlock()
		dnsPreferred.Store(0)
	})
}

func udpServer(t *testing.T, answer bool) (addr string) {
	t.Helper()
	c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	go func() {
		buf := make([]byte, 512)
		for {
			n, from, err := c.ReadFromUDP(buf)
			if err != nil {
				return
			}
			if answer {
				c.WriteToUDP(buf[:n], from)
			}
		}
	}()
	return c.LocalAddr().String()
}

func dialResolver(t *testing.T) net.Conn {
	t.Helper()
	c, err := net.DefaultResolver.Dial(context.Background(), "udp", "ignored:53")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

func exchange(c net.Conn, wait time.Duration) error {
	c.SetDeadline(time.Now().Add(wait))
	if _, err := c.Write([]byte("q")); err != nil {
		return err
	}
	_, err := c.Read(make([]byte, 16))
	return err
}

// Seen on the emulator: the primary DNS server (10.0.2.3) never answers while
// the secondary does. A UDP dial always succeeds, so the resolver must learn
// from the exchange: a timeout moves on, an answer sticks.
func TestResolverFailsOverFromADeadServerAndSticksToALiveOne(t *testing.T) {
	dead, live := udpServer(t, false), udpServer(t, true)
	withDNSServers(t, []string{dead, live})

	first := dialResolver(t)
	if got := first.RemoteAddr().String(); got != dead {
		t.Fatalf("first dial went to %s, want the first server %s", got, dead)
	}
	if err := exchange(first, 100*time.Millisecond); err == nil {
		t.Fatal("a silent server answered")
	}

	retry := dialResolver(t)
	if got := retry.RemoteAddr().String(); got != live {
		t.Fatalf("retry went to %s after a timeout, want the next server %s", got, live)
	}
	if err := exchange(retry, time.Second); err != nil {
		t.Fatalf("live server exchange: %v", err)
	}

	for range 3 {
		if got := dialResolver(t).RemoteAddr().String(); got != live {
			t.Fatalf("after an answer the resolver went to %s, want to stick to %s", got, live)
		}
	}
}

// A and AAAA queries run in parallel and stall together on a dead server; the
// preference must advance once, not twice (which would land back on it).
func TestParallelTimeoutsAdvanceThePreferenceOnce(t *testing.T) {
	dead, live := udpServer(t, false), udpServer(t, true)
	withDNSServers(t, []string{dead, live})

	a, aaaa := dialResolver(t), dialResolver(t)
	exchange(a, 50*time.Millisecond)
	exchange(aaaa, 50*time.Millisecond)

	if got := dialResolver(t).RemoteAddr().String(); got != live {
		t.Fatalf("after two parallel timeouts the next dial went to %s, want %s", got, live)
	}
}

func TestConnStaysADatagramConn(t *testing.T) {
	withDNSServers(t, []string{udpServer(t, true)})
	if _, ok := dialResolver(t).(net.PacketConn); !ok {
		t.Fatal("resolver conn is not a net.PacketConn; Go would treat DNS as a stream")
	}
}

func TestResolverWithNoServersFailsFast(t *testing.T) {
	withDNSServers(t, nil)
	if _, err := net.DefaultResolver.Dial(context.Background(), "udp", "ignored:53"); err == nil {
		t.Fatal("dial succeeded with no DNS servers")
	}
}

func TestPreferenceSurvivesAShrinkingServerList(t *testing.T) {
	a, b, c := udpServer(t, true), udpServer(t, true), udpServer(t, true)
	withDNSServers(t, []string{a, b, c})
	dnsPreferred.Store(2)
	netStateMu.Lock()
	customDNSList = []string{a}
	netStateMu.Unlock()
	if got := dialResolver(t).RemoteAddr().String(); got != a {
		t.Fatalf("with one server left, dial went to %s, want %s", got, a)
	}
}
