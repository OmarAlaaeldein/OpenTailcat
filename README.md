# OpenTailcat

Android client for Tailcat. Paste a `tc…` token and connect to **your** gateway —
no control plane in the middle.

> Independent community project. Not affiliated with Tailscale Inc.

## Status

Development preview, version 1.5.6. The data plane runs, but physical-device
leak capture and production signing are still open. Do not rely on it for
privacy-sensitive traffic. Reconnection removes the TUN between attempts; turn
on Android's **Always-on VPN** with **Block connections without VPN** if that
matters to you.

## Screenshots

<p align="center">
  <img src="docs/screenshots/home-connected.png" alt="Connected home" width="280" />
  &nbsp;
  <img src="docs/screenshots/network-benchmark.png" alt="Network benchmark" width="280" />
</p>

## Install

1. Download the latest release from the repository's **Releases** page.
2. APKs are split by ABI:
   - **Phone:** `OpenTailcat-<version>-arm64-v8a.apk`
   - **Emulator (x86_64):** `OpenTailcat-<version>-x86_64.apk`
3. Allow installs from your browser or file manager when asked.
4. Optional: enable **Always-on VPN** and **Block connections without VPN**.
5. Paste your `tc…` token and tap Connect.

Checksums ship as `OpenTailcat-<version>-SHA256SUMS.txt` beside each build.

## How it works

You run a Tailcat-compatible exit gateway, which gives you a short token.
OpenTailcat uses that token to establish a WireGuard tunnel to the gateway and
carries IPv4/IPv6 TCP, UDP, and DNS through it. Transport sockets are protected
from the tunnel; everything else goes through it.

## Notes

- Builds are development-signed unless `OPENTAILCAT_RELEASE_*` is set. Android
  may refuse to upgrade an older development install; uninstall it first.
- Release notes: [`docs/releases/`](docs/releases/). Build, test, and design
  details: [`AGENTS.md`](AGENTS.md). Signing: [`docs/signing.md`](docs/signing.md).

## License

Apache License 2.0 — [LICENSE](LICENSE), [PRIVACY_POLICY.md](PRIVACY_POLICY.md).
