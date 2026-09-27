# Review 01 — Go engine (core-engine) line-by-line + security

Status: COMPLETE

Reviewer 1 of 4. Scope: token.go, cbor_strict.go, lifecycle.go/main.go concurrency, ipv6_egress.go, egress.go, phase8_pcap.go, protect*.go, cmd/*, build-aar.sh, govulncheck/staticcheck, Go test quality.
Baseline: docs/review-2026-09-26.md (findings there are not repeated).

## Findings

### token.go / cbor_strict.go

- **[L] [parity/determinism] core-engine/token.go:226-447 — Error code and legacy RegionID depend on Go map iteration order**
  `ParseToken` validates fields with `for k, v := range rawMap`, so a token with two bad fields returns a random code. Verified in a /tmp copy: `{"p":2 bytes,"k":1 byte,"i":1}` returned `ERR_INVALID_DISCO_KEY` 178/200 and `ERR_INVALID_NODE_KEY` 22/200. A legacy token with both `i` and numeric `r` reports `RegionID` 7 or 9 at random (87/113). Kotlin (`TokenParser.kt:317`, `mutableMapOf` = wire order) is deterministic, so the "Kotlin and Go agree exactly" contract only holds for single-error tokens. The fixture suite passes only because every fixture has at most one error.
  Fix: validate in a fixed key order (iterate `[]string{"p","k","q","i","r","exp","iat"}`) or keep an ordered slice from `parseStrictTokenMap`. Effort S.

- **[L] [validation gap] core-engine/token.go:590-631, 700-752 — Embedded-DERP checks can be bypassed and are inconsistent (defense in depth; the token is trusted)**
  Verified accepted as `VALID_OFFICIAL_RESOLVED`: `h` = `localhost.` / `LOCALHOST.` (trailing dot skips the `== "localhost"` check; Go's resolver matches `/etc/hosts` by absolute name, so this resolves to 127.0.0.1 on Android), `127-0-0-1.nip.io`, `255.255.255.255`. Numeric forms (`127.1`, `2130706433`, `0x7f000001`) also pass, but with `PreferGo` they become DNS lookups and fail. Region shape is inconsistent too. `"N":[]` is rejected, but a missing `N` or `"N":null` is accepted as a region with 0 nodes, and connect then fails after a 10 s Ping timeout. Duplicate region IDs are accepted (`[{N:[a]},{i:1,N:[b]}]`), and upstream `mak.Set(&dm.Regions, r.RegionID, r)` silently drops the first region. Node `i` (region ID, even 99999), `s`, `d` and `t` (CertName) are allowlisted but never range-checked. Kotlin mirrors the `localhost` logic (`TokenParser.kt:670`), so the fix must go in both parsers (or delete the Kotlin one, as the baseline suggests).
  Fix: strip one trailing dot and lowercase before the name checks. Require a non-empty `N` list. Reject duplicate region IDs, and node `i` != region ID or outside 1..65535. Bound `s`/`d` to -1..65535. Effort S.

- **[L] [parity] EXTENDS: "Two independent strict CBOR token parsers" — new divergence: explicit `null` region ID**
  An embedded region `{"i": null, ...}` is rejected by Go (`token.go:664-672`: nil hits `default` → "invalid region ID type") but accepted by Kotlin (`TokenParser.kt:617-621`: `null -> ri + 1`). Also, Go and upstream both accept `"c": null`, non-minimal CBOR integer/length encodings (`0x78 0x01 'p'`, `i` as an 8-byte uint) and base64 tails with non-zero pad bits. That is not a security problem, but "canonical" in the comments is inaccurate. Effort S (or zero if the Kotlin parser is deleted).

- **OK — cbor_strict.go bounds:** every length goes through `readBytes`, which checks `n < 0 || remaining < n`. `int(uint64)` truncation only matters on 32-bit targets, and none are built (the AAR is arm64/x86_64). Depth is capped at 16, arrays and maps at 256 entries (worst-case allocation amplification ~25x of a 32 KiB payload ≈ <1 MiB), and top-level keys at 128 bytes. Indefinite lengths, tags, floats, and duplicate keys at every level are rejected, and trailing bytes are checked. Negative ints can't overflow (`val > MaxInt64` rejected). Invalid UTF-8 text passes the strict reader but upstream `cbor.Unmarshal` (UTF8RejectInvalid) rejects it in step 6. No panic found by reading. (Fuzz result below.)

- **OK — Fuzzing (in a /tmp copy, not the repo):** three fuzz runs found no panics and no invariant breaks. `FuzzR1ParseToken` fed arbitrary CBOR bytes → `tc`+base64 for 90 s (~880k execs) and checked two invariants: `err == nil` ⇔ `IsConnectable()`, and the result is never nil. `FuzzR1ParseTokenRaw` fed arbitrary strings for 45 s (~600k execs). `FuzzR1UpdateNetworkState` fed arbitrary JSON for 45 s (~1M execs).
- **Note — derp_filter.go** reuses `rejectUnsafeDERPEndpoint` for the fetched `tailcat.dev` map, so the trailing-dot `localhost.` bypass above also applies there. Otherwise it is fine: the body read is capped at 8 MiB, and a filter failure turns into a 502 so upstream fails closed.

Progress: finished token.go, cbor_strict.go, derp_filter.go (spot check)

### lifecycle.go / main.go concurrency

- **[M] [lifecycle/availability] core-engine/lifecycle.go:392-435, bridge.go:385-409, netstack_proxy.go:759-797 — `Stop()` has no overall bound; the "3 s bound" in AGENTS.md is false**
  `Stop` → `closeSession` → `TunBridge.Stop` runs two 3 s waits one after the other: `netstack.Close()` blocks up to `stopWaitTimeout` on `udpWg/tcpWg`, then `TunBridge.Stop` waits another 3 s on `b.wg`. Then `client.Close()` (upstream `Client.Close` → takes `startMu` → `lb.Close()`) runs with no timeout at all. Every concurrent `Stop()` caller and every `Prepare()` waits on `stopWait` with no timeout either (`lifecycle.go:133-140,403-409`). With the baseline H (blocking TUN fd, so `readLoop` never leaves `b.wg`), every Stop with an attached bridge takes at least 3 s. The Kotlin `onDestroy()` → `shutdown()` path calls native `stop()` **on the main thread** (`TailcatVpnService.kt:330-333,316`), so a system-initiated service destroy freezes the UI for 3-6 s or longer.
  Fix: run `closeSession` under one deadline (e.g. `context.WithTimeout(3s)`, abandon on timeout and log). Run the netstack wait and the pump wait concurrently, not in sequence. Give waiters a bounded `select`. Move `shutdown()`'s native stop off the main thread. Effort S-M.

- **[L] [robustness] EXTENDS: "gomobile boundary has no panic recovery" — Stop's cleanup is not deferred, so the planned recover() fix would wedge the engine**
  `Stop()` resets `stopping=false` and calls `close(done)` only after `closeSession` returns normally (`lifecycle.go:428-433`). Today a panic in upstream `client.Close()`/`bridge.Stop()` crashes the app (no recover, and `markFailed` uses a bare `go closeSession(sess)`). If a boundary `recover()` is added as the baseline's P1 #10 suggests, the same panic leaves `stopping == true` forever: every later `Prepare()` spins on `<-ch` and every `Stop()` blocks, so the VPN can never reconnect until the process dies. Fix: `defer` the stopping/stopWait reset in `Stop`, and add a `recover` inside `closeSession`'s `Once`. Effort S.

- **[L] [lifecycle] core-engine/lifecycle.go:255-309, main.go:203-236, lifecycle.go:392-419 — DNS/MTU staging can lose updates**
  `AttachTun` snapshots `pendingDNS` at :265 but publishes the bridge only at :309. If `applyDNSPolicy` runs in that window, it stores the new config and then sees `sess.bridge == nil` (cleared at :255), so the new bridge starts with the old resolver policy. `Stop()` clears `pendingDNS` but never `pendingMTU` (only `abandonPrepare` clears both). A `Stop()` on an already-stopped engine also wipes a `pendingDNS` that was staged for the next connect (`:398-399`). All of this is masked today only because Kotlin sends `updateNetworkState` (with `dnsPolicy`/`tunnelMtu`) again right after every `attachTun` (`TailcatVpnService.kt:234-235`). Any refactor that drops that second call reintroduces the "200.x stale DNS" class of bug.
  Fix: after publishing the bridge in `AttachTun`, re-load `pendingDNS` and apply it (or store and read it under `globalCore.mu`). Treat `pendingMTU` the same as `pendingDNS` in `Stop`. Effort S.

- **[L] [availability] core-engine/main.go:26-57 — Process resolver never fails over to the 2nd Android DNS server and ignores `network`/`address`**
  With `/etc/resolv.conf` missing, Go uses `defaultNS = [127.0.0.1:53, [::1]:53]` with `attempts:2, timeout:5s` (verified in Go 1.27.1 `net/dnsconfig*.go`). The custom `Dial` ignores the `address` Go passes and always tries `dnsList` in order, but a UDP dial almost never fails, so `dnsList[0]` is used for every attempt. If the first LinkProperties DNS server silently drops packets, DERP hostname lookups and the `tailcat.dev` DERP-map fetch burn up to ~20 s per query and never try server #2. `Prepare`'s 10 s Ping budget then fails with a generic handshake timeout. `network == "tcp"` (truncation retry) also gets a UDP socket. (Unverified on device: how often the first Android DNS server is unresponsive.)
  Fix: map Go's server index to `dnsList[i % len]` (for example, pick the server from the `address` argument or rotate a counter per attempt) and honor `network`. Effort S.

Progress: finished lifecycle.go, main.go (UpdateNetworkState fuzzed; no panic)

### egress.go / ipv6_egress.go / tls_pin.go / protect*.go

- **[M] [resource/battery] core-engine/egress.go:61-82 — Egress audit retries forever every ~3 s with no backoff or cap**
  `egressProbeLoop` loops `for attempt := 1; ; attempt++` until one probe succeeds. Each attempt opens a gateway TCP flow to 1.1.1.1:443, does a full TLS handshake, and logs `Tailcat egress audit attempt N failed` with a fixed 3 s pause. Any persistent failure keeps this running for the entire session: a gateway that filters 1.1.1.1:443 (the gateway owns filtering policy per AGENTS scope), a Cloudflare HTTP change, or the baseline's pinned leaf key rotating (expires 2026-12-21). That is ~15-20 handshakes/min (roughly 100 KB/min of tunnel traffic, estimated from a ~5 KB TLS handshake) plus logcat spam, all day on cellular. It is only telemetry, so nothing needs it to succeed.
  Fix: exponential backoff (for example 3 s → 5 min cap) with a small attempt budget, then leave `egressAuditError` set. Log only when the error changes. Effort S.

- **[L] [correctness] core-engine/ipv6_egress.go:17-51, lifecycle.go:191 — `ipv6Egress` is a one-shot 3 s probe latched for the whole session**
  Prepare runs one probe: tunnel TCP, then the gateway's remote IPv6 dial, then TLS over a possibly DERP-relayed path, all within 3 s. The result goes into `sess.ipv6Egress` and is never re-measured (unlike `tcpOnly`, which re-probes every 30 s in `bridge.go:778-782`). One slow or failed probe on a dual-stack gateway disables IPv6 (RST for all public IPv6) until reconnect. The opposite case, gateway IPv6 WAN dying mid-session, keeps `ipv6Egress=true`, so IPv6 flows wait out the gateway dial timeout. Safe, because it fails closed to IPv4, but "measured per session" overstates it.
  Fix: reuse the UDP re-probe pattern (re-probe while false, and on repeated IPv6 dial failures while true), and raise the budget to ~5 s. Effort S.

- **OK — protect.go / protect_android.go / protect_stub.go:** small and correct. The protector is swapped under `protectorMu` and called outside the lock, and a nil protector is a no-op (desktop tests). `netns.SetEnabled(true)` is re-applied after every prepare/attach. Caveat: the process-global protector still points at the previous (destroyed) `VpnService` instance until Kotlin calls `setSocketProtector` again. Kotlin does that before every `prepare`, so there is no reachable bug today.
- **OK — egress.go parsing:** body is capped at 16 KiB, `parseEgressTrace` takes the first `ip=` line and `Unmap()`s it, and the TLS goes through the pinned config (pin weaknesses are in the baseline).

Progress: finished egress.go, ipv6_egress.go, tls_pin.go (no new pin findings beyond baseline), protect*.go
### phase8_pcap.go (parser)

- **[L] [test-evidence] core-engine/phase8_pcap.go:184-233, 307-309 — The uplink leak analyzer drops all port-53 traffic, so DNS leaks to the probe IPs can't be seen**
  `ProbeIPsOnUplink` skips any packet whose source or destination port is 53, including packets *to a probe IP*. The default probes are public resolvers (`generate-probes.sh`: `1.1.1.1 8.8.8.8 9.9.9.9`). A second-UID DNS query leaking straight to 8.8.8.8:53 therefore passes as "probe destinations absent", and no other Phase 8 script checks DNS leaks. Given AGENTS' definition of done ("DNS ... leak-free"), the gate can report PASS on the most common VPN leak class. (Semantics rather than robustness, but it undermines the Phase 8 evidence.)
  Fix: only exclude DNS to an explicit `--allow-dns` list (the LinkProperties servers the engine's protected resolver uses). Use non-resolver probe hosts for the TCP probes. Add a DNS-leak probe (unique QNAME from `adb shell`, then fail if it appears on the uplink). Effort S-M.
- **OK — parser robustness:** every slice access is length-guarded (the IPv4 IHL is checked against `len`), `incl` is capped at 1 MiB with `orig >= incl`, and unsupported magic or link types fail explicitly. Fuzzed 30 s in a /tmp copy (~530k execs) with no panic. Minor: the whole capture is buffered in memory (a ~270 MB pcap means ~270 MB+ RAM), only one VLAN tag is handled (0x8100, not 0x88a8/QinQ), `DLT_NULL` misses Linux AF_INET6=10, and nanosecond-magic pcaps are rejected. All of these fail toward "not decoded" (not counted), not toward a crash.

Progress: finished phase8_pcap.go
### cmd/*

- **[L] [stale/misleading + secret handling] core-engine/cmd/tailcat-cli/main.go:23-71 — The "multiplatform CLI" never routes traffic, `status`/`down` can't reach a session, and the token goes on argv**
  `up` only calls `engine.Prepare` (no TUN, no `AttachTun`) and then prints "Tunnel engine reports active". `status` and `down` run in a *new process* whose `globalCore` is empty, so `status` always prints `{"version":2,"sessionId":0,"state":"STOPPED",...}` (verified) and `down` is a no-op. The token (including any PSK `q`) is taken from argv/`-token`, so it shows up in `ps` and shell history. The usage text claims "Linux / macOS / Android". Nothing in CI or the docs depends on it.
  Fix: delete it, or reduce it to a `prepare`-only handshake probe that reads the token from stdin or a file, with honest output. Effort S.
- **OK — cmd/generate-fixtures:** builds, is deterministic (all keys and timestamps fixed), and regenerating in a /tmp copy produced a byte-identical `testdata/token_fixtures.json`. That file is shared with `TokenParserTest.kt`. Gap: it has no multi-error or `null`-field fixtures (see the parity findings above).
- **OK — cmd/phase8-analyze:** builds. Argument parsing is strict, and `--gateway` is mandatory (H7 fix present). Exit codes: 1 = leak or missing gateway probe, 2 = usage or parse error. The only analyzer issue is the DNS masking above.
- `go build ./cmd/...` → all three build with Go 1.27.1 (output in /tmp).

Progress: finished cmd/tailcat-cli, cmd/generate-fixtures, cmd/phase8-analyze
### build-aar.sh

- **[M] [supply-chain/provenance] EXTENDS: "CI does not rebuild the AAR" — build-aar.sh writes the source hash *before* building, so a failed build leaves a stale AAR that passes CI**
  `build-aar.sh:92-106` writes `app/libs/libtailcat.aar.sourcehash` for the current tree. `gomobile bind` only runs at :122. Under `set -e`, a compile, gomobile, or NDK failure exits right there, leaving the new sourcehash next to the *old* `libtailcat.aar` and the old `.aar.sha256`. CI (`ci.yml:27-49`) checks only `sha256(aar) == .sha256` and `hash(tree) == .sourcehash`, and both still pass. That is exactly the "checked-in AAR lags native source" state that AGENTS.md forbids, and it takes only one accidental commit. Separately, `-o "${OUTPUT_AAR}"` writes straight into `app/libs` *before* the javap and 16 KB checks, so a failed verification leaves an unverified AAR in place for local Gradle builds. CI would catch that one through the sha mismatch.
  Fix: build to a temp path, verify, then atomically move the AAR and write `.sha256` and `.sourcehash` last (sourcehash computed from the staged copy that was actually built). Effort S.

- **[L] [reproducibility] core-engine/build-aar.sh:111-116 — gobind version is only pinned when gobind is missing**
  `if ! command -v gobind` installs the pinned `x/mobile@…4776eadac327` only when no gobind is on PATH. Any older or newer `gobind` in `$GOPATH/bin` is used silently. `gomobile` itself comes from `go run` against `go.mod`, so gomobile/gobind can mismatch and the generated bindings differ between machines. (This machine's `~/go/bin/gobind` does match the pin, verified with `go version -m`.) `go.mod:64` already declares `tool golang.org/x/mobile/cmd/gobind`.
  Fix: always `GOBIN=$(mktemp -d) go install golang.org/x/mobile/cmd/gobind` from the module (or `go build -o` the tool from `go.mod`) and put that dir first on PATH. Record `go version -m gobind` in the metadata. Effort S.

- **[L] [gate coverage] core-engine/build-aar.sh:143-165 — Required-method check skips `ensureTransportProtect` and uses substring grep**
  Kotlin calls `ensureTransportProtect` via reflection, and a missing method is a silent no-op (baseline M "Native bridge is reflection-only"). This is the method that re-enables `VpnService.protect` on transport sockets (AUDIT H2), yet it is not in `REQUIRED_METHODS`. `grep -q "${method}"` is a substring match against the whole javap output and doesn't check parameter types.
  Fix: add `ensureTransportProtect`, match `public static .* ${method}\(` against the expected signature list, and diff against a committed `signatures.txt`. Effort S.

- **[L] [robustness] core-engine/build-aar.sh:10,84-87,135 — Fixed shared staging dir and late trap**
  `CANONICAL_BUILD_DIR=/tmp/opentailcat-build` gets `rm -rf` on start, so two concurrent builds (for example CI matrix jobs on one runner, or two worktrees) delete each other's staging tree mid-build. The cleanup `trap` is installed only after `gomobile bind` succeeds, so a failed build leaves the staging copy (full source plus `third_party`) behind. A fixed path is needed for `-trimpath`-independent reproducibility, so use a lock (`mkdir` lock / `flock`) rather than `mktemp`, and install the trap before the copy. Effort S.

- **OK —** `set -euo pipefail` and consistent quoting. Go (`go1.27.1`) and NDK (`29.0.14206865`) are pinned and checked. `-trimpath -ldflags="-s -w"`. Every LOAD segment's 16 KB alignment is checked per ABI, and an empty `grep LOAD` aborts under pipefail. `go version -m` and readelf metadata are recorded. The `LC_ALL=C` sort issue is already in the baseline.

Progress: finished build-aar.sh
### Tooling (run from core-engine; `git status --porcelain` unchanged afterwards)

- **govulncheck@latest:** 0 reachable vulnerabilities. It also reported vulnerabilities in code that is imported or required but never called: GO-2026-6355 and GO-2026-6354 (`golang.org/x/crypto/ssh` DoS on deadlocked channels, fixed in x/crypto v0.56.0; the tree has v0.55.0) and GO-2026-5932 (the unmaintained `x/crypto/openpgp` module, no fix). **[L] [deps]** Bump `golang.org/x/crypto` to ≥ v0.56.0 when the submodule and tailscale.com are bumped (baseline P3 #21), so the pattern-matching scanners Play/OSV run on the SBOM stay quiet. Effort S.
- **staticcheck@latest (v0.8.1):** exit 1, but every hit is SA1019 deprecation apart from one U1000. Production code: `tailcat.ConnBlob`/`ParseConnBlob` are deprecated in favor of `Addr`/`ParseAddr` (`lifecycle.go:152`, `main.go:368`, `token.go:547`), plus `key.*Raw32`/`NodePrivateFromRaw32` in `cmd/generate-fixtures` and tests. Unused: `dns_test.go:101 pairedStreamConn` (U1000). No correctness (SA4/SA5) or concurrency (SA2) findings. **[L] [maintenance]** Switch to `tailcat.Addr`/`ParseAddr` (the alias still works, but the upstream bump to v0.7.0 recommended in the baseline may remove it). Delete `pairedStreamConn`. Effort S.
- **Fuzzing:** see above (token parser, UpdateNetworkState, pcap parser — no crashes).

Progress: finished tooling
### Go test quality (brief)

- **[M] [tests] EXTENDS: "TUN reader cannot be stopped; `Stop()` silently leaks it" — the existing tests already hit the bug; they just never assert latency**
  The baseline says the tests "use `os.Pipe()` so they never model Android's blocking fd". That is not accurate. The tests pass `int(r.Fd())`, and `(*os.File).Fd()` switches the pipe to **blocking** mode. The dup in `newTunBridge` shares that file status, so `os.NewFile` does not register it with the netpoller. This is the same condition as Android. Measured in a /tmp copy: `TestLifecycleHappyPathPrepareAttachStop` takes **6.05 s** and `TestDetachTunKeepsPreparedClient` **6.04 s**. That is two `stopWaitTimeout` expiries each (re-attach/detach + Stop), and the whole package takes ~16 s. The suite goes green because `bridge.Stop()`'s timeout error is discarded, and tests that need pumps to exit close the *writer* end (EOF), which a real TUN never delivers. The regression test for the P0 fix is therefore one line: assert `Stop()`/`DetachTun()` return in < 500 ms in these two existing tests (and surface `bridge.Stop()`'s error). Effort S.
- **[L] [tests] core-engine/main_test.go:318-352 — `TestLiveMonitorLifecycle` tests a code path production never takes**
  It assigns `activeMonitor = netmon.NewStatic()` by hand and then checks that `UpdateNetworkState` "does not panic". Production never assigns a non-nil monitor (baseline M "Network-change injection is dead code"). The test name and comments ("triggers event injection") give false confidence that roaming re-evaluation is wired up, and nothing checks that an injected event reaches magicsock. Delete it, or rewrite it against the real wiring once that exists. Effort S.
- **[L] [tests/flakiness] ~30 fixed `time.Sleep`s used as synchronization** (`dns_test.go:567-763`, `nil_dial_test.go:43-277`, `ipv6_drop_test.go:68,89,480`, `netstack_proxy_test.go:490-866`, `lifecycle_test.go:285`). Positive assertions after a fixed sleep (for example "expected 2 dialed destinations" after 2x100 ms) can flake under `-race` on a loaded CI runner. Negative assertions after a sleep ("must not dial", "must not mark FAILED" after 200 ms) pass vacuously when the goroutine simply hasn't run yet. Replace them with channels signalled by the fake dialers or `require.Eventually`-style polling with a deadline, and for negative checks wait on a positive completion signal (for example the policy-rejection counter) before asserting nothing else happened. Effort M.
- **Missing tests for baseline bugs:** there is no response-after-client-FIN (TCP half-close) test, no memory/connection-cap test (`tcpMaxEstablished` x 2 x 256 KiB), and no Stop-latency assertion (above). There are also no tests for this file's findings: multi-error token determinism, `localhost.`, `N` absent/null, the egress retry backoff, and the `pendingDNS` AttachTun window.
- **OK —** The lifecycle tests cover cancel-via-Stop, a blocked prepare, a dead-reader attach, pump death → FAILED, concurrent Stop, and pendingDNS clearing, all with the real state machine and a fake client. Upstream parity is checked by feeding every valid fixture through `tailcat.ParseConnBlob`. Live tests are correctly gated behind `-tags liveprobe`, and the token is read from a private path rather than the repo.

Progress: finished Go test review
### Addendum (lifecycle)

- **[L] [policy] core-engine/lifecycle.go:123-219 — Token `exp` is checked only at `Prepare`; a running session outlives its token indefinitely**
  `IsConnectable()` is evaluated once. After that nothing re-checks `exp`: not in Go (no timer, `AttachTun` re-attach doesn't re-check), and not in Kotlin (`isExpired` is referenced only inside `TokenParser.kt`). With `START_STICKY`/always-on and no re-prepare on roam, a session can keep running days past `exp`. That is consistent with "the gateway enforces", but invariant 7 ("Reject ... expired tokens in both Android and native code") reads as if expiry is enforced on the client too.
  Fix: either document that `exp` is advisory at connect time, or have `AttachTun` reject expired tokens and schedule a native `markFailed` at `exp`. Effort S.
- **Note —** Every bridge leaked by the baseline H (blocked `readLoop`) keeps `b.token` (the raw token, including any PSK `q`) and the closed `client` reachable for the life of the process. That is one more reason to fix the non-blocking fd first. Go strings can't be zeroed, so there is no separate fix.

## Overall assessment (reviewer 1)

**Counts (new findings only, including EXTENDS items):** H 0, M 4, L 17.

- **M:** (1) `Stop()` has no overall bound: two sequential 3 s waits plus an unbounded `client.Close()`, unbounded waiters, and a main-thread `onDestroy` path. (2) The egress audit retries forever every ~3 s. (3) `build-aar.sh` writes the sourcehash before building, so a failed build yields a stale AAR that passes CI. (4) The existing tests already exercise the blocking-fd Stop bug (6 s tests) but never assert latency.
- **L:** token-parser nondeterminism, DERP host/shape validation gaps (`localhost.`, 0-node regions, duplicate region IDs), a new Kotlin/Go `null` divergence, non-deferred Stop cleanup, the DNS/MTU staging window, no resolver failover, the one-shot `ipv6Egress` latch, Phase 8 analyzer DNS masking, a stale `tailcat-cli`, gobind pin/required-method/staging-dir build issues, x/crypto bump, deprecated `ConnBlob` API, weak/flaky tests, and client-side `exp` enforced only at connect.

**Top 3 to fix:** (a) The Stop bound together with the baseline non-blocking-fd P0: one change fixes the 3-6 s Stop/Detach, the leaked readers and token retention, and adding `< 500 ms` assertions to the two existing tests gives the regression test. (b) Make `build-aar.sh` atomic and hash the tree last, so the "AAR never lags source" gate can't pass by accident. (c) Put exponential backoff on `egressProbeLoop` before the pinned Cloudflare leaf rotates (2026-12-21) and turns it into a permanent background retry loop for every user.

**Scope results:** `token.go`/`cbor_strict.go` are solid against crashes and resource abuse. Every length is bounds-checked, depth and count limits hold, and 90 s + 45 s of fuzzing found no panic. The remaining issues are about parity and defense in depth, not memory safety. The lifecycle state machine has no lock-order deadlock: `closeSession` is only called with `globalCore.mu` released, there is no lost wakeup on `stopWait` (it is closed under the mutex after `stopping=false`), and session IDs are monotonic. The real lifecycle problems are unbounded waits and non-deferred cleanup. `egress.go`/`ipv6_egress.go`/`protect*.go` and the pcap parser have no crash paths. `govulncheck` reports 0 reachable vulnerabilities, and `staticcheck` reports only deprecations and one unused test type. `go build ./cmd/...` builds all three tools, and the fixture generator is byte-reproducible. No live-token test was needed; nothing here required live confirmation.

Scratch work lives only in `/tmp/otc-r1/` (copied tree, fuzz and probe tests, tool output). The repo `git status --porcelain` is unchanged apart from this file (verified after govulncheck and staticcheck).
