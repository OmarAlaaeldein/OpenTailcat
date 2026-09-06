//go:build android

package engine

import "tailscale.com/net/netns"

func hookAndroidProtect() {
	netns.SetAndroidProtectFunc(func(fd int) error {
		return protectFD(fd)
	})
	// Tailcat createEngine calls netns.SetEnabled(false), which strips the
	// Control hook from Magicsock listeners and DERP dialers. Re-enable so
	// VpnService.protect actually runs on transport sockets (AUDIT H2).
	netns.SetEnabled(true)
}

func ensureTransportProtect() {
	hookAndroidProtect()
}
