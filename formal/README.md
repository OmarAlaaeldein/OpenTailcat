# Lifecycle verification

Hand-written TLA+ design models of the Android VPN service and Go engine lifecycle. These check concurrency design properties within each model's assumptions and bounds; they are not formal proofs of the Kotlin/Go implementation or VPN leak resistance.

## Running TLC

Requires Java 21, Python 3.10+, and Bash. `check.sh` downloads SHA-256-pinned `tla2tools.jar` v1.7.4 into the git-ignored `formal/.tools/` directory.

```bash
python3 -m unittest discover -s formal -p 'test_*.py'
formal/check.sh --regressions     # baseline, counterexample replays, focused models
formal/check.sh --extended        # larger scenarios and random simulation
formal/check.sh                   # both
formal/check.sh --quick           # skip configs tagged TIER: slow
formal/check.sh --extended --shard 2/6
formal/check.sh progress-fixed network-loss-fixed
formal/check.sh --translate       # regenerate PlusCal -> TLA+ only
```

Do not run two instances in the same checkout because they share generated files and scratch directories. Full exploration can take hours. `TLC_XMX` and `TLC_DIRECT` control heap and fingerprint memory (defaults `5g` / `2g`; CI uses smaller limits), `TLC_MIN_FREE_KB` defaults to 4 GiB, and `TLC_CHECK_TIMEOUT` sets a hard deadline for non-sampling runs.

## Models

| Model | Scope | Checked properties |
| --- | --- | --- |
| `Lifecycle.tla` | Full connect, warm attach, routed attach, reconnect, and stop flow across Kotlin and Go (PlusCal + generated TLA+) | Exhaustive safety in smaller configs, random simulation in larger configs, and historical counterexample replays |
| `Shutdown.tla` | Single in-flight start, single Disconnect, one session, and one TUN (no retries or younger start) | Exhaustive safety and liveness for cancellation cleanup and call completion under explicit fairness assumptions |
| `NetworkCallback.tla` | Network-loss callback racing `onVpnStopped` | Exhaustive check that a late callback cannot set `RECONNECTING` after stop (reproduces failure for the old split read/write) |

### Full lifecycle model (`Lifecycle.tla`)

Models start jobs (serialized across service instances), inline shutdown, posted `finish()`, main-thread intent dispatch and destruction, asynchronous native stop, the stats poll loop and post-lock tail, network restore, pump callbacks, async session close, clock ticks, environment, and user actions.

Tracked state covers engine/session/bridge identity, duplicated TUN ownership, shutdown epochs and start generations, job cancellation/predecessors, UI session generation, health age, intent queue, and `vpnWanted`. Native calls split at mutex release points; Kotlin synchronized blocks are modeled as atomic actions; cancellation is cooperative (blocking JNI calls are not interrupted).

Key safety checks:

- Installing default routes requires a `PREPARED` session that proved live pumps on the warm TUN (`Act1_RoutedInstallNeedsProven`, enabled in all passing full-model configs).
- `RUNNING` requires a current, uncancelled session with matching bridge ownership.
- An older start job can never stop a younger start's session.
- At quiescence (`Rest`, when no jobs, calls, intents, runnables, callbacks, or closes are active), UI state, `vpnWanted`, native session, and TUN ownership agree; Disconnect leaves no session or TUN, and a wanted Connect is not lost.
- Timed configs bound unjustified live UI claims using scaled ticks (not real Android wall-clock latency).

Note that conditional invariants on `Rest` do not prove `Rest` is eventually reached. Full-model temporal properties (`Live3_*`..`Live6_*`) are defined but unchecked, and larger two-start restart configs use random simulation rather than exhaustive search. Bounds per `.cfg` cap starts, disconnects, faults, sessions, bridges, TUNs, jobs, and instances (`BoundsOK` catches exhausted allocation bounds). The model permits the known no-TUN gap between reconnect attempts (Always-on lockdown is outside its scope).

### Focused progress model (`Shutdown.tla`)

`progress-fixed` exhaustively checks four temporal properties under fair scheduling and bounded-call assumptions (one session/TUN, no retries or newer starts, background close eventually runs once requested):

- `LiveDisconnectObserved`: the Disconnect action actually fires (prevents vacuous cleanup checks).
- `LiveCleanup`: after cancellation, Android and bridge fds close, the session clears, native Stop finishes, both callers return, and live UI clears.
- `LiveStopReturns`: every invoked Stop eventually returns, including callers waiting behind another Stop.
- `LiveStartReturns`: the cancelled start eventually returns.

