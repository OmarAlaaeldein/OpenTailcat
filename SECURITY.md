# Security Policy

## Release status

Tailcat 1.0.0 in this repository is a pre-production Android client scaffold. It is not a supported VPN release because the WireGuard/Magicsock data plane is absent. No version should be represented as providing encrypted tunneling until the integration and release gates in [handoff.md](handoff.md) pass.

## Current controls

- Connection tokens require a single CBOR map, an exact 32-byte public key, a positive DERP region, consistent timestamps, and valid Base64URL encoding.
- Expired tokens cannot be saved or used to start a tunnel.
- Profiles and tokens are stored in encrypted preferences backed by Android Keystore; legacy plaintext preferences are migrated and removed.
- Android backups and device-to-device transfer are disabled for app data.
- Cleartext HTTP is disabled.
- The VPN service requires Android's `BIND_VPN_SERVICE` permission.
- The app checks an explicit native capability contract before requesting VPN consent.
- A TUN is marked connected only after native startup succeeds. Startup errors close it immediately; repeated telemetry errors stop it.
- Speed tests report network failures rather than generated measurements.
- Production release builds are not signed with the debug key.

## Not yet provided

- WireGuard cryptography, packet pumping, Magicsock, STUN, or DERP.
- A gateway identity handshake.
- DNS or IPv6 leak protection verified against a working engine.
- TCP MSS clamping.
- App-controlled kill-switch behavior. Lockdown is configured through Android's Always-on VPN settings.
- Native-engine security tests, fuzzing, or independent review.

## Reporting a vulnerability

Do not publish secrets, working exploits, gateway tokens, or personal traffic in a public issue. Use the repository owner's private security-reporting channel if one is configured. If no private channel is available, open a minimal non-sensitive issue asking the maintainer to establish private contact; do not include exploit details in that issue.

A useful report includes the affected commit/version, Android version and device, reproduction conditions, expected and observed behavior, and the least-sensitive proof of concept possible. No response-time guarantee is currently offered for this pre-production project.
