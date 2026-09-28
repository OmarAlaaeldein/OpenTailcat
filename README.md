# OpenTailcat

Android client for Tailcat. Paste a `tc…` token and connect to **your** gateway — no control plane in the middle.

> Independent community project. Not affiliated with Tailscale Inc.

## Screenshots

<p align="center">
  <img src="docs/screenshots/home-connected.png" alt="Connected home" width="280" />
  &nbsp;
  <img src="docs/screenshots/network-benchmark.png" alt="Network benchmark" width="280" />
</p>

## What’s new in 1.5.0

- **Speed test through the gateway** shows live progress instead of sitting at 0.
- **Reconnects** after an engine failure or a gateway that stops answering,
  and follows Wi-Fi ↔ mobile data switches.
- **Honest status** (“GATEWAY NOT RESPONDING”), no local ping replies, and no
  public IP or peer addresses in logcat unless Diagnostics is on.
- **Safer storage and updates**: saved gateways are sealed with an Android
  Keystore key (a lost key no longer crashes the app), deleted gateways stay
  deleted, and Settings → Updates verifies the APK’s SHA-256 and signer.
- Tailcat `v0.7.0`, bundled license notices (Settings → About), and many
  data-plane fixes from the 2026-09-26 review.

Full notes: [`docs/releases/1.5.0.md`](docs/releases/1.5.0.md). Older
releases: [`docs/releases/`](docs/releases/).

## Install

1. Grab the [latest release](https://github.com/OmarAlaaeldein/OpenTailcat/releases/latest).
2. APKs:
   - **Phone:** `OpenTailcat-1.5.0-arm64-v8a.apk`
   - **Emulator (x86_64):** `OpenTailcat-1.5.0-x86_64.apk`
3. Install it (allow installs from your browser/file manager if asked).
4. Optional: enable **Always-on VPN** and **Block connections without VPN** for OpenTailcat — better if the tunnel drops. Connect still works without them.
5. Paste your `tc…` token and tap Connect.

Checksums are in `OpenTailcat-1.5.0-SHA256SUMS.txt`.

## How it works

You run a Tailcat-compatible exit gateway. It gives you a short token. OpenTailcat uses that token to tunnel to your gateway.

## Notes

- Builds are development-signed unless you set `OPENTAILCAT_RELEASE_*`. Uninstall an older development install if Android blocks the upgrade.
- More detail: [`docs/releases/`](docs/releases/), [`AGENTS.md`](AGENTS.md), [`handoff.md`](handoff.md).

## License

Apache License 2.0 — [LICENSE](LICENSE), [PRIVACY_POLICY.md](PRIVACY_POLICY.md).
