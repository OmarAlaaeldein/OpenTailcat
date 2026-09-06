# OpenTailcat engineering evaluation — 2026-09-05

Audited revision: `cdb0245` (`main`), version 1.2.2 / versionCode 14.

**Verdict: development prototype with release-blocking startup, lifecycle, transport and verification defects. Full-device encrypted operation was not established in this audit.** The supplied token successfully completed a native gateway preparation, but the shipped Android service refused to connect even after Android Always-on and lockdown were enabled. No production capabilities were promoted and no production artifact was signed or published.

## Scope and method

Three parallel reviewers covered native code, Android code, and documentation/build artifacts. The primary reviewer handled the capture scripts/analyzer, builds, emulator automation, live token, and consolidation. All first-party production source, tests, resources, repository documentation, build scripts and CI were reviewed. Relevant pinned Tailcat/Tailscale transport and lifecycle call paths were traced. This is not a line-by-line audit of every transitive dependency, cryptographic implementation, or gateway deployment.

At the audit snapshot, repository implementation files and the checked-in AAR were unchanged. Audit regressions, a second-UID probe and an additional Android instrumentation diagnostic were created outside the repository. This report was the only repository addition at that point. The external Android test was injected through a temporary Gradle init script; it did not change production APK code. The subsequent startup crash correction is recorded below.

The Mac had no physical ADB device attached. Testing used the available API 37 ARM64 emulator, with 16,384-byte memory pages. The host itself had an existing VPN route; its physical public IP must not be mistaken for a clean ISP baseline. No gateway SSH/capture access was supplied. Resource exhaustion interrupted emulator sessions; disposable caches were cleared with the maintainer's authorization and the final session used reduced memory/graphics settings.

## What actually passed

| Check | Result and limits |
| --- | --- |
| `go test -race -json ./...` | 86 top-level tests passed; engine package 19.66 seconds; no race reports in covered cases |
| `go vet ./...` | Passed |
| Fresh `./gradlew testDebugUnitTest --rerun-tasks` | 49 tests, 8 suites, zero failures/errors/skips |
| `lintDebug` | Zero errors, 16 warnings; deprecations and other existing warnings remain |
| Debug APK, Android test APK, release APK and release AAB builds | Passed; full build took 2m31s; release signing environment variables explicitly removed |
| Existing token-pairing instrumentation | Passed on the emulator; stores a profile, does not start a VPN |
| Audit-only native handshake instrumentation | Passed: real supplied token reached `PREPARED` in 867 ms; native `stop()` called afterward; no TUN created |
| Debug native loading | AAR loaded on ARM64 Android with 16 KB pages; native prepare/stop executed |
| AAR integrity/API/ABIs | Hash matched sidecar and handoff; both ARM64 and x86-64 present; Java API inspected |
| ELF and APK alignment | AAR and APK native LOAD segments aligned to 16 KB; APK ZIP alignment passed, including AndroidX native library |
| R8 retention inspection | Engine, `setSocketProtector`, and `SocketProtector.protect(long)` retained; minified runtime was not exercised |
| Artifact signatures | Debug signature verified; release APK/AAB intentionally unsigned |
| Upstream provenance | Clean submodule at documented `0c31395bfd1ae0c0ef2917c0ec20432466087417` |

The existing native tests mostly use mocks/userspace test stacks. Passing their UDP/QUIC and DNS tests is not equivalent to a real Tailcat gateway integration test: the QUIC test reflects synthetic datagrams, and one DNS retry test directly calls the mock TCP dialer.

## Live Android and capture results

The controlled probe ran as Android shell **UID 2000**, distinct from the VPN application. It used bounded literal-IP HTTP/HTTPS, DNS UDP/TCP, and NTP UDP requests. HTTPS used ordinary certificate/hostname validation with Android's CA directory. A harmless HTTP query marker made the baseline traffic identifiable.

