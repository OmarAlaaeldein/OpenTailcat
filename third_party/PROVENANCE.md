# Upstream Provenance: third_party/tailcat

OpenTailcat ships an unmodified copy of upstream Tailcat as a submodule. This
file records exactly which commit it is, why that commit, and what it contains,
so a re-pin can be reviewed without diffing the submodule by hand.

`third_party/tailcat` is a git submodule of the official repository
https://github.com/tailscale/tailcat.

- **Pinned commit**: `e00cdde6ff787f801361ff85ed085775c775d4c5`
  (2026-11-05), `v0.7.0-18-ge00cdde6f`, on `main` and **not yet tagged or
  signed** upstream. It is 18 commits after the signed `v0.7.0` tag
  (`15ab9e68bfc6534a61797d7af28cedd42b54a3a5`, 2026-09-16). Upstream's
  CHANGELOG marks this range "Unreleased".
- **Why not a signed tag**: upstream has published no release after `v0.7.0`.
  This pin carries `Client.DERPRegion()`, which reports the DERP region the
  running client actually relays through, and the dependency graph that goes
  with it. Re-pin to the next signed tag as soon as one exists; do not treat
  this commit as a release.
- **Change since `52fbad32`**: a dependency-only bump in the submodule's own
  `go.mod` (no Tailcat API change): `tailscale.com` from a `v1.103.0` pre-release
  to `v1.105.0-pre.0.20261013225543-fb79793d99f5`, which carries Tailscale's
  netstack shutdown fix; `wireguard-go` to `v0.0.0-20260928213353-e61b6b78110b`;
  `golang.org/x/net` 0.60.0; and the submodule's own `go` directive to 1.27.2.
  OpenTailcat's `core-engine/go.mod` moves with it; `gvisor.dev/gvisor` stays at
  the commit both Tailscale and Tailcat pin (`20260915211658`) because newer
  upstream commits fail to build.
- **Includes**: application-layer UDP (`Client.DialUDP`, `Server.OnUDPForward`),
  gateway-side UDP forwarding through `--serve=exit-node`, upstream's Android
  support packages `feature/androiddns` and `feature/androidbin` (linked into
  the Android library by `android_linux.go`), and the client DERP region
  accessor.
- **Upstream API change carried by this pin**: `Server.AllowedClients` became
  the `AllowClient` hook plus `KeySet`, and `Server` gained `StartContext`,
  `PeerKey`, and `DisconnectClient`. OpenTailcat is a client and does not use
  the server surface, so nothing in `core-engine` depends on those changes.
- **License**: BSD 3-Clause

The pin is unmodified upstream. Do not add OpenTailcat patches in this
submodule. Pin history: the unsigned `main` commit
`52fbad323e9d352f1febb623e0975368e9a8cbdd` (`v0.7.0-17-g52fbad323`) before
this one, the signed `v0.7.0` tag before that, and the unsigned `main` commit
`0c31395bfd1ae0c0ef2917c0ec20432466087417` (`v0.5.0-25-g0c31395bf`) before
that.
