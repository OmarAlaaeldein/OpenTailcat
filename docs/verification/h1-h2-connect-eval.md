# H1/H2 Connect evaluation — 2026-09-06

## Fix summary

- **H1**: `LockdownProbe` reads Settings.Secure `always_on_vpn_app` /
  `always_on_vpn_lockdown`. `TailcatVpnService` fails fast when Settings
  explicitly deny lockdown, and after warm TUN uses
  `LeakGuard.refusalReasonForStartup(settings OR framework isLockdownEnabled)`.
  Settings.Secure=true wins when the framework query is still false.
- **H2**: Rebuilt `libtailcat.aar` exports `ensureTransportProtect` so Kotlin can
  re-enable Tailscale `netns` protect after Tailcat `createEngine` disables it.

## Verified here

| Check | Result |
| --- | --- |
| Unit: LockdownProbeTest (8) + LeakGuardTest | Pass |
| `go test` protect / EnsureTransportProtect | Pass |
| `assembleDebug` (x86_64 + arm64) | Pass |
| AAR Java API has `ensureTransportProtect` | Pass (javap) |
| Emulator Always-on + live token Connect | **Not completed** — AVD stayed `adb offline` / near-idle CPU after KVM start in this box |

## Emulator blocker

API 30 x86_64 AVD starts under KVM (`-accel on` via `sg kvm`) but remains
`offline` with ~0% guest CPU. `emulator -accel-check` reports KVM usable;
ProbeKVM still mis-reports group membership without `sg kvm`. Cold wipe did not
recover adb. Physical device / working emulator still required for live Connect.

## Omar checklist

1. Install latest debug/development APK built from this tree (includes new AAR).
2. Enable Always-on VPN + Block connections without VPN for OpenTailcat.
3. Connect with live token; confirm lastError is not LOCKDOWN_REQUIRED.
4. If lockdown passes but tunnel fails, capture logcat for prepare/protect.
5. Phase 8 dual capture still required before any leak-free claim. `ipv6` stays false.
