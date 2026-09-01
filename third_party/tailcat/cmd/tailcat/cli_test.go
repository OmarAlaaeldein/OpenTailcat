// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

package main

import (
	"bytes"
	"errors"
	"os/exec"
	"strings"
	"testing"

	"github.com/peterbourgon/ff/v4"
	"github.com/peterbourgon/ff/v4/ffhelp"
)

// TestHelpListsCommandTree verifies that the generated root help
// mentions every subcommand and the global flags, the declarative
// help dump that motivated the ff port.
func TestHelpListsCommandTree(t *testing.T) {
	help := ffhelp.Command(newRootCommand()).String()
	for _, want := range []string{
		"serve", "recv", "ping", "socks", "ssh", "cp", "parse", "resolve",
		"genkey", "printpub", "version", "readme",
		"--serve", "--key", "--derpmap-url",
	} {
		if !strings.Contains(help, want) {
			t.Errorf("root help is missing %q", want)
		}
	}
	if strings.Contains(help, "\n  -serve") {
		t.Errorf("root help renders single-dash flags")
	}
	// Server-only flags live on the serve subcommand, not the root.
	// (The long help prose may still mention them; only reject them
	// as rendered flag entries.)
	for _, notWant := range []string{"\n  --allow", "\n  --full-address"} {
		if strings.Contains(help, notWant) {
			t.Errorf("root help lists server-only flag %q", strings.TrimSpace(notWant))
		}
	}
}

// TestServeHelpListsServerFlags verifies that the server-only flags
// moved off the root command render in the serve subcommand's help.
func TestServeHelpListsServerFlags(t *testing.T) {
	root := newRootCommand()
	var serve *ff.Command
	for _, sub := range root.Subcommands {
		if sub.Name == "serve" {
			serve = sub
		}
	}
	if serve == nil {
		t.Fatal("no serve subcommand")
	}
	help := ffhelp.Command(serve).String()
	for _, want := range []string{"--allow", "--full-address", "--key"} {
		if !strings.Contains(help, want) {
			t.Errorf("serve help is missing %q", want)
		}
	}
}

// parseCLI parses args against a fresh command tree and returns the
// root command. It doesn't run anything.
func parseCLI(t *testing.T, args ...string) (root *ff.Command, err error) {
	t.Helper()
	root = newRootCommand()
	return root, root.Parse(args)
}

// TestServeSubcommand verifies that the serve subcommand takes port
// and service lists as positional arguments, accepts server flags,
// and rejects mixing positional arguments with the --serve flag.
func TestServeSubcommand(t *testing.T) {
	root, err := parseCLI(t, "serve", "--key=new", "80,no-auth-ssh", "8000-8999")
	if err != nil {
		t.Fatal(err)
	}
	sel := root.GetSelected()
	if sel.Name != "serve" {
		t.Fatalf("selected command = %q; want serve", sel.Name)
	}
	if *flagKey != "new" {
		t.Errorf("--key = %q; want new", *flagKey)
	}
	got := sel.Flags.(*ff.FlagSet).GetArgs()
	want := []string{"80,no-auth-ssh", "8000-8999"}
	if strings.Join(got, " ") != strings.Join(want, " ") {
		t.Errorf("leftover args = %q; want %q", got, want)
	}

	root, err = parseCLI(t, "serve", "--serve=80", "443")
	if err != nil {
		t.Fatal(err)
	}
	err = root.Run(t.Context())
	var ue usageError
	if !errors.As(err, &ue) {
		t.Errorf("serve --serve=80 443: err = %v; want a usageError", err)
	}
}

// TestSSHTrailingArgs verifies that flag parsing stops at the ssh
// destination, so a remote command's own flags (here "-la") are
// passed through rather than parsed.
func TestSSHTrailingArgs(t *testing.T) {
	root, err := parseCLI(t, "ssh", "-p", "2222", "user@tcblob", "ls", "-la")
	if err != nil {
		t.Fatal(err)
	}
	sel := root.GetSelected()
	if sel.Name != "ssh" {
		t.Fatalf("selected command = %q; want ssh", sel.Name)
	}
	f, ok := sel.Flags.GetFlag("p")
	if !ok {
		t.Fatal("no -p flag")
	}
	if got := f.GetValue(); got != "2222" {
		t.Errorf("-p = %q; want 2222", got)
	}
	got := sel.Flags.(*ff.FlagSet).GetArgs()
	want := []string{"user@tcblob", "ls", "-la"}
	if strings.Join(got, " ") != strings.Join(want, " ") {
		t.Errorf("leftover args = %q; want %q", got, want)
	}
}

// TestSocksTrailingArgs verifies that a child command's flags after
// the address blob are left unparsed.
func TestSocksTrailingArgs(t *testing.T) {
	root, err := parseCLI(t, "socks", "--listen=1080", "tcblob", "curl", "--fail", "http://x/")
	if err != nil {
		t.Fatal(err)
	}
	sel := root.GetSelected()
	if sel.Name != "socks" {
		t.Fatalf("selected command = %q; want socks", sel.Name)
	}
	got := sel.Flags.(*ff.FlagSet).GetArgs()
	want := []string{"tcblob", "curl", "--fail", "http://x/"}
	if strings.Join(got, " ") != strings.Join(want, " ") {
		t.Errorf("leftover args = %q; want %q", got, want)
	}
}

