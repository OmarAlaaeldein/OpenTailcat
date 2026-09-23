# Security policy

## Release status

OpenTailcat 1.3.7 in the current source tree is a development build. It has an
integrated Go Mobile Tailcat engine with Phase 0 fail-closed capability gates,
Phase 1 reproducible builds, Phase 2 official token validation, and Phase 3
tunneled UDP userspace netstack code. IPv4 test-routing capabilities are true so
a live token can Connect. `ipv6` is true (client dual-stack path; session
`ipv6Egress` depends on gateway WAN). The capability JSON also reports
`testRouting: true`, which marks that Phase 8 physical leak acceptance has not
passed; Kotlin surfaces this in Settings and does not gate Connect on it.
Physical-device leak acceptance and full release gates are pending; it must not
be relied on as a production privacy VPN.

### Implemented security controls

- The native Meow/Meowed gateway handshake runs during `prepare`. Kotlin will not
  call `prepare` or create a TUN while the IPv4 capability set is incomplete.
- The Android reflection boundary enforces an API v2 native capability contract.
  IPv4 default-route installation requires `dataPlane`, `wireGuard`,
  `magicsock`, `twoPhaseStart`, `ipv4`, `tcp`, `udp`, `dns`, `liveStats`, and
  `cancelSafeLifecycle`. `requireIpv6` exists but production Connect uses
  `requireIpv6 = false`, so `::/0` is installed while dual-stack egress still
  depends on the gateway. Unknown capability JSON fields fail closed.
  `testRouting` is an optional non-gating field that signals Phase 8 acceptance
  is still pending.
- Token validation in Android and Go strictly enforces official token structures,
  rejects legacy/synthetic disco keys, rejects duplicate CBOR keys, rejects
  surrounding and interior whitespace, and rejects
  expired tokens. Embedded DERP maps allow only region fields `i`/`c`/`m`/`N` and
  node fields `n`/`i`/`h`/`t`/`4`/`6`/`s`/`d`. Node `x` (`InsecureForTests`) is
  rejected so a pasted token cannot disable DERP TLS. Loopback, unspecified,
  link-local, and multicast `h`/`4`/`6` values are rejected. Pairing UI labels
  embedded maps as "Embedded DERP Map", not an official city name, and sets
  `FLAG_SECURE` while the token paste dialog is open.
- Tunneled UDP uses a single gVisor netstack proxy routing datagrams exclusively
  through Tailcat WireGuard/Magicsock via `Client.DialUDP` without direct OS UDP
  sockets in `core-engine`. IPv4 `udp` is test-enabled; Phase 8 leak capture is
  still pending.
- TCP-only gateways can `prepare`. The UDP capability probe uses a 5s bound at
  prepare and re-probes every 30s while latched, so a slow DERP path cannot
  permanently drop non-DNS UDP for the session. DNS port 53 is carried over TCP
  and other UDP is dropped on such gateways; the measured `tcpOnly` state is
  reported in telemetry and the UI. A compatible exit gateway was observed
  answering native UDP (DNS query via `Client.DialUDP`); TCP-only remains the
  fallback (`tcpOnly` session). Gateway UDP support is deployment-specific and
  still needs Phase 8 capture proof per gateway.
- Profiles and tokens are stored in encrypted preferences backed by Android
  Keystore. Android backup and device-to-device transfer are disabled.
- Transport sockets must be protected with `VpnService.protect` (netns re-enabled after Tailcat `SetEnabled(false)`). The builder no longer relies on app-UID exclusion alone.
- Cleartext traffic is disabled for the Android application.

### Remaining release blockers & pending gates

- **Audit H1–H7 code fixes** shipped in 1.3.5 source and the checked-in AAR
  (lockdown-after-warm-TUN, transport protect re-enable, dead-reader attach,
  DNS TCP stop bound, DiscoPing health honesty, FD lifecycle serialization,
  phase8 fail-closed). Host Go tests cover the native pieces. Always-on
  emulator/device run and Phase 8 dual capture remain before any production
  claim.
- **IPv4 flags are test-enabled, not Phase 8 accepted**: leak capture still pending.
- **IPv6 dual-stack egress**: Android installs `::/0` after pumps are live.
  Native proxies IPv6 TCP/UDP with a 250ms dial timeout; ICMPv6 echo is dropped;
  oversized IPv6 gets a local Packet Too Big. Live IPv6 internet depends on the
  gateway. Capability `ipv6` is true; without gateway IPv6 WAN, public IPv6 is
  fail-closed (RST/drop) so Happy Eyeballs can fall back to tunneled IPv4.
- **Test-routing marker**: capability JSON includes `testRouting: true` while
  Phase 8 physical leak acceptance has not passed. Settings surfaces this; it
  does not gate Connect.
- **Lifecycle / telemetry promotion**: two-phase start (host-only TUN, then
  default routes after pumps), sticky VPN service, TUN closed before native stop,
  plus live `DiscoPing` RTT. `twoPhaseStart`, `cancelSafeLifecycle`, and
  `liveStats` are test-enabled until Phase 8 evidence.
- **Live physical-device acceptance**: Uplink packet capture on multi-interface
  devices to verify zero direct destination leaks (Phase 8).
- **Production release signing**: Signing with a production keystore (Phase 8).

See [handoff.md](handoff.md) for the ordered continuation plan and test gates.
