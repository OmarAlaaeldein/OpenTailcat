package engine

import (
	"context"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
)

type countingNotifier struct{ n atomic.Int32 }

func (c *countingNotifier) InjectEvent() { c.n.Add(1) }

type monitoredTestClient struct {
	prepareTestClient
	mon linkChangeNotifier
}

func (c *monitoredTestClient) netMonitor() linkChangeNotifier { return c.mon }

func currentMonitor() linkChangeNotifier {
	netStateMu.RLock()
	defer netStateMu.RUnlock()
	return activeMonitor
}

// recordDefaultRouteInterface swaps the netmon hook for one that records
// every value until the test ends.
func recordDefaultRouteInterface(t *testing.T) func() []string {
	t.Helper()
	var mu sync.Mutex
	var got []string
	original := setDefaultRouteInterface
	setDefaultRouteInterface = func(name string) {
		mu.Lock()
		got = append(got, name)
		mu.Unlock()
	}
	t.Cleanup(func() { setDefaultRouteInterface = original })
	return func() []string {
		mu.Lock()
		defer mu.Unlock()
		return append([]string(nil), got...)
	}
}

// The monitor sits in unexported upstream fields; this fails if a Tailcat
// update moves them, instead of roaming silently going back to timeouts.
func TestClientNetMonReachesTheClientMonitor(t *testing.T) {
	ci := tailcat.ConnInfo{
		ServerPublic:      tailcat.NodePublic{NodePublic: key.NewNode().Public()},
		ServerDiscoPublic: tailcat.DiscoPublic{DiscoPublic: key.NewDisco().Public()},
		Region: []*tailcfg.DERPRegion{{
			RegionID:   900,
			RegionCode: "test",
			Nodes: []*tailcfg.DERPNode{{
				Name:     "900a",
				RegionID: 900,
				HostName: "127.0.0.1",
				IPv4:     "127.0.0.1",
				DERPPort: 1,
				STUNPort: -1,
			}},
		}},
	}
	addr := ci.Addr()

	if mon := clientNetMon(tailcat.NewClient(addr)); mon != nil {
		t.Fatal("a client that never started has no monitor yet")
	}
	if clientNetMon(nil) != nil {
		t.Fatal("nil client must yield nil")
	}

	client := tailcat.NewClient(addr)
	t.Cleanup(func() { _ = client.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	_, _ = client.Ping(ctx) // no relay answers; this only starts the client

	if clientNetMon(client) == nil {
		t.Fatal("clientNetMon did not find the started client's netmon (upstream layout changed?)")
	}
	if (&engineClient{Client: client}).netMonitor() == nil {
		t.Fatal("engineClient.netMonitor returned nil for a started client")
	}
}

func TestUpdateNetworkStateInjectsIntoClientMonitor(t *testing.T) {
	_ = Stop()
	recorded := recordDefaultRouteInterface(t)
	notifier := &countingNotifier{}
	netStateMu.Lock()
	activeMonitor = notifier
	netStateMu.Unlock()
	t.Cleanup(func() {
		netStateMu.Lock()
		activeMonitor = nil
		netStateMu.Unlock()
		_ = UpdateNetworkState("")
	})

	wifi := `{"isOnline":true,"networkType":"WIFI","defaultInterface":"wlan0",` +
		`"interfaces":[{"name":"wlan0","addresses":["192.168.1.20/24"]}]}`
	if err := UpdateNetworkState(wifi); err != nil {
		t.Fatalf("UpdateNetworkState: %v", err)
	}
	offline := `{"isOnline":false,"networkType":"NONE","interfaces":[]}`
	if err := UpdateNetworkState(offline); err != nil {
		t.Fatalf("UpdateNetworkState offline: %v", err)
	}

	if got := notifier.n.Load(); got != 2 {
		t.Fatalf("InjectEvent calls = %d, want 2 (one per network update)", got)
	}
	got := recorded()
	if len(got) != 2 || got[0] != "wlan0" || got[1] != "" {
		t.Fatalf("default route interfaces = %q, want [wlan0 \"\"]", got)
	}
}

func TestPrepareWiresClientMonitorAndStopClearsIt(t *testing.T) {
	_ = Stop()
	first := &countingNotifier{}
	installClient(t, &monitoredTestClient{mon: first})
	if err := Prepare(officialTestToken(t)); err != nil {
		t.Fatalf("Prepare: %v", err)
	}
	if currentMonitor() != first {
		t.Fatal("Prepare did not publish the client's monitor")
	}

	// A new prepare replaces the monitor with the new client's.
	second := &countingNotifier{}
	installClient(t, &monitoredTestClient{mon: second})
	if err := Prepare(officialTestToken(t)); err != nil {
		t.Fatalf("second Prepare: %v", err)
	}
	if currentMonitor() != second {
		t.Fatal("second Prepare kept the previous client's monitor")
	}

	if err := Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	if currentMonitor() != nil {
		t.Fatal("Stop left a monitor behind")
	}

	// A client with no monitor (test fakes, future upstream) leaves it nil.
	installClient(t, &prepareTestClient{})
	if err := Prepare(officialTestToken(t)); err != nil {
		t.Fatalf("Prepare without monitor: %v", err)
	}
	if currentMonitor() != nil {
		t.Fatal("a client without a monitor must leave activeMonitor nil")
	}
}
