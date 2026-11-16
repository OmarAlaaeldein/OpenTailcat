package engine

import (
	"os/exec"
	"testing"
)

func TestTailcatIntegrationMatchesPinnedSource(t *testing.T) {
	cmd := exec.Command("python3", "-B", "integration/sync_tailcat.py", "--check")
	if output, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("integration source/patch verification: %v\n%s", err, output)
	}
}
