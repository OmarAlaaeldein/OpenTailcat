package engine

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"os"
	"testing"
	"time"

	"github.com/fxamacker/cbor/v2"
	"github.com/tailscale/tailcat"
	"go4.org/mem"
	"tailscale.com/types/key"
)

func officialTestToken(t *testing.T) string {
	t.Helper()
	var nodeRaw, discoRaw [32]byte
	for i := 0; i < 32; i++ {
		nodeRaw[i] = byte(i + 1)
		discoRaw[i] = byte(i + 33)
	}
	nodePub := key.NodePrivateFromRaw32(mem.B(nodeRaw[:])).Public()
	discoPub := key.DiscoPrivateFromRaw32(mem.B(discoRaw[:])).Public()
	cborBytes, err := cbor.Marshal(map[string]any{
		"p": nodePub.AppendTo(nil),
		"k": discoPub.AppendTo(nil),
		"i": uint64(1),
	})
	if err != nil {
		t.Fatalf("cbor marshal: %v", err)
	}
	return "tc" + base64.RawURLEncoding.EncodeToString(cborBytes)
}

func installClient(t *testing.T, client preparedClient) {
	t.Helper()
	original := newTailcatClient
	newTailcatClient = func(tailcat.ConnBlob) preparedClient { return client }
	t.Cleanup(func() {
		newTailcatClient = original
		_ = Stop()
	})
}

func statsState(t *testing.T) (EngineStats, EngineState) {
	t.Helper()
	var stats EngineStats
	if err := json.Unmarshal([]byte(GetStatsJSON()), &stats); err != nil {
		t.Fatalf("unmarshal stats: %v", err)
	}
	globalCore.mu.Lock()
	st := globalCore.state
	globalCore.mu.Unlock()
	return stats, st
}

func TestLifecycleHappyPathPrepareAttachStop(t *testing.T) {
	_ = Stop()
	fake := &prepareTestClient{caps: tailcat.CapExitTCP | tailcat.CapExitUDP}
	installClient(t, fake)

	if err := Prepare(officialTestToken(t)); err != nil {
		t.Fatalf("Prepare: %v", err)
	}
	stats, st := statsState(t)
	if st != StatePrepared || stats.State != "PREPARED" {
		t.Fatalf("expected PREPARED, got state=%s json=%s", st, stats.State)
	}
	if stats.Transport != "DISCONNECTED" {
		t.Fatalf("expected DISCONNECTED transport while prepared, got %s", stats.Transport)
	}

	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	defer r.Close()
	defer w.Close()

	if err := AttachTun(int(r.Fd())); err != nil {
		t.Fatalf("AttachTun: %v", err)
	}
	stats, st = statsState(t)
	if st != StateRunning || stats.State != "RUNNING" {
		t.Fatalf("expected RUNNING, got state=%s json=%s", st, stats.State)
	}
	if stats.HealthUnixSec <= 0 {
		t.Fatal("expected healthUnixSec > 0 while running")
	}

	r2, w2, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe2: %v", err)
	}
	defer r2.Close()
	defer w2.Close()
	if err := AttachTun(int(r2.Fd())); err != nil {
		t.Fatalf("reattach TUN: %v", err)
	}
	stats, st = statsState(t)
	if st != StateRunning || stats.State != "RUNNING" {
		t.Fatalf("expected RUNNING after reattach, got state=%s json=%s", st, stats.State)
	}

	if err := Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	stats, st = statsState(t)
	if st != StateStopped || stats.State != "STOPPED" {
		t.Fatalf("expected STOPPED, got state=%s json=%s", st, stats.State)
	}
	if !fake.closed {
		t.Fatal("expected client Close on Stop")
	}
	if globalCore.sess != nil {
		t.Fatal("expected session wiped")
	}
}

func TestSecondPrepareClosesUnattachedClient(t *testing.T) {
	_ = Stop()
	first := &prepareTestClient{caps: tailcat.CapExitTCP | tailcat.CapExitUDP}
	installClient(t, first)
	if err := Prepare(officialTestToken(t)); err != nil {
		t.Fatalf("first Prepare: %v", err)
	}

	second := &prepareTestClient{caps: tailcat.CapExitTCP | tailcat.CapExitUDP}
	newTailcatClient = func(tailcat.ConnBlob) preparedClient { return second }
	if err := Prepare(officialTestToken(t)); err != nil {
		t.Fatalf("second Prepare: %v", err)
	}
	if !first.closed {
		t.Fatal("expected first prepared client to be closed")
	}
	if second.closed {
		t.Fatal("second client should still be open")
	}
}

