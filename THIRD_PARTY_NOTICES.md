# Third-Party Notices

**OpenTailcat**  
Copyright (c) 2026 OpenTailcat contributors. All rights reserved.

## Complete notices

The full list of third-party components that ship in the APK, with their
versions, license types, and full license texts, is generated into
[`app/src/main/assets/THIRD_PARTY_NOTICES.txt`](app/src/main/assets/THIRD_PARTY_NOTICES.txt)
by `scripts/licenses/generate-notices.sh`. The app shows the same file under
Settings > About & legal > Open-source licenses.

The generator covers:

- every Go module linked into `libgojni.so` in the checked-in
  `app/libs/libtailcat.aar`, read with `go version -m`, with license texts
  collected by `go-licenses`, plus the Go standard library;
- every library on the Android release runtime classpath (AndroidX, Compose,
  Kotlin, kotlinx.coroutines, AndroidX Security Crypto and Tink, and their
  transitive dependencies).

Re-run the script after changing Gradle dependencies or rebuilding the AAR.
Test-only libraries (JUnit 4 under the Eclipse Public License 1.0, AndroidX
Test) are not shipped and are not listed there.

## Tailcat provenance

`github.com/tailscale/tailcat` is a git submodule pinned to the unmodified
upstream commit `52fbad323e9d352f1febb623e0975368e9a8cbdd` (17 commits after
the signed `v0.7.0` tag, not yet released upstream), licensed under the BSD
3-Clause License. See `third_party/PROVENANCE.md`.

## External services

Cloudflare is a network service, not a bundled library. Its use is disclosed in `PRIVACY_POLICY.md`.

## Trademarks

WireGuard, Tailscale, Android, Kotlin, Cloudflare, and other names belong to their respective owners. Their appearance describes compatibility or dependencies and does not imply endorsement.