This covers both the late-`Prepare` race (requiring `abandonStaleStart` to stop a session created after shutdown's Stop returned) and separate bridge fd duplication vs. publication. Four mutant configs (`progress-mutant-abandon`, `-dup`, `-close`, `-stop`) verify temporal violations while a heartbeat keeps running.

## Code mapping

| Model action | Code |
| --- | --- |
| `P1..P5`, check/prepare/handshake | `core-engine/lifecycle.go`: `Prepare` and final identity/cancellation check |
| `A1..A8`, dup/publish | `core-engine/lifecycle.go`: `AttachTun` local bridge ownership, locked publish, stale-bridge `Stop` |
| `S1..SWait`, StopEnter/Return/Wait | `core-engine/lifecycle.go`: `Stop` (`stopping`, `stopWait`, deferred release) and `closeSession` |
| `Abandon` | `TailcatVpnService.abandonStaleStart` |
| `WarmEst` / `RoutedEst` / `SetHandler` | `TailcatVpnService.establishAndAdopt` and `CONNECTED` publish under `interfaceLock` |
| `SHc..SH4`, `FNw` | `TailcatVpnService.shutdown` scoped CAS, TUN close, native stop, `finish` generation check, `stopSelf(lastStartId)` |
| `MS` / `MS2` | `TailcatVpnService.onStartCommand`, `handleStopIntent`, `startGeneration`, `launchSerializedStart` |
| `PR` / `PA` / `PRc` / `PRe`, `NR1..NR3` | `TunnelController`: `sessionGeneration` checks, poll/listener publication, failure handler |
| Environment loss (`NetworkCallback.tla`) | `TunnelController` network listener `NetworkType.NONE` branch (`compareAndSet(CONNECTED, RECONNECTING)`) |

`ServiceStartIdInstrumentedTest` validates the Android `stopSelf(startId)` assumption using a debug-only non-VPN service (`StartIdProbeService`) in CI's emulator smoke suite. Full VPN flows for queued starts and failed-start/quick-Connect still need device verification (see [AGENTS.md](../AGENTS.md#remaining-work)).

## Historical counterexample replays

Mutation configs restore previously fixed bugs. Saved action schedules in `replays/*.json` (action names and process IDs only, with no injected states) let `replay.py` run the current model's actual guards along each schedule and verify TLC still finds the named property violation.

| Mutation | Reintroduced bug |
| --- | --- |
| `F1` | Warm-bridge pump callback fails the newer routed bridge |
| `F2` | Cancelled start leaks a session created after shutdown's Stop |
| `F3a` | Interface establishment/adoption races shutdown |
| `F3b` (redundant) | `DetachTun` without a session returns success |
| `F4` | Stale start mutates current UI, `vpnWanted`, or session state |
| `F5` | `CONNECTED` publication races shutdown |
| `F6` | Stale poll or network-restore snapshot publishes after session end |
| `F7` | Delayed STOP overwrites a newer Connect's `vpnWanted` |
| `N1` / `N1b` | Early `DISCONNECTED` or unscoped STOP/`finish` drops a new Connect |
| `N2` | Catch-path staleness check and failure handler run in separate lock blocks |
| `Serial` | Start jobs run without joining earlier starts |
| `Identity` | `AttachTun` skips native session-identity checks |

Some historical configs intentionally enable multiple mutants together (check their `Mutants` constants; e.g., `mutant-n1-old-dup-guard` pairs `N1` with the old duplicate guard). Extract new schedules from a TLC counterexample log with:

```bash
python3 formal/replay.py --extract path/to/tlc.log --output formal/replays/name.json
```

Then reference it via `\* REPLAY: replays/name.json` and an exact `EXPECT: violation` line in the config.

## Result classification and CI

- **CHECKED**: TLC exits 0 with its completed-model-check message.
- **expected counterexample**: TLC reports the exact named property violation with a safety/liveness/deadlock exit code.
- **SAMPLED**: depth-300 random simulation reached its time limit with positive progress and no errors (evidence, not exhaustive verification; random seed logged).
- Crashes, OOMs, SIGKILL/137, exceptions, low-disk aborts, zero progress, or incomplete non-sampling runs all fail the gate.

`formal.yml` runs `test_*.py`, PlusCal translation freshness, and `--regressions`, followed by 6 shards of `--extended`.

## Scope limits

Out of scope for these models: packet data plane, cryptography, DNS/MTU/UDP-table behavior, token parsing, split tunneling, Always-on lockdown, fd-number reuse, process death, lock blocking, JVM memory model, full-model liveness, and exhaustive exploration of large restart scenarios.