1. Before VPN/lockdown, IPv4 HTTP, HTTPS, UDP DNS, TCP DNS and NTP UDP succeeded. HTTPS returned a public IPv4 address. IPv6 TCP/UDP attempts timed out even before the VPN, so this environment cannot establish working IPv6 egress or absence of IPv6 bypass.
2. The initial emulator `-tcpdump` capture did **not** see the Wi-Fi probe traffic. This blind capture was rejected as evidence. Disabling emulator Wi-Fi selected its virtual cellular `eth0` path; `tshark` then visibly identified the baseline HTTP marker, HTTPS, DNS query and NTP destination.
3. Connect without lockdown correctly produced a refusal. After enabling Always-on and Block connections without VPN through Android Settings, secure settings reported `always_on_vpn_app=com.tailcat.vpn` and `always_on_vpn_lockdown=1`. Android's VPN dump independently recorded both flags enabled.
4. The app still refused with the lockdown-required message. Repeated Connect and process recreation reproduced it. Android reported no active VPN (`type=-1`), no TUN, and no remaining service after failure.
5. While lockdown was enabled and no VPN existed, every second-UID probe failed with `permission denied`. This is positive evidence for Android lockdown in this tested state. It is **not** an encrypted-tunnel pass or a connected-pump-failure test.
6. To isolate the token from the startup bug, an audit-only instrumentation test called the shipped native `prepare()` without creating routes. It reached `PREPARED` in 867 ms and stopped cleanly. Prepared-state telemetry reported transport `DISCONNECTED`; no live transport mode was inferred from that field.

Wireshark's `tshark` was used to inspect real emulator captures and synthetic analyzer regressions. TLS, DNS and other transport traffic were visible; the presence of TLS or absence of selected probe IPs cannot establish full-device WireGuard coverage. There is no successful connected second-UID transfer or simultaneous gateway capture in this audit. **Encryption of full-device traffic remains unverified.**

## High-priority findings

### H1 — Startup requires a lockdown result that depends on an established VPN

**Evidence: reproduced on the API 37 emulator; framework source supports the diagnosis.**

[TailcatVpnService.kt](app/src/main/java/com/tailcat/vpn/service/TailcatVpnService.kt), lines 93–103, queries `isLockdownEnabled` and rejects false before native prepare or the first `Builder.establish()`.

Android's `VpnService` delegates the query to `isCallerCurrentAlwaysOnVpnLockdownApp`. The inspected framework checks the current VPN owner through underlying-network information, which is absent until the VPN is running. This creates a startup cycle with this placement of the check. See [AOSP VpnManagerService](https://android.googlesource.com/platform/frameworks/base/%2B/android-16.0.0_r2/services/core/java/com/android/server/VpnManagerService.java) (`getVpnIfOwner`, `isCallerCurrentAlwaysOnVpnLockdownApp`) and [AOSP Vpn](https://android.googlesource.com/platform/frameworks/base/%2B/afe0d0e3c7b4/services/core/java/com/android/server/connectivity/Vpn.java) (`getUnderlyingNetworkInfo`, `isRunningLocked`). This framework explanation is an inference consistent with the live reproduction, not a claim that every Android vendor/version behaves identically.

**Required correction:** establish and validate a startup sequence that can inspect lockdown without requiring an already-routed VPN. Preserve the no-default-route-before-readiness invariant; add a real service instrumentation test for fresh Always-on startup. Simply disabling the guard would not satisfy leak requirements.

### H2 — The Android socket protector is bypassed by upstream transport configuration

**Evidence: traced production call paths; post-route failure not exercised because H1 blocks connection.**

[protect_android.go](core-engine/protect_android.go), line 8, registers the Android `netns` protect callback. However, [tailcat.go](third_party/tailcat/tailcat.go), line 1725, calls `netns.SetEnabled(false)` when creating the engine. In the pinned Tailscale dependency, `net/netns/netns.go:94–96` then returns a listener without its control hook, and `:122–125` returns a plain dialer. The Android callback in `netns_android.go` therefore does not run for these sockets. Magicsock uses that listener (`wgengine/magicsock/magicsock.go:3714`); DERP uses that dialer (`derp/derphttp/derphttp_client.go:714`).

The current Android builder includes the app UID. Fresh/reopened transport sockets can consequently be routed back into the TUN instead of explicitly bypassing it. Pre-route bootstrap success does not validate reconnection behavior. The separately protected bootstrap DNS resolver does not fix DERP/Magicsock sockets.

