# Upstream Provenance: third_party/tailcat

`third_party/tailcat` is an unmodified git submodule of https://github.com/tailscale/tailcat (BSD 3-Clause). Do not commit patches inside the submodule. OpenTailcat compiles a generated local module in `core-engine/tailcat-integration` produced from this submodule plus `core-engine/integration/tailcat.patch` (see `core-engine/integration/README.md`).

- **Pinned commit:** `e00cdde6ff787f801361ff85ed085775c775d4c5` (2026-11-05, `v0.7.0-18-ge00cdde6f`), an untagged/unsigned commit on `main`, 18 commits past signed tag `v0.7.0` (`15ab9e68bfc6534a61797d7af28cedd42b54a3a5`, 2026-09-16). Re-pin to the next signed upstream tag once published.
- **What this pin provides:**
  - `Client.DERPRegion()` (reports the live DERP region the client relays through)
  - Application-layer UDP (`Client.DialUDP`, `Server.OnUDPForward`) and gateway `--serve=exit-node` UDP forwarding
  - `Server.AllowClient`, `KeySet`, `Server.StartContext`, `Server.PeerKey`, and `Server.DisconnectClient` (used by `core-engine/exitnode` for "Share this phone")
  - Upstream Android support packages (`feature/androiddns`, `feature/androidbin`, linked via `android_linux.go`)
- **Dependency bump over previous pin (`52fbad32`):** updates `tailscale.com` to `v1.105.0-pre.0.20261013225543-fb79793d99f5` (includes Tailscale's netstack shutdown fix), `wireguard-go` to `v0.0.0-20260928213353-e61b6b78110b`, and `golang.org/x/net`. `core-engine/go.mod` tracks these versions, while `gvisor.dev/gvisor` remains on `20260915211658` (matched by both Tailscale and Tailcat).

Previous pins: `52fbad323e9d352f1febb623e0975368e9a8cbdd` (`v0.7.0-17-g52fbad323`), signed `v0.7.0`, and `0c31395bfd1ae0c0ef2917c0ec20432466087417` (`v0.5.0-25-g0c31395bf`).
