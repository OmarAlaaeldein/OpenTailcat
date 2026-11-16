//go:build android

package engine

import "tailscale.com/net/netns"

func hookAndroidProtect() {
	netns.SetAndroidProtectFunc(func(fd int) error {
		return protectFD(fd)
	})
	// The application-owned Tailcat integration preserves this setting during
	// engine creation and region selection; no existing sockets are swept.
	netns.SetEnabled(true)
}

func ensureTransportProtect() {
	hookAndroidProtect()
}