**Required correction:** restore a supported Android transport-protection path and prove callback invocation, post-route redial, direct/DERP transition and roaming. Preserve the unmodified-submodule constraint; resolve this through an appropriate upstream integration rather than silently patching the pin.

### H3 — Attach can report RUNNING after its TUN reader has already died

**Evidence: reproduced under `-race` with an audit-only regression.**

[lifecycle.go](core-engine/lifecycle.go), lines 259–280, ignores pump failure unless `sess.bridge == bridge`, but assigns that pointer only after `bridge.Start()`. Readiness signals precede actual I/O. Supplying an EOF descriptor (`/dev/null`) reproduced successful `AttachTun`, state `RUNNING`, and fresh health after the read pump exited.

**Required correction:** publish and validate attach ownership atomically with readiness/failure state; prove usable packet directions and reject a pump that fails during startup. Add this case to maintained tests.

### H4 — A silent DNS TCP fallback can hang shutdown indefinitely

**Evidence: reproduced under `-race`; manually closing the mock resolver released shutdown.**

[netstack_proxy.go](core-engine/netstack_proxy.go), line 543 onward, opens TCP for UDP DNS fallback without read/write deadlines, cancellation closure or connection tracking. A silent resolver blocks the response read. The proxy's close path waits for that flow at line 689, before `TunBridge.Stop` reaches its timeout. [lifecycle.go](core-engine/lifecycle.go), lines 96–104 and 364, closes the session client afterward.

The audit assertion exceeded the documented three-second stop bound. Cancelling the context alone did not release the connection.

**Required correction:** track and close fallback TCP connections, apply bounded I/O, and put the stop bound around the complete teardown. Cover simultaneous stops and interrupted warm-TUN detach.

### H5 — Gateway loss can leave health fresh and old connectivity displayed

**Evidence: production/upstream source analysis; live outage not exercised.**

[bridge.go](core-engine/bridge.go), lines 585 and 649–662, refreshes health while `Client.Ping` succeeds. Pinned upstream closes its acknowledgment channel permanently after the first successful Meow reply ([tailcat.go](third_party/tailcat/tailcat.go), lines 1848 and 2047). Subsequent Ping calls can succeed from that closed channel without a new gateway reply. Actual `DiscoPing` failures are ignored at `bridge.go:627–629`.

**Required correction:** use a fresh authenticated observation for liveness; expire transport, RTT and health consistently after failure. Test a dead gateway while the physical network remains validated.

### H6 — Android startup and shutdown can race descriptor ownership

**Evidence: concrete source interleaving; no Android race reproduction.**

[TailcatVpnService.kt](app/src/main/java/com/tailcat/vpn/service/TailcatVpnService.kt), lines 26, 103–127 and 192–205, runs start and stop concurrently on `Dispatchers.IO`. `vpnInterface` is unsynchronized; the volatile `shuttingDown` check-and-set is not atomic. Shutdown can cancel startup, inspect/clear the old descriptor, and finish while startup subsequently establishes and assigns another descriptor. Its cancellation handler then calls shutdown, which returns early.

**Required correction:** serialize lifecycle transitions and give every newly created descriptor unconditional cleanup until ownership is transferred. Test cancellation at each establish/attach boundary.

### H7 — The capture analyzer can falsely pass a real leak

**Evidence: two executed synthetic reproductions, independently checked with tshark.**

[phase8_pcap.go](core-engine/phase8_pcap.go), line 103, treats unsupported link types as raw IP and can silently discard all packets. A classic-PCAP Linux SLL2 frame containing destination `8.8.8.8` produced exit 0 and both PASS messages when paired with a matching gateway capture; `tshark` decoded the leaking destination. An entirely empty uplink capture also produced both PASS messages. [cmd/phase8-analyze/main.go](core-engine/cmd/phase8-analyze/main.go), lines 61–71, has no capture-validity requirement and makes gateway evidence optional.

The checker also lacks flow/UID/time correlation, reply or payload verification, and an allowed-transport policy. An unrelated gateway packet can satisfy its destination-only condition. It must not be used as a Phase 8 acceptance authority.

