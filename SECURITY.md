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
- Share this phone (exit node): the code is a bearer credential, so a new device is refused until the host approves its key; open admission is an explicit, always time-limited mode, and a share without a time limit requires approval (enforced in Go and Kotlin). Every session uses fresh keys discarded at stop, so stopping revokes every code, and the node enforces its own expiry rather than trusting the client's.
- Guests cannot reach the host's own network: loopback, private, CGNAT, link-local, multicast and reserved destinations are refused (`core-engine/exitnode/policy.go`); the test-only override is not reachable from the bridge.
- Sharing and the client VPN never run together (the node's dials would loop through the tunnel); refused natively, in the Android start paths, and by a backstop poll. Forwarding sockets exist only in `core-engine/exitnode/dial.go`, enforced by a test, so the engine package keeps its no-OS-socket rule.
- The sharing code is shown only in a `FLAG_SECURE` window, is never logged, and reaches the clipboard only through Copy. Known gap: the host cannot verify who is asking to connect; see `exit.md`.