func TestPrepareCancelViaStop(t *testing.T) {
	_ = Stop()
	entered := make(chan struct{})
	fake := &blockingPingClient{
		prepareTestClient: prepareTestClient{caps: tailcat.CapExitTCP | tailcat.CapExitUDP},
		entered:           entered,
	}
	installClient(t, fake)

	errCh := make(chan error, 1)
	go func() { errCh <- Prepare(officialTestToken(t)) }()

	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("Ping did not start")
	}

	if err := Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	select {
	case err := <-errCh:
		if err == nil {
			t.Fatal("expected Prepare to fail after Stop")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("Prepare did not return after Stop")
	}
	if !fake.closed {
		t.Fatal("expected cancelled client to be closed")
	}
}

func TestGetStatsDuringBlockedPrepare(t *testing.T) {
	_ = Stop()
	entered := make(chan struct{})
	fake := &blockingPingClient{
		prepareTestClient: prepareTestClient{caps: tailcat.CapExitTCP | tailcat.CapExitUDP},
		entered:           entered,
	}
	installClient(t, fake)

	go func() { _ = Prepare(officialTestToken(t)) }()
	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("Ping did not start")
	}

	done := make(chan struct{})
	go func() {
		_ = GetStatsJSON()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("GetStatsJSON blocked while Prepare held I/O")
	}
	_ = Stop()
}

func TestDetachTunKeepsPreparedClient(t *testing.T) {
	_ = Stop()
	fake := &prepareTestClient{caps: tailcat.CapExitTCP | tailcat.CapExitUDP}
	installClient(t, fake)
	if err := Prepare(officialTestToken(t)); err != nil {
		t.Fatalf("Prepare: %v", err)
	}
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	defer r.Close()
	defer w.Close()
	if err := AttachTun(int(r.Fd())); err != nil {
		t.Fatalf("AttachTun: %v", err)
	}
	if err := DetachTun(); err != nil {
		t.Fatalf("DetachTun: %v", err)
	}
	stats, st := statsState(t)
	if st != StatePrepared || stats.State != "PREPARED" {
		t.Fatalf("expected PREPARED after DetachTun, got state=%s json=%s", st, stats.State)
	}
	if fake.closed {
		t.Fatal("DetachTun must not close the prepared client")
	}
	if stats.HealthUnixSec != 0 {
		t.Fatalf("expected health 0 after detach, got %d", stats.HealthUnixSec)
	}

	r2, w2, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe2: %v", err)
	}
	defer r2.Close()
	defer w2.Close()
	if err := AttachTun(int(r2.Fd())); err != nil {
		t.Fatalf("reattach after DetachTun: %v", err)
	}
	stats, st = statsState(t)
	if st != StateRunning || stats.State != "RUNNING" {
		t.Fatalf("expected RUNNING after reattach, got state=%s json=%s", st, stats.State)
	}
	if err := DetachTun(); err != nil {
		t.Fatalf("second DetachTun: %v", err)
	}
	if err := DetachTun(); err != nil {
		t.Fatalf("idempotent DetachTun: %v", err)
	}
}

func TestAttachTunBeforePrepare(t *testing.T) {
	_ = Stop()
	if err := AttachTun(3); err == nil {
		t.Fatal("expected AttachTun without Prepare to fail")
	}
}

