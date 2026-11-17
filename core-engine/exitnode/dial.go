package exitnode

import (
	"net"
	"time"
)

// osDialer is the only place this package creates OS sockets. A guest's flow
// leaves the phone through it, over whatever route the device currently has
// (Wi-Fi or cellular). TestOSSocketsConfinedToDial keeps it that way.
//
// Platform note: when this runs on Android the dialer must not be captured by
// a VPN this app owns. The service layer refuses to start an exit node while
// the client tunnel is up; see AGENTS.md.
func osDialer() Dialer {
	return &net.Dialer{
		Timeout:   tcpDialTimeout,
		KeepAlive: 30 * time.Second,
	}
}
