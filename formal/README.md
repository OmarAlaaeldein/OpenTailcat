# Lifecycle verification

These are hand-written design models of the Android service and Go engine.
They do not prove the implementation or establish that the VPN is secure.
Results apply to each model's assumptions, bounds and enabled faults.

## Run the checks

Requires Java 21, Python 3.10+ and Bash. The script downloads sha256-pinned
`tla2tools.jar` v1.7.4 into ignored `formal/.tools/`.

```bash
python3 -m unittest discover -s formal -p 'test_*.py'
formal/check.sh --regressions     # baseline, counterexample replays, small models
formal/check.sh --extended        # larger scenarios and exploratory sampling
formal/check.sh                   # both sets
formal/check.sh --quick           # omit configs tagged TIER: slow
formal/check.sh --extended --shard 2/6
formal/check.sh progress-fixed network-loss-fixed
formal/check.sh --translate       # regenerate PlusCal -> TLA+ only
```

Do not run two copies in the same checkout: they share generated files and
scratch directories. Full exploration can take hours. `TLC_XMX` / `TLC_DIRECT`
set heap / fingerprint memory (defaults 5g / 2g); CI uses smaller limits.
`TLC_MIN_FREE_KB` defaults to 4 GiB and guards every run. Optional
`TLC_CHECK_TIMEOUT` is a hard failure deadline for non-sampling runs.

## Models and checked properties

| Model | Scope | Checks |
| --- | --- | --- |
| `Lifecycle.tla` | Full connect / warm attach / routed attach / reconnect / stop design across Kotlin and Go; PlusCal source plus generated TLA+ | Safety within individual exhaustive configurations; random simulation for larger configurations; historical counterexample replays |
| `Shutdown.tla` | One in-flight start, one Disconnect, one session and one TUN; no retries or younger start | Exhaustive safety and **liveness** checks of cancellation cleanup and call completion under explicit fairness assumptions |
| `NetworkCallback.tla` | Network loss racing `onVpnStopped` | Exhaustive check that a late callback cannot restore RECONNECTING after stop; old split read/write is rejected |

### Full lifecycle model

Actors: start jobs (serialized across service instances), inline shutdown,
posted finish, main-thread intent dispatch / destruction, asynchronous native
stop, poll loop and its post-lock tail, network restore, pump callbacks,
asynchronous session close, clock, environment and user.

Tracked state includes engine/session/bridge identity, duplicated TUN ownership,
shutdown epochs and start generations, job cancellation/predecessors, UI session
generation, health age, intent queue and `vpnWanted`. Native procedures split at
mutex release points. Kotlin lock regions are atomic actions, rather than
blocking locks. Cancellation is cooperative; JNI calls are not interrupted.

The safety checks include:

- Routed-interface installation requires a PREPARED session that previously
  proved live pumps on the warm TUN (`Act1_RoutedInstallNeedsProven`, now enabled
  in every passing full-model configuration). The state invariant alone is
  weaker: it is conditional on a current session and excludes pending adoption.
- RUNNING has a current, uncancelled session with the correct bridge ownership.
- An older start cannot stop a younger start's session.
- At quiescence (`Rest`), UI, user intent, native session and TUN ownership agree;
  Disconnect leaves no session/TUN, and a wanted Connect is not silently lost.
- Timed configurations check a bound on unjustified live UI claims. Health
  windows are scaled ticks, not a verification of Android wall-clock latency.

`Rest` means no job, native call, intent, posted runnable, callback, poll tail or
pending close is in flight. **These conditional invariants do not prove that
Rest is eventually reached.** A deadlock check does not establish termination
while another actor can keep taking steps. The full model's `Live3_*` through
`Live6_*` remain defined but are not checked. Its `SpecQ` has no fairness;
`Spec` has weak process fairness, but simulation is not a fairness proof.
The untimed pump-failure simulation uses `NEXT NextQ`, so a completely stopped
quiescent state can stutter; deadlock checking remains enabled outside `Rest`.

The clock records `Unjustified(hAge)` after assigning the new health age. Using
`NextH` again there would age health twice in the same PlusCal step.

Bounds are per `.cfg`: maximum starts, Disconnects, faults, sessions, bridges,
TUNs, jobs and instances. `BoundsOK` detects exhausted allocation bounds;
it does not lift the explicit user/fault budgets. Single-start cases can be
exhaustive, while the larger two-start cases remain sampled. A TUN is alive
while either Android or a bridge retains its descriptor. The model permits the
known gap without a TUN between reconnect attempts; Android lockdown is outside
its scope. It does not assert that routed installation and attachment have no
intervening window.

