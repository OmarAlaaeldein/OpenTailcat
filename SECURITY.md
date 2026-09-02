# Security policy

## Release status

OpenTailcat 1.1.1 in the current source tree is a development build. It has an
integrated Go Mobile Tailcat engine with Phase 0 fail-closed capability gates,
Phase 1 reproducible builds, Phase 2 official token validation, and Phase 3
tunneled UDP userspace netstack data plane. However, physical-device live acceptance
and full release gates are pending; it must not be distributed or relied on as a
production privacy VPN.

### Implemented security controls

- The native Meow/Meowed gateway handshake runs before Android creates the TUN
  and default IPv4 route.
- The Android reflection boundary enforces an API v2 native capability contract
  requiring explicit, proven capabilities (`dataPlane`, `ipv4`, `ipv6`, `tcp`,
  `udp`, `dns`, `liveStats`, `cancelSafeLifecycle`). Incomplete engines fail
  closed immediately.
- Token validation in Android and Go strictly enforces official token structures,
  rejects legacy/synthetic disco keys, rejects duplicate CBOR keys, and rejects
  expired tokens.
- Tunneled UDP uses a single gVisor netstack proxy routing datagrams exclusively
  through Tailcat WireGuard/Magicsock via `Client.DialUDP` without direct OS UDP sockets.
- The native engine checks gateway `CapExitUDP` during `prepare` and fails before
  TUN attachment if connected to a legacy/TCP-only gateway.
- Profiles and tokens are stored in encrypted preferences backed by Android
  Keystore. Android backup and device-to-device transfer are disabled.
- The app UID bypasses the VPN to prevent Magicsock/DERP transport recursion.
- Cleartext traffic is disabled for the Android application.

### Remaining release blockers & pending gates

- **Live physical-device acceptance**: Uplink packet capture on multi-interface
  devices to verify zero direct destination leaks during network handover across TCP, UDP, and DNS.
- **IPv6 dual-stack route**: IPv6 is not yet assigned an Android VPN address or `::/0` (Phase 5).
- **Cancellable lifecycle state machine**: Synchronized state machine with real readiness and pump-failure propagation (Phase 6).
- **Production release signing**: Signing with production keystore (Phase 8).

See [handoff.md](handoff.md) for the ordered continuation plan and test gates.
