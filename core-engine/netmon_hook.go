package engine

import (
	"github.com/tailscale/tailcat"
	"tailscale.com/net/netmon"
)

// linkChangeNotifier is the part of netmon.Monitor that UpdateNetworkState
// uses: InjectEvent makes the monitor re-read the interfaces and, on a major
// change, makes the engine rebind Magicsock and re-STUN.
type linkChangeNotifier interface {
	InjectEvent()
}

// netMonitorSource is implemented by prepared clients that can hand out the
// network monitor their WireGuard engine listens to.
type netMonitorSource interface {
	netMonitor() linkChangeNotifier
}

// setDefaultRouteInterface tells netmon which interface carries the default
// route. Only Android's netmon reads it (netmon_android.go); without it every
// change looks non-viable and never triggers a rebind.
var setDefaultRouteInterface = func(string) {}

func (c *engineClient) netMonitor() linkChangeNotifier {
	if mon := clientNetMon(c.Client); mon != nil {
		return mon
	}
	return nil
}

// clientNetMon uses the application-owned, compile-checked integration API.
// A missing monitor is an initialization failure for real engine clients.
func clientNetMon(c *tailcat.Client) *netmon.Monitor {
	mon, err := c.NetworkMonitor()
	if err != nil {
		return nil
	}
	return mon
}
