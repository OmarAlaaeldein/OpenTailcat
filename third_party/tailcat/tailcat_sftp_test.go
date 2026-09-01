// Copyright (c) Tailscale Inc & contributors
// SPDX-License-Identifier: BSD-3-Clause

//go:build (linux || darwin || windows) && !ts_omit_ssh

package tailcat_test

import (
	"io"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/pkg/sftp"
	"github.com/tailscale/tailcat"
)

// newServeDir returns a directory with some existing content to serve:
// existing.txt, sub/, sub/inner.txt, and (except on Windows) an
// escape symlink pointing at a secret file outside the directory.
func newServeDir(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "existing.txt"), []byte("existing content"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.Mkdir(filepath.Join(dir, "sub"), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "sub", "inner.txt"), []byte("inner"), 0644); err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" {
		outside := filepath.Join(t.TempDir(), "secret.txt")
		if err := os.WriteFile(outside, []byte("secret"), 0644); err != nil {
			t.Fatal(err)
		}
		if err := os.Symlink(outside, filepath.Join(dir, "escape")); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

// sftpClient connects an SFTP client to a rooted file service for dir
// with the given mode, over an in-memory pipe with no SSH transport.
func sftpClient(t *testing.T, dir string, mode tailcat.FileServeMode) *sftp.Client {
	t.Helper()
	srvConn, cliConn := net.Pipe()
	go tailcat.ServeRootedSFTPForTest(srvConn, &tailcat.FileService{Dir: dir, Mode: mode})
	c, err := sftp.NewClientPipe(cliConn, cliConn)
	if err != nil {
		t.Fatalf("NewClientPipe: %v", err)
	}
	t.Cleanup(func() { c.Close() })
	return c
}

func readFile(c *sftp.Client, name string) (string, error) {
	f, err := c.Open(name)
	if err != nil {
		return "", err
	}
	defer f.Close()
	all, err := io.ReadAll(f)
	return string(all), err
}

func writeFile(c *sftp.Client, name, content string) error {
	// O_WRONLY, not sftp.Client.Create's O_RDWR: upload tools like
	// scp open write-only, and write-only mode rejects readable
	// handles.
	f, err := c.OpenFile(name, os.O_WRONLY|os.O_CREATE|os.O_TRUNC)
	if err != nil {
		return err
	}
	if _, err := f.Write([]byte(content)); err != nil {
		f.Close()
		return err
	}
	return f.Close()
}

func TestSFTPReadOnly(t *testing.T) {
	c := sftpClient(t, newServeDir(t), tailcat.FileServeRO)

	if got, err := readFile(c, "existing.txt"); err != nil || got != "existing content" {
		t.Errorf("read existing.txt = %q, %v; want %q, nil", got, err, "existing content")
	}
	fis, err := c.ReadDir("/")
	if err != nil {
		t.Errorf("ReadDir: %v", err)
	} else if len(fis) < 2 {
		t.Errorf("ReadDir returned %d entries; want at least existing.txt and sub", len(fis))
	}
	if got, err := readFile(c, "sub/inner.txt"); err != nil || got != "inner" {
		t.Errorf("read sub/inner.txt = %q, %v; want %q, nil", got, err, "inner")
	}
	if _, err := c.Stat("existing.txt"); err != nil {
		t.Errorf("Stat existing.txt: %v", err)
	}

	if err := writeFile(c, "new.txt", "x"); err == nil {
		t.Error("Create succeeded in read-only mode")
	}
	if err := c.Remove("existing.txt"); err == nil {
		t.Error("Remove succeeded in read-only mode")
	}
	if err := c.Mkdir("newdir"); err == nil {
		t.Error("Mkdir succeeded in read-only mode")
	}
	if err := c.Rename("existing.txt", "renamed.txt"); err == nil {
		t.Error("Rename succeeded in read-only mode")
	}
	if err := c.Chmod("existing.txt", 0600); err == nil {
		t.Error("Chmod succeeded in read-only mode")
	}
}

