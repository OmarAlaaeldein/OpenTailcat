# Security Policy

## Release status

Tailcat 1.0.0 is an Android client with an integrated Go Mobile WireGuard/Magicsock engine adapter (`libtailcat.aar`) built around pinned `tailscale/tailcat` v0.4.0. The native engine implements two-phase startup, raw TUN packet pumping, gVisor userland TCP proxying, UDP forwarding with checksum verification, ICMP echo handling, and TLS tunnel egress telemetry.

## Security controls

- **Strict Token Parsing**: Connection tokens require valid CBOR format, exact 32-byte public keys, valid DERP regions, consistent timestamps, and URL-safe Base64 encoding.
- **Expiry Enforcement**: Expired tokens cannot be saved or used to start a tunnel.
- **Encrypted Local Storage**: Profiles and tokens are stored in encrypted preferences backed by Android Keystore.
- **Fail-Closed VPN Lifecycle**: Full-device routes (`0.0.0.0/0`, `::/0`) are only installed after the native engine completes an authenticated Meow `Ping` reachability handshake.
- **Socket Protection & Loop Prevention**: The Android app UID is excluded from VPN routing so native transport sockets bypass the TUN directly without recursive loops.
- **Immediate Teardown**: Startup errors close the TUN immediately; consecutive telemetry failures initiate graceful teardown.
- **Truthful Telemetry**: Real measured bytes, latency, and rates are reported; synthetic measurements are forbidden.
- **Secure Defaults**: Cleartext HTTP is disabled, Android data backup is excluded, and release APKs are not signed with debug keys.

## Remaining pre-distribution gates

- End-to-end live testing against a running exit node across cellular and Wi-Fi networks.
- TCP MSS clamping optimization in the packet pipeline.
- Production APK signing with a private release key.

## Reporting a vulnerability

Do not publish secrets, working exploits, gateway tokens, or personal traffic in a public issue. Use the repository owner's private security-reporting channel if one is configured. If no private channel is available, open a minimal non-sensitive issue asking the maintainer to establish private contact; do not include exploit details in that issue.

A useful report includes the affected commit/version, Android version and device, reproduction conditions, expected and observed behavior, and the least-sensitive proof of concept possible.
