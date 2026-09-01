// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

package buildtags

import (
	"os"
	"strings"
	"testing"
)

// TestReleaseTagsInSync asserts that the checked-in build-tags.txt
// file (the entrypoint for third-party packagers) and the -tags= line
// in .goreleaser.yaml both carry exactly the tags ReleaseTags
// returns. It reads the yaml as plain text on purpose: the repo has
// no YAML dependency and an exact substring match is all that is
// needed. Regenerate both with: go run ./internal/buildtags/printtags
func TestReleaseTagsInSync(t *testing.T) {
	const regen = "regenerate with: go run ./internal/buildtags/printtags"

	txt, err := os.ReadFile("../../build-tags.txt")
	if err != nil {
		t.Fatal(err)
	}
	// A .gitattributes rule keeps the file LF, but clones that predate
	// the rule on a core.autocrlf=true checkout can still see CRLF, so
	// normalize before comparing.
	got := strings.ReplaceAll(string(txt), "\r\n", "\n")
	if want := ReleaseTags() + "\n"; got != want {
		t.Errorf("build-tags.txt content is stale; %s\n got: %s\nwant: %s", regen, got, want)
	}

	yml, err := os.ReadFile("../../.goreleaser.yaml")
	if err != nil {
		t.Fatal(err)
	}
	if want := "-tags=" + ReleaseTags(); !strings.Contains(string(yml), want) {
		t.Errorf(".goreleaser.yaml does not contain %q; %s", want, regen)
	}
	if n := strings.Count(string(yml), "-tags="); n != 1 {
		t.Errorf(".goreleaser.yaml contains %d \"-tags=\" occurrences; want exactly 1", n)
	}
}
