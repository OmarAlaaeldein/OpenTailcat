package engine

import (
	"errors"
	"runtime"
	"sync"
)

type SocketProtector interface {
	Protect(fd int) bool
}

var (
	protectorMu      sync.Mutex
	currentProtector SocketProtector
)

func SetSocketProtector(p SocketProtector) {
	defer recoverExported("SetSocketProtector", nil)
	protectorMu.Lock()
	if exitNodeClaim.Load() {
		// Never clobber the exit node's allowAllProtector while it owns the engine.
		protectorMu.Unlock()
		return
	}
	currentProtector = p
	protectorMu.Unlock()
	hookAndroidProtect()
}

func protectFD(fd int) error {
	protectorMu.Lock()
	p := currentProtector
	protectorMu.Unlock()
	if p == nil {
		if runtime.GOOS == "android" {
			return errors.New("android transport socket protector is not installed")
		}
		return nil
	}
	if !p.Protect(fd) {
		return errors.New("VpnService.protect failed")
	}
	return nil
}

// EnsureTransportProtect is retained for bridge compatibility. Android's
// creation-time hook now stays enabled throughout Tailcat initialization.
func EnsureTransportProtect() {
	defer recoverExported("EnsureTransportProtect", nil)
	ensureTransportProtect()
}
