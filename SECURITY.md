# Security policy

## Release status

OpenTailcat 1.1.9 in the current source tree is a development build. It has an
integrated Go Mobile Tailcat engine with Phase 0 fail-closed capability gates,
Phase 1 reproducible builds, Phase 2 official token validation, and Phase 3
tunneled UDP userspace netstack code. IPv4 test-routing capabilities are true so
a live token can Connect. `ipv6` is false. Physical-device leak acceptance and
full release gates are pending; it must not be relied on as a production
privacy VPN.

### Implemented security controls

- The native Meow/Meowed gateway handshake runs during `prepare`. Kotlin will not
  call `prepare` or create a TUN while the IPv4 capability set is incomplete.
- The Android reflection boundary enforces an API v2 native capability contract.
  IPv4 default-route installation requires `dataPlane`, `wireGuard`,
  `magicsock`, `twoPhaseStart`, `ipv4`, `tcp`, `udp`, `dns`, `liveStats`, and
  `cancelSafeLifecycle`. `requireIpv6` exists but production Connect uses
  `requireIpv6 = false`, so `::/0` is installed while `ipv6` stays false.
  Unknown capability JSON fields fail closed.
- Token validation in Android and Go strictly enforces official token structures,
  rejects legacy/synthetic disco keys, rejects duplicate CBOR keys, and rejects
  expired tokens. Embedded DERP maps allow only region fields `i`/`c`/`m`/`N` and
  node fields `n`/`i`/`h`/`t`/`4`/`6`/`s`/`d`. Node `x` (`InsecureForTests`) is
  rejected so a pasted token cannot disable DERP TLS. Pairing UI labels embedded
  maps as "Embedded DERP Map", not an official city name.
- Tunneled UDP uses a single gVisor netstack proxy routing datagrams exclusively
  through Tailcat WireGuard/Magicsock via `Client.DialUDP` without direct OS UDP
  sockets in `core-engine`. IPv4 `udp` is test-enabled; Phase 8 leak capture is
  still pending.
- TCP-only gateways (including official v0.4.0 `nullexit`) can `prepare`. DNS
  port 53 is carried over TCP. Other UDP is dropped. This is not a full UDP VPN.
- Profiles and tokens are stored in encrypted preferences backed by Android
  Keystore. Android backup and device-to-device transfer are disabled.
- The app UID bypasses the VPN to prevent Magicsock/DERP transport recursion.
- Cleartext traffic is disabled for the Android application.

### Remaining release blockers & pending gates

- **IPv4 flags are test-enabled, not Phase 8 accepted**: leak capture still pending.
- **IPv6 dual-stack egress**: After `prepare`, Android installs `::/0` on the
  same TUN as IPv4 default routes, then `attachTun`. Native proxies IPv6 TCP/UDP
  with a 250ms dial timeout; ICMPv6 echo is dropped; oversized IPv6 gets a local
  Packet Too Big. Live IPv6 internet depends on the gateway. `ipv6` stays false.
- **Lifecycle / telemetry promotion**: one routed TUN after `prepare`, sticky VPN
  service, TUN closed before native stop, plus live `DiscoPing` RTT. `twoPhaseStart`,
  `cancelSafeLifecycle`, and `liveStats` are test-enabled until Phase 8 evidence.
- **Live physical-device acceptance**: Uplink packet capture on multi-interface
  devices to verify zero direct destination leaks (Phase 8).
- **Production release signing**: Signing with a production keystore (Phase 8).

See [handoff.md](handoff.md) for the ordered continuation plan and test gates.
