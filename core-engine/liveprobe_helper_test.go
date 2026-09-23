//go:build liveprobe

package engine

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// loadLiveToken reads the live gateway token without requiring the CI-only
// /workspace path. Order:
//  1. OPENTAILCAT_LIVE_TOKEN env (preferred; never written to disk)
//  2. /workspace/.opentailcat-private/live-token.txt (CI)
//  3. $HOME/.opentailcat-private/live-token.txt (local macOS/Linux)
func loadLiveToken(t *testing.T) string {
	t.Helper()
	if tok := strings.TrimSpace(os.Getenv("OPENTAILCAT_LIVE_TOKEN")); tok != "" {
		return tok
	}
	for _, p := range []string{
		"/workspace/.opentailcat-private/live-token.txt",
		filepath.Join(os.Getenv("HOME"), ".opentailcat-private", "live-token.txt"),
	} {
		raw, err := os.ReadFile(p)
		if err == nil {
			if tok := strings.TrimSpace(string(raw)); tok != "" {
				return tok
			}
		}
	}
	t.Fatal("no live token: set OPENTAILCAT_LIVE_TOKEN or write ~/.opentailcat-private/live-token.txt")
	return ""
}
