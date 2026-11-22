#!/usr/bin/env python3
"""Reproduce the application-owned Tailcat integration; never edit the submodule."""
import argparse
import difflib
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "third_party/tailcat"
DEST = ROOT / "core-engine/tailcat-integration"


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise SystemExit("Tailcat integration anchor changed; review the upstream update")
    return text.replace(old, new, 1)


def outputs():
    files = {p.name: p.read_bytes() for p in BASE.glob("*.go")
             if not p.name.endswith("_test.go")}
    for name in ("go.mod", "go.sum", "LICENSE", "README.md"):
        files[name] = (BASE / name).read_bytes()
    for name in ("tailcat.go", "pickregion.go"):
        text = files[name].decode()
        text = replace_once(text, '\t"tailscale.com/net/netns"\n', "")
        text = replace_once(text, "\tnetns.SetEnabled(false)", "\tconfigureStandaloneNetNS()")
        if name == "tailcat.go":
            # A DisconnectClient that lands while allowClient is in flight for
            # the same key must win: record it, and refuse to add the client
            # once the hook returns.
            text = replace_once(text,
                "\tpendingAllow map[key.NodePublic]bool          // client keys with an allowClient call in flight\n",
                "\tpendingAllow map[key.NodePublic]bool          // client keys with an allowClient call in flight\n"
                "\tremovedAllow map[key.NodePublic]bool // pendingAllow keys disconnected while their allowClient call was in flight\n")
            text = replace_once(text, '''		delete(b.pendingAllow, src)
		if !allowed {''', '''		delete(b.pendingAllow, src)
		removed := b.removedAllow[src]
		delete(b.removedAllow, src)
		if removed {
			b.logf("ignoring meow from %v: disconnected while AllowClient ran", src.String())
			return false
		}
		if !allowed {''')
            text = replace_once(text, '''func (b *locoBackend) removeClientLocked(k key.NodePublic) bool {
	if _, ok := b.clients[k]; !ok {''', '''func (b *locoBackend) removeClientLocked(k key.NodePublic) bool {
	if b.pendingAllow[k] {
		mak.Set(&b.removedAllow, k, true)
	}
	if _, ok := b.clients[k]; !ok {''')
            # Every decoded DERP map, fresh or cached, passes the application's
            # endpoint policy, whatever URL or redirect it came from.
            text = replace_once(text, '''	dm := new(tailcfg.DERPMap)
	if err := json.Unmarshal(data, dm); err != nil {
		return nil
	}
	return dm
}''', '''	dm := new(tailcfg.DERPMap)
	if err := json.Unmarshal(data, dm); err != nil {
		return nil
	}
	if f := DERPMapFilter; f != nil {
		if err := f(dm); err != nil {
			return nil
		}
	}
	return dm
}

// DERPMapFilter, when set, vets and may prune every DERP map decoded from
// JSON (fetched or cached). An error discards the map. Set it once at
// program start, before any client or server runs.
var DERPMapFilter func(*tailcfg.DERPMap) error''')
            text = replace_once(text, "// nodeKeyLocked returns", '''// NetworkMonitor returns the monitor owned by this initialized client.
// This application-owned integration API deliberately uses ordinary typed
// access. Call after Ping initialized the client; absence is an explicit error.
func (c *Client) NetworkMonitor() (*netmon.Monitor, error) {
	if c == nil {
		return nil, errors.New("nil Tailcat client")
	}
	c.startMu.Lock()
	defer c.startMu.Unlock()
	if c.lb == nil {
		return nil, errors.New("tailcat client is not initialized")
	}
	mon, ok := c.lb.sys.NetMon.GetOK()
	if !ok || mon == nil {
		return nil, errors.New("tailcat network monitor is unavailable")
	}
	return mon, nil
}

// nodeKeyLocked returns''')
        files[name] = subprocess.check_output(["gofmt"], input=text.encode())
    files["opentailcat_android.go"] = b'''// Copyright (c) OpenTailcat contributors
// SPDX-License-Identifier: BSD-3-Clause

//go:build android

package tailcat

import "tailscale.com/net/netns"

// Android transport sockets must always receive the registered protect hook,
// including initial STUN, DERP and Magicsock sockets and all later redials.
func configureStandaloneNetNS() { netns.SetEnabled(true) }
'''
    files["opentailcat_other.go"] = b'''// Copyright (c) OpenTailcat contributors
// SPDX-License-Identifier: BSD-3-Clause

//go:build !android

package tailcat

import "tailscale.com/net/netns"

func configureStandaloneNetNS() { netns.SetEnabled(false) }
'''
    patch = []
    for name in ("tailcat.go", "pickregion.go", "opentailcat_android.go", "opentailcat_other.go"):
        old = (BASE / name).read_text() if (BASE / name).exists() else ""
        patch.extend(difflib.unified_diff(old.splitlines(True), files[name].decode().splitlines(True),
                                         fromfile="upstream/" + name, tofile="integration/" + name))
    return files, "".join(patch)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    files, patch = outputs()
    expected = {DEST / name: content for name, content in files.items()}
    expected[Path(__file__).with_name("tailcat.patch")] = patch.encode()
    if args.check:
        stale = [str(p.relative_to(ROOT)) for p, data in expected.items()
                 if not p.is_file() or p.read_bytes() != data]
        extras = [p.name for p in DEST.glob("*.go") if p.name not in files]
        if stale or extras:
            raise SystemExit("Tailcat integration differs from its source/patch: " + ", ".join(stale + extras))
        print("Tailcat integration matches the pinned source and explicit patch")
    else:
        DEST.mkdir(parents=True, exist_ok=True)
        for path, data in expected.items():
            path.write_bytes(data)


if __name__ == "__main__":
    main()
