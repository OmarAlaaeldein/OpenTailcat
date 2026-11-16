package engine

import (
	"context"
	"io"
	"net"
	"net/netip"
	"os"
	"syscall"
	"testing"
	"time"
)

// newCounterHarness wires a TunBridge to a socketpair standing in for the TUN
// and a gateway that echoes every datagram. The returned file is the "kernel"
// side: whatever it writes is read from the TUN, whatever it reads was written
// to it, so its own byte totals are the ground truth for the counters.
func newCounterHarness(t *testing.T) (app *os.File, b *TunBridge, stop func()) {
	t.Helper()
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatalf("socketpair: %v", err)
	}
	for _, fd := range fds {
		if err := syscall.SetNonblock(fd, true); err != nil {
			t.Fatalf("set non-blocking: %v", err)
		}
	}
	app = os.NewFile(uintptr(fds[0]), "app")
	tun := os.NewFile(uintptr(fds[1]), "tun")

	echoDial := func(context.Context, netip.AddrPort) (net.Conn, error) {
		c, s := net.Pipe()
		go func() {
			_, _ = io.Copy(s, s)
			_ = s.Close()
		}()
		return c, nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	b = &TunBridge{
		sessionID: 1,
		tunFile:   tun,
		client:    &mockTunnelClient{dialUDPFn: echoDial},
		mtu:       1280,
		ctx:       ctx,
		cancel:    cancel,
	}
	proxy, err := newNetstackProxy(b)
	if err != nil {
		t.Fatalf("newNetstackProxy: %v", err)
	}
	b.netstack = proxy
	go func() { _ = b.readLoop(nil) }()
	go func() { _ = proxy.writeLoop(nil) }()
	stop = func() {
		b.closed.Store(true)
		cancel()
		_ = tun.Close()
		proxy.Close()
		_ = app.Close()
	}
	return app, b, stop
}

// TestTunByteCountersEqualBytesCrossingTun is the ground-truth check: the
// harness counts, independently, every byte it writes to and reads from the
// TUN, and the engine's counters must equal those totals exactly. The mix
// includes datagrams the engine echoes and one it refuses with a local ICMP
// error, so all three kinds of packet are covered.
func TestTunByteCountersEqualBytesCrossingTun(t *testing.T) {
	app, b, stop := newCounterHarness(t)
	defer stop()

	src := netip.MustParseAddrPort("100.64.0.2:40000")
	dst := netip.MustParseAddrPort("198.51.100.1:5000")
	buf := make([]byte, 4096)

	var written, read int64 // what the "kernel" side saw
	for _, payload := range []int{1, 17, 333, 1000, 1232, 1300 /* refused */, 64, 1232, 9} {
		pkt := buildIPv4UDPPacket(src, dst, make([]byte, payload))
		if _, err := app.Write(pkt); err != nil {
			t.Fatalf("write %d-byte payload to TUN: %v", payload, err)
		}
		written += int64(len(pkt))

		_ = app.SetReadDeadline(time.Now().Add(3 * time.Second))
		n, err := app.Read(buf)
		if err != nil {
			t.Fatalf("read the answer to a %d-byte payload: %v", payload, err)
		}
		read += int64(n)
	}

	waitAtomic(t, &b.rxBytes, read, 2*time.Second, "rxBytes to reach the bytes read from the TUN")
	time.Sleep(100 * time.Millisecond) // let a spurious extra count show up
	if got := b.txBytes.Load(); got != written {
		t.Fatalf("txBytes = %d, bytes written into the TUN = %d", got, written)
	}
	if got := b.rxBytes.Load(); got != read {
		t.Fatalf("rxBytes = %d, bytes read out of the TUN = %d", got, read)
	}
}

func TestRateKbpsMath(t *testing.T) {
	cases := []struct {
		name    string
		bytes   int64
		elapsed time.Duration
		want    int64
	}{
		{"125 kB in 1 s is exactly 1000 kb/s", 125_000, time.Second, 1000},
		{"125 MB in 1 s is 1 Gb/s", 125_000_000, time.Second, 1_000_000},
		{"1250 B in 1 s is 10 kb/s", 1250, time.Second, 10},
		{"over 2 s the rate halves", 250_000, 2 * time.Second, 1000},
		{"over half a second it doubles", 125_000, 500 * time.Millisecond, 2000},
		{"rounds up from 0.504 kb/s", 63, time.Second, 1},
		{"rounds down from 0.496 kb/s", 62, time.Second, 0},
		{"a lone byte is below 1 kb/s", 1, time.Second, 0},
		{"no traffic", 0, time.Second, 0},
		{"counter reset is not a negative rate", -5000, time.Second, 0},
		{"zero interval", 1000, 0, 0},
		{"negative interval", 1000, -time.Second, 0},
		{"terabyte-scale delta keeps exact integer maths", 1 << 40, time.Second, 8_796_093_022},
	}
	for _, c := range cases {
		if got := rateKbps(c.bytes, c.elapsed); got != c.want {
			t.Errorf("%s: rateKbps(%d, %v) = %d, want %d", c.name, c.bytes, c.elapsed, got, c.want)
		}
	}
}

// The reported rate and the byte total must describe the same traffic: bytes
// moved in the interval, converted at 8 bits per byte and 1000 bits per kb.
func TestRateKbpsAgreesWithByteDeltas(t *testing.T) {
	const interval = time.Second
	var total int64
	for _, step := range []int64{12_500, 250_000, 0, 1_250_000} {
		total += step
		want := step * 8 / 1000 // exact for these steps over 1 s
		if got := rateKbps(step, interval); got != want {
			t.Fatalf("step %d B: rate %d kb/s, want %d", step, got, want)
		}
	}
	if total != 1_512_500 {
		t.Fatalf("test arithmetic changed: total %d", total)
	}
}