// TestGlobalFlagPlacement verifies that global flags work both before
// the subcommand (the only placement the old CLI accepted) and after
// it (via flag set parenting).
func TestGlobalFlagPlacement(t *testing.T) {
	for _, args := range [][]string{
		{"--key=new", "--derpmap-url=http://d/", "ping", "--timeout=5s", "tcblob"},
		{"ping", "--key=new", "--derpmap-url=http://d/", "--timeout=5s", "tcblob"},
	} {
		if _, err := parseCLI(t, args...); err != nil {
			t.Fatalf("parse %q: %v", args, err)
		}
		if *flagKey != "new" {
			t.Errorf("parse %q: --key = %q; want new", args, *flagKey)
		}
		if *flagDERPMapURL != "http://d/" {
			t.Errorf("parse %q: --derpmap-url = %q; want http://d/", args, *flagDERPMapURL)
		}
	}
}

// TestHelpRequests verifies that -h, --help, and the help argument
// all report ErrHelp instead of being treated as an address blob.
func TestHelpRequests(t *testing.T) {
	for _, args := range [][]string{
		{"-h"},
		{"--help"},
		{"genkey", "--help"},
		{"ssh", "-h"},
	} {
		if _, err := parseCLI(t, args...); !errors.Is(err, ff.ErrHelp) {
			t.Errorf("parse %q: err = %v; want ErrHelp", args, err)
		}
	}

	root, err := parseCLI(t, "help")
	if err != nil {
		t.Fatalf("parse help: %v", err)
	}
	if err := root.Run(t.Context()); !errors.Is(err, ff.ErrHelp) {
		t.Errorf("run help: err = %v; want ErrHelp", err)
	}
}

// TestHelpGoesToStdout verifies that explicitly requested help is
// written to stdout, so it can be piped into a pager, while
// usage-error help stays on stderr, off a pipeline's stdout.
func TestHelpGoesToStdout(t *testing.T) {
	bin := buildTailcat(t)
	run := func(args ...string) (stdout, stderr string, err error) {
		var outBuf, errBuf bytes.Buffer
		cmd := exec.Command(bin, args...)
		cmd.Stdout = &outBuf
		cmd.Stderr = &errBuf
		err = cmd.Run()
		return outBuf.String(), errBuf.String(), err
	}

	stdout, stderr, err := run("-h")
	if err != nil {
		t.Fatalf("-h: %v", err)
	}
	if !strings.Contains(stdout, "SUBCOMMANDS") {
		t.Errorf("-h stdout = %q; want help text", stdout)
	}
	if stderr != "" {
		t.Errorf("-h stderr = %q; want empty", stderr)
	}

	// A trailing --serve with no value gets the serve subcommand's
	// help rather than ff's bare "missing value" parse error.
	stdout, stderr, err = run("--serve")
	if err != nil {
		t.Fatalf("--serve: %v", err)
	}
	if !strings.Contains(stdout, "tailcat serve [flags]") {
		t.Errorf("--serve stdout = %q; want serve help text", stdout)
	}
	if stderr != "" {
		t.Errorf("--serve stderr = %q; want empty", stderr)
	}

	stdout, stderr, err = run("genkey")
	if err == nil {
		t.Error("genkey: succeeded; want usage error")
	}
	if stdout != "" {
		t.Errorf("genkey stdout = %q; want empty", stdout)
	}
	if !strings.Contains(stderr, "--key=<name>") {
		t.Errorf("genkey stderr = %q; want usage error with help", stderr)
	}
}

// TestGenkeyRequiresKeyName verifies that genkey no longer invents a
// key name: writing a key to disk is a big enough action that the
// user has to pick the name, with the magic names suggested.
func TestGenkeyRequiresKeyName(t *testing.T) {
	for _, tt := range []struct {
		args []string
		want string // expected substring of the error
	}{
		{[]string{"genkey"}, `"default"`},
		{[]string{"genkey", "--client"}, `"client-default"`},
	} {
		root, err := parseCLI(t, tt.args...)
		if err != nil {
			t.Fatalf("parse %q: %v", tt.args, err)
		}
		err = root.Run(t.Context())
		if err == nil || !strings.Contains(err.Error(), "--key=<name>") || !strings.Contains(err.Error(), tt.want) {
			t.Errorf("run %q: err = %v; want one requiring --key=<name> and mentioning %s", tt.args, err, tt.want)
		}
		var ue usageError
		if !errors.As(err, &ue) {
			t.Errorf("run %q: err is not a usageError", tt.args)
		}
	}

	// The server-mode magic name with --client is almost certainly a
	// mix-up.
	root, err := parseCLI(t, "genkey", "--client", "--key=default")
	if err != nil {
		t.Fatal(err)
	}
	err = root.Run(t.Context())
	if err == nil || !strings.Contains(err.Error(), "--key=client-default") {
		t.Errorf("genkey --client --key=default: err = %v; want one suggesting --key=client-default", err)
	}
}

// TestVersionSubcommand verifies that "tailcat version" dispatches to
// the version subcommand rather than being treated as an address blob.
func TestVersionSubcommand(t *testing.T) {
	root, err := parseCLI(t, "version")
	if err != nil {
		t.Fatal(err)
	}
	if sel := root.GetSelected(); sel.Name != "version" {
		t.Errorf("selected command = %q; want version", sel.Name)
	}
}

// TestUnknownArgSelectsRoot verifies that a non-subcommand first
// argument still selects the root command, whose exec treats it as an
// address blob in client pipe mode.
func TestUnknownArgSelectsRoot(t *testing.T) {
	root, err := parseCLI(t, "tcSOMEBLOB", "80")
	if err != nil {
		t.Fatal(err)
	}
	if sel := root.GetSelected(); sel != root {
		t.Errorf("selected command = %q; want root", sel.Name)
	}
}