**Required correction:** fail on unsupported/truncated/empty/inadequately scoped capture input; use a proven packet decoder, mandatory simultaneous captures, positive capture controls and uniquely correlated successful probes. Verify relevant encapsulations and both address families.

### H8 — Supported API 26–28 does not require verified lockdown

**Evidence: source and existing permissive unit test; no API 26–28 device available.**

[LeakGuard.kt](app/src/main/java/com/tailcat/vpn/service/LeakGuard.kt), line 24, enforces lockdown only for API 29+. The service substitutes true on older versions. Closing the TUN on failure can therefore restore direct routing with no verified platform barrier. This is a release limitation rather than a leak observed in this run.

**Required correction:** define and enforce a supported older-Android safety policy, then test failure behavior on the minimum supported API.

## Additional correctness, privacy and release findings

| Severity | Finding and evidence | Required direction |
| --- | --- | --- |
| Medium | **Network-change notification is unwired.** `main.go:88,178` reads `activeMonitor`, but production never assigns a non-nil monitor; the only assignment is a test. The pinned client has no exported `NetMon` method despite handoff claims. | Integrate actual upstream monitoring and test interface changes. |
| Medium | **TCP half-close can truncate replies.** `netstack_proxy.go:309–333` fully closes both connections when either copy completes. A client FIN can discard a later server response. Upstream `ProxyConns` waits for both directions. Source finding. | Preserve half-close semantics and test response-after-FIN. |
| Medium | **Pin verification searches unverified supplied certificates.** `tls_pin.go:28` checks `PeerCertificates`, including certificates outside the verified chain. An audit regression accepted an unrelated pinned certificate alongside a differently verified chain. Normal trust and hostname verification remain enabled; this is not an unauthenticated MITM demonstration. | Restrict pin matching to appropriate certificates in `VerifiedChains`; cover the negative case. |
| Medium | **Kotlin/Go token parity is false beyond the shared corpus.** Executed synthetic tokens with region `i=4294967297`, region `c=1`, region `N=1`, host `::ffff:127.0.0.1`, or host `::ffff:169.254.169.254` were accepted by Kotlin and rejected by Go. `TokenParser.kt:604,615,713`; `token.go:533`. Native validation prevents a demonstrated connection bypass. | Align field types, integer bounds, mapped-address handling and shared fixtures. |
| Medium | **UDP availability is guessed once.** `main.go:287` uses a one-second DNS response probe; `lifecycle.go:166` permanently selects TCP-only mode if it fails. This is not versioned gateway capability negotiation. | Distinguish timeout/policy failure from protocol support and expose limitations honestly. |
| Medium | **UDP bytes are double-counted.** Whole TUN packets count at `bridge.go:306`, and UDP payloads count again at `netstack_proxy.go:595,611`. | Define accounting boundaries and compare against packet lengths. |
| Medium | **Unknown WG values look measured.** `NetworkMetrics.kt:31`, `TelemetryCard.kt:215`, `VpnNotificationManager.kt:73` display zero byte/rate counters although authoritative WireGuard status is unavailable. | Expose unavailable values; label any actual TUN accounting separately. |
| Medium | **Future health timestamps pass freshness.** An executed Kotlin probe with `healthUnixSec=999999999999` returned live. `NetworkMetrics.kt:47` lacks a lower bound on age. | Reject future samples beyond tolerated skew and use monotonic local freshness. |
| Medium | **Profile display can differ from active session.** Adding a profile makes it active without stopping a running session; selecting stops only CONNECTED, not CONNECTING/RECONNECTING. `ProfileRepository.kt:132`, `HomeViewModel.kt:78,94`. | Bind displayed identity to the actual session; handle edits in every active state. |
| Medium | **Split-tunnel controls advertise unavailable behavior.** `SettingsScreen.kt:259` says selected apps bypass, while `TunnelController.kt:94` rejects every nonempty selection. | Disable or accurately label the unavailable feature. |
| Medium | **App-UID path descriptions are stale.** The builder no longer excludes the app UID, but `IpAuditor`, benchmark labels, privacy/security docs and engineering guides say it does. Speed path choice is sampled only once (`SpeedTestViewModel.kt:22`, `SpeedTestScreen.kt:123`). | Describe the current socket-protection model and handle benchmark state changes. |
| Low | **DNS validation mishandles numeric addresses.** Executed Kotlin probes reject `2001:db8::53` and `2001:4860:4860::5353` due to substring `:53`, but accept `+1.1.1.1`. `DnsValidator.kt:29,70`. | Parse complete numeric addresses, with strict IPv4 digits. |
| Low/medium | **Ordinary HTTP speed tests lack failure/cancellation cleanup.** `SpeedTestEngine.kt:160,208` closes resources only on success; blocking loops are not cancellation-aware and upload lacks an effective write deadline. Source finding. | Finally-close resources and bound/cancel I/O. |
| Medium release gap | **CI cannot detect an AAR stale against source.** `.github/workflows/ci.yml:23` compares only AAR to sidecar; it never rebuilds native source. CI also omits race tests and release/device gates. | Rebuild and compare the AAR from a clean pinned toolchain; archive provenance. |
| Medium release gap | **Reproducibility safeguards are incomplete.** `build-aar.sh:78` accepts any existing gobind; `:125` checks names rather than exact Java signatures; `:69` shares and deletes a canonical staging directory across builds. | Pin the executable actually used, verify signatures, and isolate/lock staging. |
| Release gap | **No complete distribution inventory found.** No full SBOM or packaged dependency notices were found in APK/AAB/AAR; `THIRD_PARTY_NOTICES.md:29` acknowledges unfinished inventory. | Generate and review complete shipped dependency/license evidence before distribution. |

