# Security policy

## Reporting a vulnerability

Report privately through the repository's **Security** tab →
**Report a vulnerability**. Do not open a public issue. Never include a live
gateway token, signing key, or traffic capture; describe how to reproduce
instead.

## Build status

OpenTailcat 1.5.6 is a development build of a dual-stack test-routing client.
It can connect with a live token, but physical-device leak acceptance and
production signing are still outstanding. Do not treat it as a production
privacy VPN.

`testRouting: true` in the capability payload marks that state. Settings
surfaces it; it does not block Connect.

## Security controls

- `TunnelEngine` requires API v2 and the full capability set before creating a
  TUN or installing a default route. Unknown fields and missing capabilities
  fail closed.
- The service builds a host-only TUN, attaches pumps, then installs
  `0.0.0.0/0` and `::/0`. `CONNECTED` requires native `RUNNING` plus fresh
  `healthUnixSec`.
- Kotlin and Go share one strict token contract: no synthetic disco keys, no
  duplicate CBOR keys, no padding or whitespace, no expired or legacy tokens.
  Embedded DERP maps reject test-only and non-routable addresses. Both sides
  consume the same fixture corpus.
- TCP and UDP are proxied through Tailcat WireGuard/Magicsock; `core-engine`
  opens no direct destination sockets. Transport sockets are protected with
  `VpnService.protect`.
- Profiles and tokens use AES-256 preferences backed by Android Keystore.
  Android backup and device-to-device transfer are disabled.
- The GitHub flavor verifies the update asset digest, restricts download hosts,
  and pins accepted signing certificates with lineage checks. The F-Droid
  flavor has no updater.
- Cleartext traffic is disabled and release builds are R8-shrunk.

## Known limitations

- Physical uplink and gateway captures have not been run, so the "no direct
  destination traffic" property is unproven on a device.
- Between reconnect attempts there is no TUN. Use Always-on VPN with Block
  connections without VPN to close that gap.
- WireGuard peer byte counters are not exposed by the upstream client, so
  telemetry reports TUN counters instead.
- The packaged AAR is built from an upstream Tailcat commit that is not a
  signed tag; see `third_party/PROVENANCE.md`.
- Released APKs are development-signed unless a release key is supplied.
- Gateways without IPv6 WAN make the client fail public IPv6 closed rather
  than bypass the tunnel.

See [AGENTS.md](AGENTS.md) for the test commands and the current work list.
