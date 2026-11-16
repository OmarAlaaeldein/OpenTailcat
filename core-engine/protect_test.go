package engine

import (
	"io"
	"net/http"
	"net/http/httptest"
	"runtime"
	"sync/atomic"
	"testing"
)

type testProtector struct {
	fds []int
}

func (t *testProtector) Protect(fd int) bool {
	t.fds = append(t.fds, fd)
	return true
}

func TestProtectFDWithoutProtector(t *testing.T) {
	SetSocketProtector(nil)
	if runtime.GOOS == "android" {
		if err := protectFD(3); err == nil {
			t.Fatal("Android accepted a transport socket without a protector")
		}
		return
	}
	if err := protectFD(3); err != nil {
		t.Fatalf("nil protector: %v", err)
	}
}

type protectorFunc func(int) bool

func (f protectorFunc) Protect(fd int) bool { return f(fd) }

func TestNativeMetadataHTTPProtectsBeforeConnecting(t *testing.T) {
	var protected, received atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if protected.Load() == 0 {
			t.Error("HTTP reached the server before socket protection")
		}
		received.Add(1)
		_, _ = w.Write([]byte("metadata"))
	}))
	defer server.Close()
	SetSocketProtector(protectorFunc(func(fd int) bool {
		protected.Add(1)
		return fd >= 0
	}))
	t.Cleanup(func() { SetSocketProtector(nil) })
	response, err := http.Get(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, response.Body)
	_ = response.Body.Close()
	if protected.Load() == 0 || received.Load() != 1 {
		t.Fatal("native metadata request did not exercise its protection hook")
	}
}

func TestNativeMetadataHTTPProtectionFailureStopsConnect(t *testing.T) {
	var received atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		received.Add(1)
	}))
	defer server.Close()
	SetSocketProtector(protectorFunc(func(int) bool { return false }))
	t.Cleanup(func() { SetSocketProtector(nil) })
	response, err := http.Get(server.URL)
	if response != nil {
		_ = response.Body.Close()
	}
	if err == nil || received.Load() != 0 {
		t.Fatal("failed socket protection allowed an HTTP connection")
	}
}

func TestProtectFDInvokesHook(t *testing.T) {
	p := &testProtector{}
	SetSocketProtector(p)
	t.Cleanup(func() { SetSocketProtector(nil) })
	if err := protectFD(7); err != nil {
		t.Fatal(err)
	}
	if len(p.fds) != 1 || p.fds[0] != 7 {
		t.Fatalf("got %v", p.fds)
	}
}

func TestEnsureTransportProtectNoopOrSafe(t *testing.T) {
	// On non-Android this is a no-op; on Android it re-enables netns protect.
	EnsureTransportProtect()
	p := &testProtector{}
	SetSocketProtector(p)
	t.Cleanup(func() { SetSocketProtector(nil) })
	EnsureTransportProtect()
	if err := protectFD(9); err != nil {
		t.Fatal(err)
	}
	if len(p.fds) != 1 || p.fds[0] != 9 {
		t.Fatalf("got %v", p.fds)
	}
}
