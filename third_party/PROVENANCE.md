# Upstream Provenance: third_party/tailcat

`third_party/tailcat` is a git submodule of the official repository
https://github.com/tailscale/tailcat.

- **Pinned tag**: `v0.7.0` (2026-09-16), commit
  `15ab9e68bfc6534a61797d7af28cedd42b54a3a5`. The tag is SSH-signed by the
  upstream maintainer; GitHub reports the signature as verified.
- **Includes**: application-layer UDP (`Client.DialUDP`, `Server.OnUDPForward`),
  gateway-side UDP forwarding through `--serve=exit-node`, and upstream's
  Android support packages `feature/androiddns` and `feature/androidbin`
  (linked into the Android library by `android_linux.go`).
- **License**: BSD 3-Clause

The pin is unmodified upstream. Do not add OpenTailcat patches in this
submodule. Before v0.7.0 the pin was the unsigned `main` commit
`0c31395bfd1ae0c0ef2917c0ec20432466087417` (`v0.5.0-25-g0c31395bf`), an
ancestor of this tag.
