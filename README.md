# OpenTailcat

A simple Android app that connects you to **your own Tailcat gateway** with a short `tc…` token. No control plane in the middle — just your phone and your gateway.

> Independent community project. Not affiliated with, sponsored by, or endorsed by Tailscale Inc.

## What’s new in 1.3.0

- Cleaner home and settings copy (no development-test banners in the UI)
- Emulator dual-capture uplink check passed (Phase 8 analyze)
- Connect verified on a physical phone
- Live traffic rates in the status notification

## Install

1. Open the [latest release](https://github.com/OmarAlaaeldein/OpenTailcat/releases/latest).
2. Download the APK for your device:
   - **Phone / most devices:** `OpenTailcat-1.3.0-arm64-v8a.apk`
   - **Emulator (x86_64):** `OpenTailcat-1.3.0-x86_64.apk`
3. Install the APK (you may need to allow installs from your browser/file manager).
4. In Android **VPN settings**, turn on **Always-on VPN** and **Block connections without VPN** for OpenTailcat (required on Android 10+).
5. Paste your gateway `tc…` token and tap Connect.

Optional: check the SHA-256 sums in `OpenTailcat-1.3.0-SHA256SUMS.txt` against the downloaded APK.

## How it works (short)

1. You run a Tailcat-compatible exit gateway you control.
2. The gateway gives you a compact token.
3. OpenTailcat uses that token to build a private tunnel to your gateway.

## Notes

- **1.3.0** is signed with the development keystore unless release signing keys are configured. Uninstall any older development build before installing if Android refuses an upgrade.
- Always-on + block-without-VPN is required for default routes on modern Android.
- Deep technical detail lives in [`docs/releases/`](docs/releases/), [`AGENTS.md`](AGENTS.md), and [`handoff.md`](handoff.md).

## License

Apache License 2.0. See [LICENSE](LICENSE) and [PRIVACY_POLICY.md](PRIVACY_POLICY.md).
