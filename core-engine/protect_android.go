//go:build android

package engine

import "tailscale.com/net/netns"

func hookAndroidProtect() {
	netns.SetAndroidProtectFunc(func(fd int) error {
		return protectFD(fd)
	})
}
