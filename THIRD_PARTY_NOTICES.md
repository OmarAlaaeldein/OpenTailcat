# Third-party notices

This file is an overview, not a substitute for the license files distributed with resolved dependencies. Generate and review a complete dependency/license report before every production release.

## Android application dependencies

- AndroidX Core, Activity, Lifecycle, Compose UI, Material, test libraries, and AndroidX Security Crypto — Apache License 2.0.
- Kotlin and kotlinx.coroutines — Apache License 2.0.
- `co.nstant.in:cbor` — Apache License 2.0.
- JUnit 4 — Eclipse Public License 1.0.

Exact versions are defined in `gradle/libs.versions.toml` and the resolved Gradle dependency graph.

## Go module declarations

`core-engine/go.mod` currently declares:

- `tailscale.com` — BSD 3-Clause components; review each linked package and its notices when a real engine is implemented.
- `golang.org/x/mobile` — BSD 3-Clause.

The current Go source is an integration scaffold and does not import or link a WireGuard/Magicsock data plane. Do not claim those components are present in an APK unless the resolved native artifact actually contains them.

## External services

Cloudflare and ipify are network services, not bundled libraries. Their use is disclosed in `PRIVACY_POLICY.md`.

## Trademarks

WireGuard, Tailscale, Android, Kotlin, Cloudflare, and other names belong to their respective owners. Their appearance describes compatibility or dependencies and does not imply endorsement.
