# Security Policy

## Reporting a vulnerability

Use GitHub's **Security** tab -> **Report a vulnerability** to report issues privately instead of opening a public issue. Do not include live gateway tokens, signing keys, or raw traffic captures; reproduction steps are enough.

For current development-preview status and open limitations, see [README.md](README.md#project-status) and [AGENTS.md](AGENTS.md).

## Implemented controls

- **Fail-closed engine checks:** `TunnelEngine` requires capability API v2 with all required dual-stack data-plane flags enabled, and rejects unknown fields.
- **Two-phase VPN start:** Android establishes a host-only TUN first, confirms native packet pumps are running, and only then installs `0.0.0.0/0` + `::/0`. The UI reports `CONNECTED` only when native state is `RUNNING` with a fresh heartbeat.
- **Strict token parsing:** Go and Kotlin share one strict CBOR/Base64URL token parser (rejecting padding, whitespace, duplicate/unknown keys, expired or legacy tokens, synthetic disco keys, and unsafe embedded DERP endpoints), verified against shared fixtures and a differential mutation corpus.
- **No direct app sockets in the client engine:** `core-engine` proxies tunneled TCP/UDP through Tailcat WireGuard/Magicsock (`no_os_socket_test.go` enforces this). Only native transport sockets receive creation-time `VpnService.protect`.
- **Encrypted local storage:** Profiles and tokens use AES-256-GCM preferences backed by Android Keystore with no plaintext fallback. Backups and device-to-device transfer are disabled, cleartext HTTP is disabled, and release/development builds are R8-minified.
- **Pinned HTTPS & verified updates:** Cloudflare audit/speed-test endpoints use SPKI certificate pinning. In the `github` flavor, the self-updater restricts download hosts to GitHub, checks APK SHA-256 digests, and verifies signing certificate pins (including v3 rotation lineage). The `fdroid` flavor omits the updater.
- **Country destination filter:** optional client-side refusal of destinations in selected countries' registry ranges. The bundled range asset is validated by Go and Kotlin tests; when a filter is selected, a missing or malformed asset, a rejected list, or a DNS resolver inside the list aborts the start instead of connecting unfiltered. The filter is best effort because registry data reflects allocation, not physical server location.
- **Exit-node isolation ("Share this phone"):**
  - Mutually exclusive with the client VPN (enforced in Go under `globalCore.mu`, in `TunnelController`, and via a poll check).
  - Outbound OS sockets live only in `core-engine/exitnode/dial.go` (`TestOSSocketsConfinedToDial`).
  - Default admission requires per-device host approval; open admission always has a time limit (max 24 h), and no-limit shares require approval. Session keys are ephemeral and discarded on stop.
  - `exitnode/policy.go` blocks loopback, RFC 1918, CGNAT, link-local, multicast, reserved, 4-in-6, and standard/RFC 8215 NAT64-mapped private addresses.
  - Sharing screens use `FLAG_SECURE`, codes are never logged, and clipboard writes happen only on explicit Copy. Known limitation: guests don't display a matching device ID yet, so the host cannot verify identity beyond watching who connects (see [AGENTS.md](AGENTS.md#exit-node)).