func TestPumpDeathFailsEngine(t *testing.T) {
	_ = Stop()
	fake := &prepareTestClient{caps: tailcat.CapExitTCP | tailcat.CapExitUDP}
	installClient(t, fake)
	if err := Prepare(officialTestToken(t)); err != nil {
		t.Fatalf("Prepare: %v", err)
	}
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	if err := AttachTun(int(r.Fd())); err != nil {
		r.Close()
		w.Close()
		t.Fatalf("AttachTun: %v", err)
	}
	_ = r.Close()
	_ = w.Close()

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		_, st := statsState(t)
		if st == StateFailed {
			stats, _ := statsState(t)
			if stats.HealthUnixSec != 0 {
				t.Fatalf("expected health 0 after FAILED, got %d", stats.HealthUnixSec)
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	_, st := statsState(t)
	t.Fatalf("expected FAILED after pipe close, got %s", st)
}

func TestConcurrentStopIdempotent(t *testing.T) {
	_ = Stop()
	fake := &prepareTestClient{caps: tailcat.CapExitTCP | tailcat.CapExitUDP}
	installClient(t, fake)
	if err := Prepare(officialTestToken(t)); err != nil {
		t.Fatalf("Prepare: %v", err)
	}
	errCh := make(chan error, 2)
	go func() { errCh <- Stop() }()
	go func() { errCh <- Stop() }()
	for i := 0; i < 2; i++ {
		select {
		case err := <-errCh:
			if err != nil {
				t.Fatalf("Stop: %v", err)
			}
		case <-time.After(3 * time.Second):
			t.Fatal("concurrent Stop timed out")
		}
	}
	_, st := statsState(t)
	if st != StateStopped {
		t.Fatalf("expected STOPPED, got %s", st)
	}
}

type blockingPingClient struct {
	prepareTestClient
	entered chan struct{}
}

func (c *blockingPingClient) Ping(ctx context.Context) (tailcat.PingResult, error) {
	if c.entered != nil {
		select {
		case <-c.entered:
		default:
			close(c.entered)
		}
	}
	<-ctx.Done()
	c.closed = true
	return tailcat.PingResult{}, ctx.Err()
}

func (c *blockingPingClient) Close() error {
	c.closed = true
	return nil
}

func TestDNSPolicyPendingAppliedOnAttachAndPreservedOnRoam(t *testing.T) {
	_ = Stop()
	fake := &prepareTestClient{caps: tailcat.CapExitTCP | tailcat.CapExitUDP}
	installClient(t, fake)

	forced := "8.8.8.8"
	policy := "FORCED_RESOLVER"
	if err := UpdateNetworkState(`{"isOnline":true,"networkType":"WIFI","interfaces":[],"dnsPolicy":"` + policy + `","forcedDns":"` + forced + `"}`); err != nil {
		t.Fatalf("pending DNS: %v", err)
	}
	pending := globalCore.pendingDNS.Load()
	if pending == nil || pending.Policy != "FORCED_RESOLVER" || pending.ForcedDNS.Addr().String() != "8.8.8.8" {
		t.Fatalf("expected pending FORCED 8.8.8.8, got %+v", pending)
	}

	if err := Prepare(officialTestToken(t)); err != nil {
		t.Fatalf("Prepare: %v", err)
	}
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	defer r.Close()
	defer w.Close()
	if err := AttachTun(int(r.Fd())); err != nil {
		t.Fatalf("AttachTun: %v", err)
	}

	globalCore.mu.Lock()
	bridge := globalCore.sess.bridge
	globalCore.mu.Unlock()
	cfg := bridge.GetDNSConfig()
	if cfg == nil || cfg.Policy != "FORCED_RESOLVER" || cfg.ForcedDNS.Addr().String() != "8.8.8.8" {
		t.Fatalf("expected bridge FORCED 8.8.8.8 after attach, got %+v", cfg)
	}

	if err := UpdateNetworkState(`{"isOnline":true,"networkType":"CELL","interfaces":[]}`); err != nil {
		t.Fatalf("roam: %v", err)
	}
	cfg = bridge.GetDNSConfig()
	if cfg == nil || cfg.Policy != "FORCED_RESOLVER" || cfg.ForcedDNS.Addr().String() != "8.8.8.8" {
		t.Fatalf("roam wiped DNS policy: %+v", cfg)
	}

	profile := "PROFILE_RESOLVER"
	if err := UpdateNetworkState(`{"isOnline":true,"networkType":"WIFI","interfaces":[],"dnsPolicy":"` + profile + `"}`); err != nil {
		t.Fatalf("explicit profile: %v", err)
	}
	cfg = bridge.GetDNSConfig()
	if cfg == nil || cfg.Policy != "PROFILE_RESOLVER" {
		t.Fatalf("expected PROFILE overwrite, got %+v", cfg)
	}
}

func TestEmptyNetworkStateDoesNotClearDNSPolicy(t *testing.T) {
	_ = Stop()
	policy := "FORCED_RESOLVER"
	forced := "1.1.1.1"
	if err := UpdateNetworkState(`{"isOnline":true,"networkType":"WIFI","interfaces":[],"dnsPolicy":"` + policy + `","forcedDns":"` + forced + `"}`); err != nil {
		t.Fatal(err)
	}
	if err := UpdateNetworkState(""); err != nil {
		t.Fatal(err)
	}
	pending := globalCore.pendingDNS.Load()
	if pending == nil || pending.Policy != "FORCED_RESOLVER" {
		t.Fatalf("empty JSON cleared DNS policy: %+v", pending)
	}
}
