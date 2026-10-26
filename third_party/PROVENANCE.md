# Upstream Provenance: third_party/tailcat

`third_party/tailcat` is a git submodule of the official repository
https://github.com/tailscale/tailcat.

- **Pinned commit**: `52fbad323e9d352f1febb623e0975368e9a8cbdd`
  (2026-10-07), `v0.7.0-17-g52fbad323`, on `main` and **not yet tagged or
  signed** upstream. It is 17 commits after the signed `v0.7.0` tag
  (`15ab9e68bfc6534a61797d7af28cedd42b54a3a5`, 2026-09-16). Upstream's
  CHANGELOG marks this range "Unreleased".
- **Why not a signed tag**: upstream has published no release after `v0.7.0`.
  This pin carries `Client.DERPRegion()`, which reports the DERP region the
  running client actually relays through, and the dependency graph that goes
  with it. Re-pin to the next signed tag as soon as one exists; do not treat
  this commit as a release.
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
submodule. Pin history: the signed `v0.7.0` tag before this commit, and the
unsigned `main` commit `0c31395bfd1ae0c0ef2917c0ec20432466087417`
(`v0.5.0-25-g0c31395bf`) before that.