## Positive properties worth retaining

The inspected application-flow code uses Tailcat `DialTCP`/`DialUDP`, with no client-side ordinary socket substituted for tunneled application traffic. Native HTTPS retains system trust/hostname verification and TLS >=1.2. Tokens are revalidated natively. Android preferences are encrypted without a plaintext fallback; backup/device transfer are disabled; the activity uses `FLAG_SECURE`; and the VPN service is nonexported and permission-protected. Release builds do not fall back to the debug signing key. The IPv6 default route is retained in the intended routed configuration instead of quietly omitting IPv6. Development-build warning text is visible.

These are useful implementation properties, not evidence that the current app meets the full VPN release definition.

## Artifact evidence

Checked-in AAR SHA-256:

```text
188ee38c4708a05a398aab8e05f278ed3903d692eee304028a1eff83ae2fa084
```

Both native ABIs report Go 1.27.1, NDK r29 / build 14206865, Android API 26, trimpath and 16 KB ELF alignment. Fresh APK/AAB `libgojni.so` bytes match the AAR. Java exports include `setSocketProtector(SocketProtector)` and `SocketProtector.protect(long)`; their names survive R8 output inspection.

AAR integrity and binary metadata were independently inspected. A full clean-room AAR rebuild comparison was **not** performed in this evaluation; source was not changed. ARM64 debug JNI ran on 16 KB Android. x86-64 runtime and minified release JNI remain untested.

## Evidence locations and reproduction

Sensitive live inputs and raw captures are outside source control in the maintainer's temporary audit workspace. Do not commit or publish its packet captures or private runtime records. No live token value is present in this report.

After testing, the temporary token file was deleted and the read-only emulator was shut down, discarding its test session state. Raw captures remain restricted to the private working directory; `capture-summary.json` records the finalized capture hash and protocol counts. This is file cleanup, not a guarantee of physical memory or storage erasure.

| Evidence | Location |
| --- | --- |
| Baseline race output / vet / regression failures | Private native audit workspace: `baseline-race.jsonl`, `vet.log`, `regressions.log` |
| Three native audit assertions and Go overlay | Private native audit workspace: `audit_regressions_test.go`, `overlay.json` |
| Kotlin parser/DNS/health probes | Private Android audit workspace |
| Full artifact verification and raw tool results | Private artifact audit workspace |
| Capture-checker reproduction | Private working directory: `pcap_repro.py`, synthetic PCAPs and `phase8-analyze` |
| Separate-UID probe source/results | Private working directory: `probe.go`, `final-baseline.jsonl`, `after-connect-probes.jsonl` |
| Live app failure | Private working directory: `connection-ui.txt`, `lockdown-vpn-state.txt` |
| Native handshake diagnostic | Private working directory: `android-test`, `audit-test.init.gradle`, `native-handshake.txt` |