func TestSFTPReadWrite(t *testing.T) {
	c := sftpClient(t, newServeDir(t), tailcat.FileServeRW)

	if err := writeFile(c, "new.txt", "hello"); err != nil {
		t.Fatalf("Create new.txt: %v", err)
	}
	if got, err := readFile(c, "new.txt"); err != nil || got != "hello" {
		t.Errorf("read back new.txt = %q, %v; want %q, nil", got, err, "hello")
	}
	if err := c.Mkdir("newdir"); err != nil {
		t.Errorf("Mkdir: %v", err)
	}
	if err := c.Rename("new.txt", "newdir/moved.txt"); err != nil {
		t.Errorf("Rename: %v", err)
	}
	if err := c.Chmod("newdir/moved.txt", 0600); err != nil {
		t.Errorf("Chmod: %v", err)
	}
	if err := c.Remove("newdir/moved.txt"); err != nil {
		t.Errorf("Remove: %v", err)
	}
	if err := c.RemoveDirectory("newdir"); err != nil {
		t.Errorf("RemoveDirectory: %v", err)
	}
}

// TestSFTPNoSymlinkEscape verifies that a symlink inside the served
// directory pointing outside of it can't be read through: os.Root
// refuses to follow it.
func TestSFTPNoSymlinkEscape(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("no symlinks in the test fixture on Windows")
	}
	c := sftpClient(t, newServeDir(t), tailcat.FileServeRW)
	if got, err := readFile(c, "escape"); err == nil {
		t.Errorf("read through escape symlink succeeded, got %q; want error", got)
	}
}

func TestSFTPWriteOnly(t *testing.T) {
	dir := newServeDir(t)
	c := sftpClient(t, dir, tailcat.FileServeWO)

	if err := writeFile(c, "drop.txt", "dropped"); err != nil {
		t.Fatalf("Create drop.txt: %v", err)
	}

	// A session may stat and adjust what it wrote itself.
	if _, err := c.Stat("drop.txt"); err != nil {
		t.Errorf("Stat own drop.txt: %v", err)
	}
	if err := c.Chmod("drop.txt", 0600); err != nil {
		t.Errorf("Chmod own drop.txt: %v", err)
	}
	if err := c.Mkdir("newdir"); err != nil {
		t.Errorf("Mkdir: %v", err)
	}
	if _, err := c.Stat("newdir"); err != nil {
		t.Errorf("Stat own newdir: %v", err)
	}
	if err := writeFile(c, "newdir/nested.txt", "nested"); err != nil {
		t.Errorf("Create newdir/nested.txt: %v", err)
	}

	// Directories stat fine so upload destinations resolve.
	if _, err := c.Stat("/"); err != nil {
		t.Errorf("Stat /: %v", err)
	}
	if _, err := c.Stat("sub"); err != nil {
		t.Errorf("Stat sub: %v", err)
	}

	// Nothing else may be read, listed, stat'd, or modified.
	if got, err := readFile(c, "drop.txt"); err == nil {
		t.Errorf("read own drop.txt succeeded, got %q; want error (write-only)", got)
	}
	if got, err := readFile(c, "existing.txt"); err == nil {
		t.Errorf("read existing.txt succeeded, got %q; want error", got)
	}
	if _, err := c.ReadDir("/"); err == nil {
		t.Error("ReadDir succeeded in write-only mode")
	}
	if _, err := c.Stat("existing.txt"); err == nil {
		t.Error("Stat existing.txt succeeded in write-only mode")
	}
	if err := c.Remove("existing.txt"); err == nil {
		t.Error("Remove existing.txt succeeded in write-only mode")
	}
	if err := c.Rename("existing.txt", "renamed.txt"); err == nil {
		t.Error("Rename succeeded in write-only mode")
	}
	if err := c.Chmod("existing.txt", 0600); err == nil {
		t.Error("Chmod existing.txt succeeded in write-only mode")
	}

	// A different session can't stat the first session's files.
	c2 := sftpClient(t, dir, tailcat.FileServeWO)
	if _, err := c2.Stat("drop.txt"); err == nil {
		t.Error("Stat drop.txt from a second session succeeded; want not-exist")
	}
}