### Focused progress model

`progress-fixed` exhaustively checks these temporal properties in `Shutdown`:

- `LiveDisconnectObserved`: the fair Disconnect actually occurs, guarding
  against a vacuous conditional cleanup check.
- `LiveCleanup`: after cancellation, Android and bridge descriptors close,
  the session clears, native Stop ends, both callers finish and live UI clears.
- `LiveStopReturns`: each invoked Stop eventually returns, including a caller
  waiting behind another Stop.
- `LiveStartReturns`: the cancelled start eventually returns.

It includes the late-Prepare race: a job that passed its last Kotlin check can
create a session after shutdown's Stop has already returned. The job's
`abandonStaleStart` must stop that session. It also separates bridge duplication
from publication, so Stop can miss a locally owned duplicate that AttachTun
must close itself. Stop can return before background descriptor close completes.
A continuously enabled heartbeat keeps the system moving even if cleanup fails.

Assumptions: no younger start/retry; one session and TUN; fair scheduling of
continuously enabled operations; handshake/probe/error-cleanup calls eventually
return; background bridge close eventually runs once requested. Native timeout
and close implementation internals, upstream Client.Close hangs, lock blocking,
Android scheduling latency and wall-clock bounds are not proved. The warm/routed
rebind and multiple service instances remain the full model's responsibility.
This is a focused abstraction, not a mechanically checked refinement of that
model or the code.

Four fault switches demonstrate non-vacuity. `progress-mutant-abandon`, `-dup`
and `-close` must violate **LiveCleanup** by omitting late-session Stop, local
fd cleanup or a background-close request respectively. `progress-mutant-stop`
must violate **LiveStopReturns** by leaving the shared Stop gate closed. These
are temporal counterexamples, not merely terminal deadlocks.

## Implementation mapping and regressions

| Model action | Implementation |
| --- | --- |
| Full `P1..P5`, focused check/prepare/handshake | `core-engine/lifecycle.go`: `Prepare`, final identity/cancellation check |
| Full `A1..A8`, focused dup/publish | `AttachTun`: local bridge ownership, locked publication and stale-bridge `Stop` |
| Full `S1..SWait`, focused StopEnter/Return/Wait | `Stop`: `stopping`, `stopWait`, deferred release; `closeSession` timeout/background work |
| Full Abandon, focused cleanup | `TailcatVpnService.abandonStaleStart`: native Stop even after shutdown already stopped |
| WarmEst / RoutedEst / SetHandler | `establishAndAdopt`, CONNECTED publication under `interfaceLock` after epoch check |
| SHc..SH4, FNw | `shutdown`: scoped CAS, cancellation, TUN close, native stop; `finish` generation check and `stopSelf(lastStartId)` |
| MS / MS2 | `onStartCommand`, `handleStopIntent`, start generation and serialized launch |
| PR / PA / PRc / PRe, NR1..NR3 | `TunnelController`: session generation check, poll/listener publication and post-lock failure handler |
| Environment loss, focused Loss | NetworkMonitor listener NONE branch: `MutableStateFlow.compareAndSet(CONNECTED, RECONNECTING)` |

The former NONE branch read CONNECTED and wrote RECONNECTING separately; a stop
could intervene. The implementation now uses CAS, matching the atomic model.
`network-loss-mutant` restores the split transition and violates
`NoResurrection`; `network-loss-fixed` explores both callback/stop orderings.

