# Third-Party Notices

**OpenTailcat**  
Copyright (c) 2026 Omar Alaaeldein. All rights reserved.

This file is an overview, not a substitute for the license files distributed with resolved dependencies. Generate and review a complete dependency/license report before every production release.

## Android application dependencies

- AndroidX Core, Activity, Lifecycle, Compose UI, Material, test libraries, and AndroidX Security Crypto — Apache License 2.0.
- Kotlin and kotlinx.coroutines — Apache License 2.0.
- JUnit 4 — Eclipse Public License 1.0.

Exact versions are defined in `gradle/libs.versions.toml` and the resolved Gradle dependency graph.

## Native engine & Go module dependencies

The native development engine (`core-engine` and `libtailcat.aar`) incorporates:

- **Tailcat / Tailscale**: `github.com/tailscale/tailcat`, based on signed release
  `v0.4.0` (`ce6fedcabc220bab3b94d470ab330219111eeae8`) plus local commit
  `49c65dace2d79b41d89f536289002816d13e5274`, embedded in
  `third_party/tailcat`, licensed under the BSD 3-Clause License. The local
  commit changes network-monitor fallback and DERP-map resolution behavior; the
  embedded tree is not the untouched signed tag.
- **Google gVisor Netstack**: `gvisor.dev/gvisor/pkg/tcpip`, licensed under the Apache License 2.0.
- **Go Mobile**: `golang.org/x/mobile`, licensed under the BSD 3-Clause License.
- **CBOR Go**: `github.com/fxamacker/cbor/v2`, licensed under the MIT License.
- **Mem**: `go4.org/mem`, licensed under the Apache License 2.0.

The list above is not a complete SBOM. The checked-in AAR also links transitive
Go dependencies recorded by `go version -m`, and the embedded Tailcat tree has
its own module graph. Generate, archive, and manually review a complete
Android/Go dependency and license report before any release.

## External services

Cloudflare and ipify are network services, not bundled libraries. Their use is disclosed in `PRIVACY_POLICY.md`.

## Trademarks

WireGuard, Tailscale, Android, Kotlin, Cloudflare, and other names belong to their respective owners. Their appearance describes compatibility or dependencies and does not imply endorsement.
