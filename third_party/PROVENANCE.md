# Upstream Provenance: third_party/tailcat

`third_party/tailcat` is an unmodified git submodule of https://github.com/tailscale/tailcat (BSD 3-Clause). Do not commit patches inside the submodule. OpenTailcat compiles a generated local module in `core-engine/tailcat-integration` produced from this submodule plus `core-engine/integration/tailcat.patch` (see `core-engine/integration/README.md`).

- **Pinned commit:** `b9491c13c63925af4119aca70bd21b1691ff3d5b` (2026-11-23, `CHANGELOG.md: update for v0.8.0 release`), the target of the SSH-signed annotated tag `v0.8.0` (tag object `87a9943e4df8b44f60bbfeba39126311a793db05`, tagged 2026-11-23 05:37 UTC; its embedded SSH signer public key is identical to the one on `v0.7.0`, but this machine has no allowed-signers file, so the signature itself was not cryptographically verified here). It is 37 commits past the previous pin.
- **What this pin provides:**
  - `Client.DERPRegion()` (reports the live DERP region the client relays through)
  - Application-layer UDP (`Client.DialUDP`, `Server.OnUDPForward`) and gateway `--serve=exit-node` UDP forwarding
  - `Server.AllowClient`, `KeySet`, `Server.StartContext`, `Server.PeerKey`, and `Server.DisconnectClient` (used by `core-engine/exitnode` for "Share this phone")
  - Upstream Android support packages (`feature/androiddns`, `feature/androidbin`, linked via `android_linux.go`); v0.8.0 also fixes DNS on Android 9 and earlier
  - Fixes for hangs on `Client.Close`/`Server.Close` after many short connections (netstack TCP TIME-WAIT 60 s to 5 s, `startMu` no longer held while closing), `PickBestRegion` leaking two UDP sockets, crashes on `null` DERP map regions/nodes, and `Addr.Resolve` keeping STUN-only nodes
- **Dependency bump over previous pin (`e00cdde6`):** `tailscale.com` to `v1.105.0-pre.0.20261118173401-6a92f86aceb2`, `wireguard-go` to `v0.0.0-20261117232807-a1800e9c5305`, `gvisor.dev/gvisor` to `v0.0.0-20261117040555-0303b2605d5e`, `golang.org/x/{crypto,net,sys}`, and `gliderssh`. Upstream now requires Go 1.27.4 (already our pin). `core-engine/go.mod` tracks these versions.
- **New upstream behavior OpenTailcat does not use or reviews explicitly:**
  - `--hybrid` / `Server.Hybrid` (experimental ML-KEM-768 handshake). Hybrid addresses add a token field `y` and a 128-bit `q`; both token parsers reject them as unknown fields, so a hybrid gateway cannot be paired. Exit-node sessions never set `Hybrid`. Supporting it is a token-schema change (fixtures, both parsers, both test suites).
  - The tailcat package now links `tailscale.com/feature/derpwebsocket` (DERP over WebSocket when the HTTP upgrade is refused) and `tailscale.com/feature/useproxy` (DERP connections honor `HTTPS_PROXY` / `ALL_PROXY`). Android app processes do not normally set those variables. Token-embedded and fetched DERP maps are still vetted by `token.go` and `derp_filter.go`.
  - `newNetMon` falls back to a static network monitor when `netmon.New` fails. The integration patch keeps the previous fail-closed behavior for clients (see `core-engine/integration/README.md`); the exit-node server keeps upstream's fallback.

Previous pins: `e00cdde6ff787f801361ff85ed085775c775d4c5` (`v0.7.0-18-ge00cdde6f`, untagged), `52fbad323e9d352f1febb623e0975368e9a8cbdd` (`v0.7.0-17-g52fbad323`), signed `v0.7.0`, and `0c31395bfd1ae0c0ef2917c0ec20432466087417` (`v0.5.0-25-g0c31395bf`).
