//go:build android

package engine

import (
	"context"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/net/netmon"
	"tailscale.com/net/netns"
	"tailscale.com/tailcfg"
	"tailscale.com/types/key"
	"tailscale.com/types/logger"
)

func TestAndroidTransportCreationAndFailure(t *testing.T) {
	var mu sync.Mutex
	var protected []int
	SetSocketProtector(protectorFunc(func(fd int) bool {
		mu.Lock()
		protected = append(protected, fd)
		mu.Unlock()
		return true
	}))
	t.Cleanup(func() { SetSocketProtector(nil) })
	ordinary, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ordinary.Close()
	mu.Lock()
	count := len(protected)
	mu.Unlock()
	if count != 0 {
		t.Fatal("ordinary socket was incorrectly protected")
	}
	ci := tailcat.ConnInfo{
		ServerPublic:      tailcat.NodePublic{NodePublic: key.NewNode().Public()},
		ServerDiscoPublic: tailcat.DiscoPublic{DiscoPublic: key.NewDisco().Public()},
		Region: []*tailcfg.DERPRegion{{RegionID: 900, RegionCode: "test", Nodes: []*tailcfg.DERPNode{{
			Name: "900a", RegionID: 900, HostName: "127.0.0.1", IPv4: "127.0.0.1", DERPPort: 1, STUNPort: -1,
		}}}},
	}
	client := tailcat.NewClient(ci.Addr())
	client.Logf = logger.Discard
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	_, _ = client.Ping(ctx) // Synthetic keys, loopback only; no user's gateway.
	if _, err := client.NetworkMonitor(); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	count = len(protected)
	mu.Unlock()
	if count == 0 {
		t.Fatal("client initialization opened no protected transport sockets")
	}
	mon := netmon.NewStatic()
	if netns.Listener(logger.Discard, mon).Control == nil {
		t.Fatal("client initialization disabled protection for future sockets")
	}
	SetSocketProtector(protectorFunc(func(int) bool { return false }))
	conn, err := netns.Listener(logger.Discard, mon).ListenPacket(context.Background(), "udp4", "127.0.0.1:0")
	if conn != nil {
		_ = conn.Close()
	}
	if err == nil {
		t.Fatal("protection failure allowed a transport UDP socket")
	}
}
