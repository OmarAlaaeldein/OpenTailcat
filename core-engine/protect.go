package engine

import (
	"errors"
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
	protectorMu.Lock()
	currentProtector = p
	protectorMu.Unlock()
	hookAndroidProtect()
}

func protectFD(fd int) error {
	protectorMu.Lock()
	p := currentProtector
	protectorMu.Unlock()
	if p == nil {
		return nil
	}
	if !p.Protect(fd) {
		return errors.New("VpnService.protect failed")
	}
	return nil
}