The three native audit assertions intentionally **fail** against the reviewed implementation. Existing passing tests were not modified to conceal these failures. Run them with `go test -race -count=1 -overlay <overlay.json> -run '^TestAudit' .` from `core-engine` while the temporary evidence exists.

## Required next work and remaining acceptance

1. Repair H1 startup and H2 transport protection; test a fresh install and Always-on start on supported Android versions.
2. Repair H3/H4/H5/H6 lifecycle and health behavior, with the reproduced regressions and real service cancellation tests maintained in-tree.
3. Correct the capture gate before using it to promote capabilities. Validate capture visibility with known baseline traffic and correlate successful connected second-UID flows with gateway capture.
4. Correct parser, TCP half-close, DNS validation, pin verification, accounting and UI/docs mismatches.
5. Run the remaining live matrix: IPv4/IPv6 TCP and UDP, real QUIC/HTTP3, large DNS and retry, gateway version/capability proof, forced DERP/direct, Wi-Fi/cellular roaming, offline/captive states, doze, process death/revoke, repeated start/stop, split policy, and MTUs 1280–1500.
6. Perform simultaneous physical ARM64 uplink and gateway capture, minimum-API testing, minified JNI runtime, deterministic AAR rebuild, complete SBOM/notices and production-signing gates.

`ipv6` remains false. Existing IPv4 flags remain development test switches; this evaluation supplies no release-promotion approval. There was no full-device encrypted connection, no gateway capture, no physical leak acceptance, and no production release.

## Startup crash correction — follow-up on 2026-09-05

A dedicated API 37 ARM64 emulator reproduced a process-ending `SecurityException`
at `TailcatVpnService.onStartCommand` when Android VPN consent was absent. The
`systemExempted` foreground-service promotion was outside error handling. The
same promotion code is present in the local `v1.2.2` tag. This is a confirmed
reproduction in the emulator; the cause of the maintainer's phone crash remains
unconfirmed without its crash trace.

Simply catching the error and stopping also reproduced
`ForegroundServiceDidNotStartInTimeException`: Android still required the service
to complete its pending foreground-start request. The correction checks consent
before requesting the service and again inside it, catches foreground promotion
errors, and uses a transient `shortService` notification solely to complete
rejection cleanup. The tunnel continues to require `systemExempted`. This follows
the documented [Android foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types).
Failed starts clear persisted `vpnWanted`, preventing automatic restoration loops.

`VpnStartupInstrumentedTest` uses synthetic public keys. Against the correction,
it verified permission refusal during restore, a service request arriving after
consent is lost, continued process survival, native `STOPPED`, and absence of a
leftover VPN service or notification. After consent was restored, an explicit
retry reached the existing lockdown refusal and cleaned up normally. The final
run passed in 22.837 seconds; the emulator crash buffer was empty afterward.

Final checks: 49 Android unit tests passed, lint had zero errors and 16 existing
warnings, and debug/test APK plus unsigned release APK/AAB builds passed. No
native source or AAR changed. The debug APK is
`app/build/outputs/apk/debug/app-debug.apk`. H1 and H2 and the other audit findings
remain open; this correction does not establish a working encrypted tunnel.

## Packaging correction — 1.2.3 build 16

The initial 1.2.3 downloads used an unoptimized debug variant and increased the
ARM64 APK from 20.8 MB to 37.0 MB. The optimized `development` variant restores
code/resource shrinking and omits debug UI tooling, bringing ARM64 to about
20.8 MB and x86-64 to 22.2 MB. It retains the existing development certificate
and advances the version code to 16 for installation over the original 1.2.3.

The [complete optimization work log](docs/verification/1.2.3-optimized.md)
records the build changes, unsuccessful minified test-runner experiments,
passing direct emulator UI checks, upgrade/signature/alignment verification,
and remaining test limits. README, handoff, CI, and release notes were updated
before committing. Native source and AAR remain unchanged; the unresolved VPN
findings above are not addressed by APK shrinking.
