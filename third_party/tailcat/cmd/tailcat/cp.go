// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

//go:build !ts_omit_ssh

package main

import (
	"context"
	"log"
	"os"
	"os/exec"
	"strings"

	"github.com/peterbourgon/ff/v4"
)

// cpCommand returns the "tailcat cp" subcommand, with parent as the
// parent flag set for the global flags.
func cpCommand(parent *ff.FlagSet) *ff.Command {
	fs := ff.NewFlagSet("cp").SetParent(parent)
	recursive := fs.BoolShort('r', "recursively copy directories")
	preserve := fs.BoolShort('p', "preserve modification times and modes")
	port := fs.StringShort('P', "22", "port number of the server's SSH (file service) port")
	return &ff.Command{
		Name:      "cp",
		Usage:     "tailcat cp [-r] [-p] <source>... <target>",
		ShortHelp: "copy files to or from a tailcat server, using the system scp",
		LongHelp:  cpLongHelp,
		Flags:     fs,
		Exec: func(ctx context.Context, args []string) error {
			return clientCPMode(*recursive, *preserve, *port, args)
		},
	}
}

const cpLongHelp = `Remote paths are written <addrblob>:[path], like scp's host:path.
Paths are relative to the server's served directory ("tailcat serve
files"), or to the remote home directory for a full SSH server
("tailcat serve no-auth-ssh"). A DNS name with a "tailcat=" TXT
record works in place of an address blob.

Copy a file to a server, keeping its name, and fetch it back:

	tailcat cp foo.txt <addrblob>:
	tailcat cp <addrblob>:foo.txt copy.txt

Copy a directory tree to a directory the server offers read-write:

	tailcat cp -r ./photos <addrblob>:photos

The actual copying is done by the system scp, with the connection
routed through tailcat, so scp's progress display applies.`

// clientCPMode runs the system scp with all remote arguments routed
// through one tailcat server.
func clientCPMode(recursive, preserve bool, portOrIPPort string, args []string) error {
	if len(args) < 2 {
		return usagef("cp requires at least one source and a target")
	}

	// Translate <addrblob>:path arguments to scp host:path ones. The
	// host handed to scp is a short deterministic label (see
	// sshDestHost); the blob itself does the routing, inside the
	// ProxyCommand.
	blob := ""
	scpArgs := make([]string, 0, len(args))
	for _, arg := range args {
		host, path, ok := splitRemoteArg(arg)
		if !ok {
			scpArgs = append(scpArgs, arg)
			continue
		}
		if blob != "" && host != blob {
			return usagef("all remote paths must name the same server (%q and %q differ)", blob, host)
		}
		blob = host
		scpArgs = append(scpArgs, sshDestHost(host)+":"+path)
	}
	if blob == "" {
		return usagef("no remote <addrblob>:path argument; nothing to copy through tailcat")
	}

	exe, err := os.Executable()
	if err != nil {
		log.Fatal(err)
	}
	scpExe, err := exec.LookPath("scp")
	if err != nil {
		log.Fatalf("no scp found in $PATH: %v", err)
	}
	argv := []string{
		scpExe,
		"-o", "UpdateHostKeys no",
		"-o", "StrictHostKeyChecking no",
		"-o", "UserKnownHostsFile " + os.DevNull,
		"-o", "LogLevel ERROR",
		"-o", "ProxyCommand=" + sshProxyCommand(exe, *flagKey, *flagDERPMapURL, blob, portOrIPPort),
	}
	if recursive {
		argv = append(argv, "-r")
	}
	if preserve {
		argv = append(argv, "-p")
	}
	argv = append(argv, scpArgs...)
	err = execSSH(scpExe, argv)
	log.Fatalf("failed to run scp: %v", err)
	return nil
}

// splitRemoteArg splits an scp-style remote argument "host:path",
// where host is an address blob or a DNS name with a "tailcat=" TXT
// record. ok reports whether arg is remote: it has a colon that
// isn't preceded by a path separator, and the part before the colon
// is longer than one character (so a Windows drive path like
// "C:\foo" stays local).
func splitRemoteArg(arg string) (host, path string, ok bool) {
	i := strings.Index(arg, ":")
	if i <= 1 {
		return "", "", false
	}
	if strings.ContainsAny(arg[:i], `/\`) {
		return "", "", false
	}
	return arg[:i], arg[i+1:], true
}
