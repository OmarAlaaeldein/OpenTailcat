package engine

import (
	"bytes"
	"log"
	"strings"
	"sync"
	"testing"

	"github.com/tailscale/tailcat"
)

type lockedBuffer struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (b *lockedBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *lockedBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

func captureLog(t *testing.T) *lockedBuffer {
	t.Helper()
	buf := &lockedBuffer{}
	prev := log.Writer()
	log.SetOutput(buf)
	t.Cleanup(func() { log.SetOutput(prev) })
	return buf
}

func setVerbose(t *testing.T, on bool) {
	t.Helper()
	prev := verboseLogs.Load()
	verboseLogs.Store(on)
	t.Cleanup(func() { verboseLogs.Store(prev) })
}

func TestTailcatLogsHideAddressesUnlessVerbose(t *testing.T) {
	buf := captureLog(t)
	setVerbose(t, false)

	tailcatLogf("magicsock: endpoints changed: %s (stun)", "198.51.100.77:41641")
	tailcatLogf("netcheck: report: udp=true v4a=%s", "198.51.100.77:3478")
	tailcatLogf("LinkChange: major, rebinding: %v", "old: {wlan0: 198.51.100.77/24}")
	quiet := buf.String()
	if strings.Contains(quiet, "198.51.100.77") {
		t.Fatalf("an address reached the log with verbose logs off:\n%s", quiet)
	}
	if !strings.Contains(quiet, "LinkChange: major, rebinding\n") {
		t.Fatalf("the link-change summary is missing:\n%s", quiet)
	}

	verboseLogs.Store(true)
	tailcatLogf("magicsock: endpoints changed: %s (stun)", "198.51.100.77:41641")
	if !strings.Contains(buf.String(), "endpoints changed: 198.51.100.77:41641") {
		t.Fatalf("verbose logs did not print the upstream line:\n%s", buf.String())
	}
}

func TestTailcatClientUsesTheQuietLogger(t *testing.T) {
	buf := captureLog(t)
	setVerbose(t, false)
	ec, ok := newTailcatClient(tailcat.ConnBlob("tc")).(*engineClient)
	if !ok || ec.Client.Logf == nil {
		t.Fatal("newTailcatClient must set the client's Logf")
	}
	ec.Client.Logf("magicsock: home is %s", "198.51.100.77")
	if strings.Contains(buf.String(), "198.51.100.77") {
		t.Fatal("the client logger printed an address with verbose logs off")
	}
}

func TestUpdateNetworkStateSetsVerboseLogs(t *testing.T) {
	setVerbose(t, false)
	t.Cleanup(func() { _ = UpdateNetworkState("") })

	if err := UpdateNetworkState(`{"isOnline":true,"interfaces":[],"verboseLogs":true}`); err != nil {
		t.Fatal(err)
	}
	if !verboseLogs.Load() {
		t.Fatal("verboseLogs:true was not applied")
	}
	// Network callbacks send the state without the field; it must not reset.
	if err := UpdateNetworkState(`{"isOnline":true,"interfaces":[]}`); err != nil {
		t.Fatal(err)
	}
	if !verboseLogs.Load() {
		t.Fatal("a payload without verboseLogs turned verbose logs off")
	}
	if err := UpdateNetworkState(`{"isOnline":true,"interfaces":[],"verboseLogs":false}`); err != nil {
		t.Fatal(err)
	}
	if verboseLogs.Load() {
		t.Fatal("verboseLogs:false was not applied")
	}
}
