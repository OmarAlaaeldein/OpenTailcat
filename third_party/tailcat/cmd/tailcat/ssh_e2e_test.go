// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

// The SSH server side only exists on these platforms (see
// tailcat.SupportsSSHServer), and the client side runs the system ssh.

//go:build linux || darwin || windows

package main

import (
	"os/exec"
	"strings"
	"testing"
	"time"
)

// TestServeNoAuthSSH runs a --serve=no-auth-ssh server and connects
// to it with "tailcat ssh", which execs the system ssh client with a
// ProxyCommand that runs the tailcat binary itself. The exec form
// ("echo hi") never starts an interactive shell or allocates a PTY:
// the server ignores the requested SSH user and runs the current
// user's shell (PowerShell on Windows). All state stays in temp
// dirs: the server's generated host key lands under the test config
// dir, and the client runs with StrictHostKeyChecking off and a null
// known hosts file.
func TestServeNoAuthSSH(t *testing.T) {
	if _, err := exec.LookPath("ssh"); err != nil {
		t.Skipf("no ssh client in $PATH: %v", err)
	}
	e := newTestEnv(t)

	_, blob, _ := e.startServer("--serve=no-auth-ssh")

	client := e.cmd("--key=new", "--derpmap-url="+e.derpMapURL, "ssh", blob, "echo", "hi")
	done := make(chan struct{})
	go func() {
		select {
		case <-done:
		case <-time.After(30 * time.Second):
			client.Process.Kill()
		}
	}()
	out, err := client.CombinedOutput()
	close(done)
	if err != nil {
		t.Fatalf("tailcat ssh: %v\n%s", err, out)
	}
	// TrimSpace: PowerShell on Windows emits "hi\r\n".
	if got, want := strings.TrimSpace(string(out)), "hi"; got != want {
		t.Errorf("tailcat ssh output = %q; want %q", got, want)
	}
}
