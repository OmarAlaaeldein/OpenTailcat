package engine

import (
	"log"
	"strings"
	"sync/atomic"
)

// verboseLogs is Kotlin's debug diagnostics switch, received as
// "verboseLogs" in updateNetworkState. Off until Kotlin turns it on.
var verboseLogs atomic.Bool

// quietSummaries are upstream log formats that still get a line when verbose
// logs are off. Only the constant text before the first formatting verb is
// printed, so no address or key reaches the log.
var quietSummaries = []string{
	"LinkChange: ",
}

// tailcatLogf is the Tailcat client's logger. Upstream (magicsock, netcheck,
// WireGuard) logs the public IP learned from STUN, local addresses and peer
// endpoints, and Android copies logcat into bug reports, so those lines are
// printed only when verbose logs are on.
func tailcatLogf(format string, args ...any) {
	if verboseLogs.Load() {
		log.Printf(format, args...)
		return
	}
	if s, ok := quietSummary(format); ok {
		log.Print(s)
	}
}

func quietSummary(format string) (string, bool) {
	for _, prefix := range quietSummaries {
		if !strings.HasPrefix(format, prefix) {
			continue
		}
		if i := strings.IndexByte(format, '%'); i >= 0 {
			format = format[:i]
		}
		return strings.TrimRight(format, " :"), true
	}
	return "", false
}
