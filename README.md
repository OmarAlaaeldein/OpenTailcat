# OpenTailcat

Android client for Tailcat. Paste a `tc…` token and connect to **your** gateway — no control plane in the middle.

> Independent community project. Not affiliated with Tailscale Inc.

## Screenshots

<p align="center">
  <img src="docs/screenshots/home-connected.png" alt="Connected home" width="280" />
  &nbsp;
  <img src="docs/screenshots/network-benchmark.png" alt="Network benchmark" width="280" />
</p>

## What’s new in 1.5.3

- **Engine security update**: the native engine is built with Go 1.27.2 and
  `golang.org/x/net` v0.60.0, fixing GO-2026-6611, GO-2026-6612, GO-2026-6613
  and GO-2026-6617 in the HTTP/2 and `net/http` code.
- **Emulator/CI reliability**: the emulator suites pre-grant notifications and
  wait for a readable UI dump before tapping Connect, and CI uses the
  flavor-explicit Gradle tasks.

Full notes: [`docs/releases/1.5.3.md`](docs/releases/1.5.3.md). Older
releases: [`docs/releases/`](docs/releases/).

## Install

1. Grab the [latest release](https://github.com/OmarAlaaeldein/OpenTailcat/releases/latest).
2. APKs:
   - **Phone:** `OpenTailcat-1.5.3-arm64-v8a.apk`
   - **Emulator (x86_64):** `OpenTailcat-1.5.3-x86_64.apk`
3. Install it (allow installs from your browser/file manager if asked).
4. Optional: enable **Always-on VPN** and **Block connections without VPN** for OpenTailcat — better if the tunnel drops. Connect still works without them.
5. Paste your `tc…` token and tap Connect.

Checksums are in `OpenTailcat-1.5.3-SHA256SUMS.txt`.

## How it works

You run a Tailcat-compatible exit gateway. It gives you a short token. OpenTailcat uses that token to tunnel to your gateway.

## Notes

- Builds are development-signed unless you set `OPENTAILCAT_RELEASE_*`. Uninstall an older development install if Android blocks the upgrade.
- More detail: [`docs/releases/`](docs/releases/), [`AGENTS.md`](AGENTS.md), [`handoff.md`](handoff.md).

## License

Apache License 2.0 — [LICENSE](LICENSE), [PRIVACY_POLICY.md](PRIVACY_POLICY.md).
