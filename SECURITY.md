# Security policy

## Reporting a vulnerability

Report privately through the repository's **Security** tab →
**Report a vulnerability**. Do not open a public issue. Never include a live
gateway token, signing key, or traffic capture; describe how to reproduce
instead.

Build status and known limitations: see the
[README](README.md#project-status). Design and safety invariants are in
[AGENTS.md](AGENTS.md).

## Security controls

- `TunnelEngine` requires API v2 and the full capability set before creating a TUN; unknown fields fail closed.
- Host-only TUN first, pumps proven, then `0.0.0.0/0` + `::/0`. `CONNECTED` requires native `RUNNING` plus fresh `healthUnixSec`.
- One strict token contract in Kotlin and Go (no synthetic disco keys, duplicate keys, padding, expired or legacy tokens); embedded DERP maps reject test and non-routable addresses. Shared fixture corpus.
- TCP/UDP proxied through Tailcat WireGuard/Magicsock; `core-engine` opens no direct destination sockets. Transport sockets protected with `VpnService.protect`.
- Profiles/tokens in AES-256 Keystore prefs; backup and device transfer disabled.
- GitHub flavor verifies update digest, restricts hosts, pins signing certificates with lineage checks. F-Droid has no updater.
- Cleartext disabled; release builds R8-shrunk.
