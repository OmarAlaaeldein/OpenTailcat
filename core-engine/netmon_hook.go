package engine

import (
	"reflect"
	"unsafe"

	"github.com/tailscale/tailcat"
	"tailscale.com/net/netmon"
	"tailscale.com/tsd"
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

// clientNetMon returns the netmon.Monitor inside a started Tailcat client, or
// nil. Upstream builds the monitor privately (Client.lb.sys.NetMon) and has no
// accessor. On Android netmon cannot subscribe to route changes: it polls
// every 10 minutes and expects the app to call InjectEvent, so without this
// Magicsock only notices a Wi-Fi/cellular switch through timeouts.
//
// The field path is read with reflection and every step checks its type, so a
// changed upstream layout yields nil (UpdateNetworkState then only records the
// interfaces) and TestClientNetMonReachesTheClientMonitor fails. Call it only
// after the client has started (Prepare's Ping), on the goroutine that started
// it; lb is never reassigned afterwards.
func clientNetMon(c *tailcat.Client) *netmon.Monitor {
	if c == nil {
		return nil
	}
	lb := reflect.ValueOf(c).Elem().FieldByName("lb")
	if !lb.IsValid() || lb.Kind() != reflect.Pointer || lb.IsNil() {
		return nil
	}
	lbv := lb.Elem()
	if lbv.Kind() != reflect.Struct {
		return nil
	}
	sys := lbv.FieldByName("sys")
	if !sys.IsValid() || sys.Type() != reflect.TypeFor[tsd.System]() {
		return nil
	}
	nm := sys.FieldByName("NetMon")
	if !nm.IsValid() || nm.Type() != reflect.TypeFor[tsd.SubSystem[*netmon.Monitor]]() || !nm.CanAddr() {
		return nil
	}
	sub := (*tsd.SubSystem[*netmon.Monitor])(unsafe.Pointer(nm.UnsafeAddr()))
	mon, ok := sub.GetOK()
	if !ok {
		return nil
	}
	return mon
}
