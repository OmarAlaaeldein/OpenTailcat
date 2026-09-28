package engine

import "tailscale.com/net/netmon"

// Android's netmon cannot read the routing table, so it takes the default
// route interface from the app (as Tailscale's Android client does).
func init() {
	setDefaultRouteInterface = netmon.UpdateLastKnownDefaultRouteInterface
}