`ServiceStartIdInstrumentedTest` uses a debug-only ordinary Service to test the
Android platform assumption behind PendingStart: an older `stopSelf(startId)`
preserves both queued and delivered newer starts, while the latest id stops the
service. This follows the [Android Service contract](https://developer.android.com/reference/android/app/Service#stopSelfResult(int)).
The probe never runs a VPN or needs a gateway. CI's emulator smoke runs it;
compiling the APK alone is not platform validation. Testing the actual VPN's
queued-start and failed-start/quick-Connect paths on a device remains outstanding.

## Historical counterexamples

Full-model mutation configs restore previous behaviour. Saved schedules in
`replays/*.json` now replace time-budget searches for simulated mutation
regressions. They contain only action names and process ids. `replay.py` builds
an ignored wrapper that calls the **current model's actions and guards** in
that order, exhaustively exploring nondeterministic choices along the schedule.
It never injects saved states. A schedule incompatible with the current model
cannot count as a reproduced violation. Deadlock checking is disabled only for
these restricted schedules: an incompatible branch is not a deadlock of the
unrestricted design. A replay passes only if the specifically named property
is actually violated with a TLC model-error exit status.

Replays prove that a counterexample exists in the mutant. They do not establish
absence of other bugs in the fixed model, which is checked separately.

| Mutation | Reintroduced behaviour |
| --- | --- |
| F1 | Old warm-bridge callback fails the newer routed bridge |
| F2 | Cancelled start leaves a session created after shutdown's Stop |
| F3a | Interface establishment/adoption races shutdown |
| F3b (redundant scenario) | Detach without a session returns success |
| F4 | Stale start changes current UI/intent/session state |
| F5 | CONNECTED publication races shutdown |
| F6 | Stale poll/network-restore snapshot publishes after session end |
| F7 | Delayed STOP overwrites a newer Connect's vpnWanted |
| N1 / N1b | Early DISCONNECTED / unscoped STOP or finish loses Connect |
| N2 | Catch-path stale check and effects occur in separate lock regions |
| Serial | Jobs run without joining all earlier starts |
| Identity | Attach omits native session-identity checks |

Some historical configurations deliberately restore **multiple** old behaviours;
read their `Mutants` constants. In particular N1's original witness also restores
N1b. The old `mutant-dup-guard` config wrongly expected Dup alone to reproduce a
pre-N1 failure against the final fixed model. It is replaced by
`mutant-n1-old-dup-guard`: explicitly restore N1 and Dup, then reproduce the lost
Connect. This makes no claim that Dup alone violates the fixed design.

Schedules were extracted from commit 4473b37's CI counterexamples (run
43328682874), with a local F2 witness and fresh identity witness. F7, network
restore and the N1+Dup schedules were composed from the same action definitions
and validated by TLC. To extract another recorded counterexample:

```bash
python3 formal/replay.py --extract path/to/tlc.log --output formal/replays/name.json
```

Add `\* REPLAY: replays/name.json` to the corresponding config and an exact
`EXPECT: violation` property. A new replay does not replace the fixed-design
checks or widen their claimed scope.

## Result handling and CI

- **CHECKED** requires exit 0 and TLC's positive completed-model-check message.
- **expected counterexample** requires the named property and a TLC safety,
  liveness or deadlock exit code; crashes cannot satisfy this expectation.
- **SAMPLED** means a depth-300 random walk reached the runner's own sampling
  deadline with positive progress and no error. It is evidence, not exhaustive
  checking. The JVM records its random seed in the log.
- SIGKILL/exit 137, external termination, OOM, exceptions, disk guards, absent
  progress, forced kills and incomplete searches fail. No generic signal code
  is converted into success. OffHeapDiskFPSet avoids a known TLC statistics
  exception, but any exception still fails rather than being ignored.

`test_tlc_runner.py` exercises real subprocess termination/resource handling as
well as classification; `test_replay.py` checks extraction and wrapper generation.
CI checks these, translation freshness and the fast regressions first, then
runs the extended suite in six shards. Logs are uploaded even on failure.
The authoritative result is the workflow for the exact commit, not an earlier
run or the EXPECT comments in configs.

Local validation of this revision (2026-11-12): all 22 fast configurations
behaved as expected with CI's 2g heap / 256m direct-memory limits; the 11 harness
tests passed, and empty selections, empty shards and all-skipped runs correctly
failed. The timed-pump and ui-races 180-second samples found no violation.
Both Android flavors passed 155 JVM tests each and lint; debug and instrumented
test APK builds passed. The full extended suite was not rerun locally. No
Android emulator was available to execute the new platform probe locally.

## Remaining scope limits

No packet/data-plane model, cryptographic proof, DNS/MTU/UDP-table verification,
token-parser proof, split-tunnel/lockdown proof, fd-number reuse, process-death
model, blocking-lock or JVM-memory-model proof. Full-model temporal properties
and exhaustive coverage of larger restart scenarios remain open. Telemetry
read exceptions and several Android callback/rejection paths are abstracted.
Physical ARM64 dual-capture leak acceptance and actual VPN lifecycle/device
validation remain separate requirements; see `AGENTS.md`.
