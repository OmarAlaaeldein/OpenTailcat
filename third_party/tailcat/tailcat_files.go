// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

package tailcat

// This file is intentionally free of build tags: it declares the
// types [Server.SSHConnHandler] takes, so code can name them on
// platforms where the SSH server itself is compiled out.

// FileServeMode says what a rooted SFTP file service lets clients do.
type FileServeMode byte

const (
	// FileServeRO serves files read-only: clients can list, stat, and
	// download files, but not modify anything.
	FileServeRO FileServeMode = iota

	// FileServeRW serves files read-write.
	FileServeRW

	// FileServeWO serves files write-only, as a drop box: clients can
	// upload files and make directories, but can't list the directory
	// or read anything back. Paths a connection created itself may
	// still be stat'd, so upload tools that verify their writes keep
	// working; directories may be stat'd too, so upload destinations
	// resolve.
	FileServeWO
)

// FileService describes a rooted SFTP file service. All client paths
// are resolved inside Dir via [os.Root], so neither ".." nor symlinks
// can escape it.
type FileService struct {
	// Dir is the directory to serve.
	Dir string

	// Mode says what clients may do within Dir.
	Mode FileServeMode
}

// SSHOptions configures the SSH server returned by
// [Server.SSHConnHandler].
type SSHOptions struct {
	// Shell enables shell and exec sessions.
	Shell bool

	// Files, if non-nil, serves the SFTP subsystem rooted at
	// Files.Dir, restricted to Files.Mode. If nil and Shell is true,
	// SFTP is instead served with the same access the shell has: the
	// whole filesystem, with relative paths resolved against the
	// user's home directory.
	Files *FileService
}
