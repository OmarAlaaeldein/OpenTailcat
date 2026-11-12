# Lifecycle model (TLA+ / PlusCal, checked with TLC)

Design-level model of the **connect / attach / reconnect / stop** lifecycle that
spans the Kotlin service and the Go engine. It is **not a proof of the code**:
it checks a hand-written abstraction of the concurrency, and every claim below
is scoped to what TLC actually explored (exhaustively, or by random-walk
simulation where it says so).

The model's baseline is the **fixed code**: findings F1–F7 of the first round
were fixed in `core-engine/lifecycle.go`, `TailcatVpnService.kt` and
`TunnelController.kt`, and the model was brought in line with those fixes. Each
old behaviour is still available as a named *mutant* switch, so a "pass" can be
shown not to be vacuous. The second round found three new problems (N1, N1b,
N2); all three were then fixed in the code and verified the same way (see
"Findings").

```bash
formal/check.sh                 # every config in formal/configs/ (2-3 h on 6 cores)
formal/check.sh --quick         # skips the configs tagged `TIER: slow`
formal/check.sh --shard 2/6     # one of 6 balanced shards (what CI runs)
formal/check.sh baseline-connect fixed-f2-prepare-after-shutdown   # a subset
formal/check.sh --translate     # only regenerate the PlusCal -> TLA+ block
```

`check.sh` downloads `tla2tools.jar` **v1.7.4** into `formal/.tools/` (git-ignored),
verifies its sha256 (`936a2620…050e88`), regenerates the TLA+ from the PlusCal,
runs TLC on each config, and exits non-zero if a config does not behave as its
`\* EXPECT:` line says. Configs marked `violation` document findings and
mutation checks; they pass when TLC *does* report the violation. Java 21 is
enough; nothing is written outside `formal/`. Safety nets: TLC's on-disk state
queue is deleted after every config and a watchdog kills TLC when fewer than
4 GiB are free (`TLC_MIN_FREE_KB` overrides). `TLC_XMX` / `TLC_DIRECT` set the
heap and off-heap fingerprint memory (defaults 5g / 2g).

## Files

| File | Purpose |
| --- | --- |
| `Lifecycle.tla` | The PlusCal algorithm, the generated TLA+ (between the `BEGIN/END TRANSLATION` markers) and the properties. Edit the PlusCal, never the generated block. |
| `configs/*.cfg` | One TLC config per scenario: constants, invariants, `EXPECT`, optional `MODE: simulate N` and `TIER: slow`. |
| `check.sh` | Pinned download, translation, run-all / `--quick`, exit code, disk guard. |
| `.tools/` | Jar, TLC state, logs (ignored). |

## What is modelled

Concurrent actors (PlusCal processes and procedures):

| Process | Represents |
| --- | --- |
| `job` | one start / reconnect coroutine: `launchSerializedStart` (join of `lastStartJob`) + `startWithRetry` + `establishAndStartEngine` |
| `Shutdown` (procedure) | `shutdown(epoch?)`: optional epoch check + CAS + `shutdownEpoch` bump in one `interfaceLock` step, handler null, `startJob.cancel()`, TUN close, native stop (sync) or `pendingNativeStop` (main thread), `finish`. Called **inline** by whoever invokes it: a job (`abandonStaleStart`, `failStart`, both scoped to the job's epoch), the `sd` coroutine, or the main thread (`onDestroy`). |
| `sd` | `serviceScope.launch { shutdown() }` of a STOP intent / `onRevoke` |
| `fin` | the `Handler.post(finish)`: skipped when a START was accepted after the CAS; else `onVpnStopped` (UI -> DISCONNECTED) and `stopSelf(lastStartId)`, which leaves the instance up while a start intent is queued (`PendingStart`) and otherwise, with `EnOnDestroy`, is followed by the `onDestroy()` the system delivers |
| `ns` | the async native stop `shutdown()` launches when called on the main thread (`pendingNativeStop`) |
| `main` | `onStartCommand` (the intent queue: USER / AUTO / AO / STOP / REVOKE / DESTROY) and `onDestroy`; the START path is two steps (`shuttingDown.set(false)`, then epoch read + launch) |
| `poll` | `TunnelController.onEngineConnected`'s 1 s poll loop, including the part of `onSessionFailed` **after** the `stateLock` region (`handler(cause)` / `reconnectAfterFailure`) |
| `ncb` | the network-restore coroutine in the `NetworkMonitor` listener |
| `bpump` | one bridge's required pumps dying and the `onPumpDead` -> `markFailed(sess, bridge, err)` callback |
| `gc` | `markFailed`'s `go closeSession(sess)` |
| `clk` | the 1 s clock (health aging, poll cadence) |
| `env` | network loss / return, gateway loss / return, OS destroying the service |
| `user` | Connect tap, restore on resume, Always-on start, Disconnect tap, `onRevoke` |

Go engine calls are PlusCal procedures (`Prepare`, `AttachTun`, `Stop`), split
at every point where `globalCore.mu` is released. `DisarmPumps` and `DetachTun`
are single atomic steps.

State that is kept: engine state, current session identity (and which job
created it), per-session cancelled / client / bridge, per-bridge state and the
TUN it holds a dup'd fd of, callback armed / in flight, health age, gateway
freshness, TUN objects, `vpnInterface`, `shuttingDown`, **`shutdownEpoch`**,
`startJob`, **`lastStartJob`**, per-job epoch / predecessor / cancellation,
`vpnWanted`, UI state, `sessLive`, **`sessionGeneration`**,
`sessionFailureHandler`, poll state, the intent queue.

### Mutants

`Mutants` (a set constant; `{}` = the fixed code) switches OLD behaviour back on:

| Element | Old behaviour restored |
| --- | --- |
| `"F1"` | the pump callback checks `live` in one lock region and `markFailed` (no bridge check) runs in another |
| `"F2"` | no re-check before `prepare()`; the cancel path only calls `shutdown()` (no native `stop()`) |
| `"F3a"` | `establish()` and `adoptInterface()` are separate steps and `establish()` is not preceded by a staleness check |
| `"F3b"` | `DetachTun` returns nil when there is no session |
| `"F4"` | a plain exception in a cancelled / stale job goes through `onVpnStartFailed` / `resetForRetry`; the reconnect body has no `isStale` early return |
| `"F5"` | `sessionFailureHandler` / `onEngineConnected` are published without a re-check |
| `"F6"` | poll iteration and network-restore write without the `sessionGeneration` check |
| `"F7"` | the STOP intent writes `vpnWanted = false` |
| `"N1"` | `onVpnStartFailed` sets the UI to DISCONNECTED before `shutdown()` has finished |
| `"N1b"` | a STOP intent always launches an unscoped `shutdown()`; `finish()` always calls `stopSelf()` |
| `"N2"` | the catch block checks staleness once, calls `onVpnStartFailed` in a later step, then an unscoped `shutdown()`; `abandonStaleStart` checks the epoch and calls `shutdown()` in separate steps |
| `"Serial"` | start jobs join nothing (a reconnect joins only `startJob`) |
| `"Dup"` | the old duplicate-start guard (no `!shuttingDown`) |
| `"Epoch"` | `isStale = shuttingDown` only |

The older switches `GuardJoinNS`, `GuardIdentity`, `GuardMarkFailed` are kept
(production = `TRUE`); `SplitCb` makes the pump callback its own step.

### Abstraction choices

* **No data plane.** No bytes, DNS, MTU, UDP table, telemetry values. Health is
  an age counter; the gateway is `ok / stale / lost`.
* **Time is a 1 s tick, scaled.** `FreshMax = 1` tick stands for the 15 s
  health window, `StalePolls = 2` for the 3 stale polls, `lost` after two
  silent ticks for `GATEWAY_LOSS_SEC = 60`. A poll runs exactly once per tick.
  `Timed = FALSE` removes the clock for scenarios that do not need it.
* **Atomicity follows the lock regions.** Each Go call is split where
  `globalCore.mu` is dropped. `interfaceLock` regions are modelled as single
  atomic steps (the CAS + epoch bump in `shutdown()`, `establishAndAdopt`, the
  CONNECTED publish); `stateLock` regions likewise (poll decision,
  `endSession`). A lock is never modelled as *blocking*: a thread waiting for
  `interfaceLock` while `establish()` runs is not represented (see "Not
  covered").
* **`shutdown()` runs inline** in its caller, so a job is not "done" until its
  own `shutdown()` returned. `finish` is posted to the main looper and cannot
  run inside another main-thread message.
* **Cancellation is cooperative.** A cancelled job only notices at
  `ensureActive`, `join`, `delay` and `checkNotShuttingDown`; JNI calls are
  never interrupted.
* **A TUN stays alive while any Go bridge holds its dup'd fd**, so "TUN closed"
  means the Android fd *and* every bridge fd are gone (`TunAlive`).
* **One live service instance at a time**; `stopSelf()` takes the instance out
  of service at `finish` (a later START creates a new one). A zombie job keeps
  running after its instance died (`serviceScope` is never cancelled).
* **Faults are budgeted** (`MaxFaults`) and each class can be switched off
  (`EnPump`, `EnPrepFail`, `EnAttachFail`, `EnNet`, `EnGw`, `EnDestroy`). The
  environment actions are not fair; everything else is weakly fair.
* **Bound-hit detector.** `BoundsOK` is an invariant: if a finite bound (jobs,
  instances, sessions, bridges, TUNs) is exhausted the model flags it instead
  of silently cutting behaviour. (Two configs needed larger bounds because of
  it: a late STOP intent legitimately creates a second service instance.)
* **PlusCal assignments within a label are sequential**: `gen := gen + 1;
  pollGen := gen` gives `pollGen = gen + 1`. An early version of this model
  wrote `pollGen := gen + 1` and silently disabled every poll iteration; it was
  caught by `timed-pump`.
* **Known, expected behaviour (not a violation):** between reconnect attempts
  there is no TUN (`resetForRetry` closes it before the next attempt); traffic
  then uses the underlying network unless Always-on lockdown is on.

### Action -> code mapping

| TLA+ label(s) | Code |
| --- | --- |
| `P1 P2 P3 P5` (Prepare) | `core-engine/lifecycle.go` `Prepare` |
| `A1 … A8` (AttachTun) | `lifecycle.go` `AttachTun` |
| `S1 S2 S3 SWait` (Stop) | `lifecycle.go` `Stop` |
| `Disarm`, `Detach` | `DisarmPumps`, `DetachTun` (errors when there is no session) |
| `bpump` | `bridge.go` `reportPumpDead`; the closure installed in `AttachTun`; `markFailed(sess, bridge, err)` (`lifecycle.go:85`) |
| `gc` | `markFailed`: `go closeSession(sess)` |
| `MS MS2` | `TailcatVpnService.onStartCommand` (`:55`): STOP / duplicate-start guard (`:66`) / `shuttingDown.set(false)` (`:88`) / epoch read + `launchSerializedStart` (`:110`) |
| `JJoin RChk` | `launchSerializedStart` (`:243`): `previous?.join()`; the `isStale(epoch)` early return of the reconnect body (`:298`) |
| `RReset RNote JoinNS` | `resetForRetry`, notification, `pendingNativeStop?.join()` |
| `Loop PrepCall PrepRet` | `establishAndStartEngine`: the pre-`prepare` check (`:342`), `prepare`, post-`prepare` `ensureActive` + `checkNotShuttingDown(epoch)` |
| `WarmEst RoutedEst RoutedClear` | `establishAndAdopt` (`:422`) for the warm / routed TUN; `warm.close()` |
| `WarmAttach … Sweep1 Disarm Detach` | `attachLive`, protect sweep, `disarmPumps`, `detachTun` |
| `SetHandler` | `synchronized(interfaceLock) { checkNotShuttingDown(epoch); sessionFailureHandler = …; onEngineConnected }` |
| `Catch Abandon AbShut` | `startWithRetry`'s catch: `abandonStaleStart(epoch)` (`:206`) = `stop()` then `shutdown(epoch)` |
| `CatchPerm CatchDecide CatchShut` | `permanent` evaluation (`prepare(this)`, `:181`), `failStart(epoch, message)` (`:221`): staleness re-check + `onVpnStartFailed(serviceStopping = true)` under `interfaceLock`, then `shutdown(epoch)` |
| `RetryReset RetryNotify RetryDelay*` | `resetForRetry`, `onStartRetrying`, `delay(waitMs)` |
| `SHc SH1 … SH4` (`Shutdown`) | `shutdown(epoch?)` (`:527`): epoch check + CAS + epoch bump under `interfaceLock`, handler null, `startJob?.cancel()` (`:538`), TUN close, native stop sync / async |
| `FNw` | `finish` (`:550`): `onVpnStopped`, `stopSelf()` (`:553`) and the `onDestroy()` (`:569`) that follows |
| `PR PA PRc PRe` | `TunnelController` poll loop, `ifSessionCurrent`, `onSessionFailed(generation, cause)` (`:333`), `reconnectAfterFailure` (`:290`) |
| `NR1 NR2 NR3` | `TunnelController` network listener restore path |
| `Tk` | `rateCalcLoop` heartbeat + `delay(METRICS_POLL_INTERVAL_MS)` cadence |

## Properties checked

| # | Property | Where | How |
| --- | --- | --- | --- |
| 1 | Default routes ⇒ the engine session proved live pumps. | `Inv1_RoutesNeedProvenSession`, `Act1_RoutedInstallNeedsProven` | invariant / action property |
| 2 | UI `CONNECTED`/`DEGRADED` ⇒ native `RUNNING`, fresh health, same session, with bounded staleness (`BadBound`). | `Inv2_UiLiveBounded` | invariant (timed configs) |
| 3 | After a pump / health failure the TUN is eventually closed. | `Inv3_RestNoDeadTun` | safety form |
| 4 | No deadlock; `Stop` terminates; a start does not hang. | TLC deadlock check with `NEXT NextQ` (quiescent states stutter, so any other terminal state is a stuck state); `Inv7_RestNotConnecting` | see "Not covered" for the temporal forms |
| 5 | A stale session never sets RUNNING/CONNECTED or publishes its bridge. | `Inv5_*` | invariant |
| 6 | After Disconnect eventually no TUN, no engine session, UI DISCONNECTED. | `Inv6_RestNoTun`, `Inv6_RestEngineStopped`, `Inv6_RestUiDisconnected` | "when nothing is in flight and the user does not want the VPN, nothing is left" |
| 7 | UI / intent / engine consistency at rest. `Inv7_RestUiLiveNeedsSession` (F6), `Inv7_RestUiLiveNeedsWanted` (F4 / F7), `Inv7_RestUiLiveNeedsEngine` (no poll), `Inv7_RestNotConnecting` (a start never hangs), `Inv7_RestWantedNotDisconnected` (**a Connect is never silently dropped**). | `Inv7_*` | invariant |
| 8 | A stale (older) start job never stops or preempts the session of a younger one. | `Safe7_NoStopOfNewerSession` | history flag |
| 9 | No service instance lingers idle: at rest every live instance runs a start it accepted and has not shut down (N1b's `stopSelf(startId)` can keep an instance up). | `Inv8_RestNoIdleInstance` | invariant |

`Rest` = no job / Go call / queued intent / posted runnable / poll-thread tail /
pending request in flight.

## Results

| Config | Mode | Tier | EXPECT |
| --- | --- | --- | --- |
| `baseline-connect` | exhaustive | quick | pass |
| `disconnect-restart` | simulate 240 | quick | pass |
| `fixed-f1-markfailed-bridge` | exhaustive | slow | pass |
| `fixed-f2-prepare-after-shutdown` | exhaustive | slow | pass |
| `fixed-f3-route-install-during-shutdown` | exhaustive | slow | pass |
| `fixed-f4-stale-start-job` | simulate 240 | quick | pass |
| `fixed-f5-connected-after-shutdown` | exhaustive | slow | pass |
| `fixed-f6-network-restore` | exhaustive | slow | pass |
| `fixed-f6-poll-races` | exhaustive | slow | pass |
| `fixed-f7-stale-stop-intent` | simulate 240 | quick | pass |
| `fixed-n1-connect-after-failed-start` | simulate 240 | quick | pass |
| `fixed-n1b-stale-stop-intent` | simulate 300 | quick | pass |
| `mutant-dup-guard` | simulate 300 | quick | violation: `Inv7_RestWantedNotDisconnected` |
| `mutant-f1-markfailed-bridge` | simulate 240 | quick | violation: `Inv5_RunningOwnsBridge` |
| `mutant-f2-prepare-after-shutdown` | simulate 240 | quick | violation: `Inv6_RestEngineStopped` |
| `mutant-f3a-nonatomic-establish` | simulate 240 | quick | violation: `action Act1_RoutedInstallNeedsProven` |
| `mutant-f4-stale-start-job` | simulate 300 | quick | violation: `[A-Za-z0-9_]+` |
| `mutant-f5-connected-after-shutdown` | exhaustive | slow | violation: `Inv[67]_Rest[A-Za-z]+` |
| `mutant-f6-network-restore` | exhaustive | slow | violation: `Inv[67]_Rest[A-Za-z]+` |
| `mutant-f6-poll-races` | exhaustive | slow | violation: `Inv[67]_Rest[A-Za-z]+` |
| `mutant-f7-stale-stop-intent` | simulate 300 | quick | violation: `[A-Za-z0-9_]+` |
| `mutant-n1-connect-lost-during-shutdown` | simulate 300 | quick | violation: `Inv7_RestWantedNotDisconnected` |
| `mutant-n1b-stale-stop-intent` | simulate 3600, seed 22 | slow | violation: `Inv7_RestWantedNotDisconnected` |
| `mutant-n2-catch-toctou` | simulate 300 | quick | violation: `[A-Za-z0-9_]+` |
| `mutant-no-identity-check` | simulate 240 | quick | violation: `Inv5_[A-Za-z]+` |
| `mutant-serial-start-jobs` | simulate 300 | quick | violation: `Safe7_NoStopOfNewerSession` |
| `pump-failure` | simulate 180 | quick | pass |
| `redundant-epoch` | simulate 240 | quick | pass |
| `redundant-f3b-detach-nil` | exhaustive | slow | pass |
| `timed-pump` | simulate 180 | quick | pass |
| `ui-races` | simulate 180 | quick | pass |

CI (`.github/workflows/formal.yml`) runs every config on each push or pull
request that touches `formal/`, the service package or `core-engine/lifecycle.go`,
in six parallel shards, and uploads the TLC logs. It also fails when the
committed TLA+ translation is out of date.

`simulate N` = random-walk simulation of N seconds: **evidence, not a proof**;
for a `violation` config it means the walk found the violation (a miss would
fail `check.sh`), for a `pass` config it means none was found. Everything
marked exhaustive explored the complete reachable state space of its bounds,
with the invariants and the deadlock check listed in its `.cfg`.

## Findings

### Round 1 (F1–F7): fixed — verified

| # | Fix (in the code) | Verified by (EXPECT pass) | Old bug reproduced by (EXPECT violation) |
| --- | --- | --- | --- |
| F1 | `markFailed(sess, bridge, err)` requires `sess.bridge == bridge` under the same lock; callback calls it directly (`lifecycle.go:85`, `:339`) | `fixed-f1-markfailed-bridge` (exhaustive, callback split into its own step) | `mutant-f1-markfailed-bridge` → `Inv5_RunningOwnsBridge` |
| F2 | re-check before `prepare`, `abandonStaleStart` stops the engine on cancel (`:258`, `:204`) | `fixed-f2-prepare-after-shutdown` (exhaustive; `Inv6_RestEngineStopped`, `Inv6_RestNoTun`, `Inv6_RestUiDisconnected`) | `mutant-f2-prepare-after-shutdown` → `Inv6_RestEngineStopped` |
| F3 | `establishAndAdopt` under `interfaceLock` after `checkNotShuttingDown`; `DetachTun` errors without a session | `fixed-f3-route-install-during-shutdown` (exhaustive, action property `Act1_RoutedInstallNeedsProven`) | `mutant-f3a-nonatomic-establish` → `Act1_…`. **`redundant-f3b-detach-nil`**: the `DetachTun` change alone is redundant in the model (every path with no session has set `shuttingDown` first, and `establishAndAdopt` refuses then) — defense in depth |
| F4 | `isStale`/`isActive` in the catch → `abandonStaleStart`; `shutdownEpoch`; `launchSerializedStart` | `fixed-f4-stale-start-job` (**simulation**) | `mutant-f4-stale-start-job`, `mutant-serial-start-jobs` (→ `Safe7_NoStopOfNewerSession`). **`redundant-epoch`**: removing the epoch changes nothing observable in the modelled scenarios (it only matters in the N1 window, where the Connect is lost anyway) |
| F5 | publish under `synchronized(interfaceLock) { checkNotShuttingDown(epoch); … }` | `fixed-f5-connected-after-shutdown` (exhaustive) | `mutant-f5-connected-after-shutdown` → `Inv6_RestUiDisconnected` |
| F6 | `stateLock` + `sessionGeneration`; `ifSessionCurrent`; `onSessionFailed(generation, …)` | `fixed-f6-poll-races`, `fixed-f6-network-restore` (exhaustive, `UiRaces = TRUE`) | `mutant-f6-poll-races`, `mutant-f6-network-restore` → `Inv6_RestUiDisconnected` / `Inv7_*` |
| F7 | the STOP intent no longer writes `vpnWanted` (`:57-60`) | `fixed-f7-stale-stop-intent` (**simulation**) | `mutant-f7-stale-stop-intent` |

Caveats that matter for reading the table: F4 and F7 need two starts, which is
far beyond exhaustive search (the first N1 search reached 7 M distinct states at
depth 29 with the violation at depth ~60), so their "fixed" configs are random
walks; and they only pass with the N2 fix, because N2 masks them
(`mutant-n2-catch-toctou` is the same scenario without it). The duplicate-start-guard change
(`!shuttingDown`) is not observable either: `mutant-dup-guard` (old guard)
produces the same violation as the new guard (finding N1).

### Round 2 (N1, N1b, N2): fixed — verified

| # | Fix (in the code) | Verified by (EXPECT pass) | Old bug reproduced by (EXPECT violation) |
| --- | --- | --- | --- |
| N1 | `onVpnStartFailed(message, serviceStopping = true)` keeps the UI state; only `finish()`'s `onVpnStopped` sets DISCONNECTED, so Connect (offered only in DISCONNECTED) is not offered while that `shutdown()` runs (`TunnelController.kt:373`) | `fixed-n1-connect-after-failed-start` (**simulation**) | `mutant-n1-connect-lost-during-shutdown` → `Inv7_RestWantedNotDisconnected` |
| N1b | `handleStopIntent`: a STOP intent is scoped to the starts accepted before it (`startGeneration`, checked in `shutdown()`'s CAS step), ignored when `vpnWanted` is set again, and only `stopSelf(startId)` on an idle instance; `finish()` skips when a START was accepted after its CAS and ends with `stopSelf(lastStartId)` | `fixed-n1b-stale-stop-intent` (**simulation**; also `Inv8_RestNoIdleInstance`: no instance lingers idle) | `mutant-n1b-stale-stop-intent` → `Inv7_RestWantedNotDisconnected` |
| N2 | `failStart(epoch, message)` (`TailcatVpnService.kt:221`): the staleness re-check and `onVpnStartFailed` run in one `interfaceLock` region, which `shutdown()`'s CAS also takes; the following `shutdown(epoch)` and `abandonStaleStart`'s `shutdown(epoch)` check the epoch in the CAS step | `fixed-f4-stale-start-job`, `fixed-f7-stale-stop-intent`, `disconnect-restart` (**simulation**; N2 masked F4 / F7 before) | `mutant-n2-catch-toctou` |

#### N1 — a Connect that arrives while `shutdown()` is still running is lost

Not introduced by the round-1 fixes (the same mechanism exists in the old
code; the old model could not show it because it did not model `shutdown()` as
inline, the posted `finish`, or the `onDestroy` after `stopSelf()`).

Trace (`mutant-n1…`, about 80 states): Connect #1; `prepare` fails; the catch
called `onVpnStartFailed` → **UI DISCONNECTED** and only then `shutdown()`. The
user taps Connect #2 in between (or while `shutdown()` is stopping the engine,
up to 3 s): `startTunnel` sets `vpnWanted = true`; `onStartCommand` runs on the
same instance, `shuttingDown` is true so the duplicate guard lets it in,
`shuttingDown.set(false)` and a new start job is launched. The in-flight
`shutdown()` then finishes: the posted `finish` → `onVpnStopped` →
`stopSelf()` → the system's `onDestroy()` sees `shuttingDown == false` and
calls `shutdown()` again, which cancels the new job. End state: no TUN, engine
stopped, **UI DISCONNECTED, `vpnWanted == true`**, no error shown. A lost tap,
no leak.

Fixed by keeping the UI state until `finish()` (see the table). The Connect
action in `HomeViewModel` is only offered in DISCONNECTED, and `finish()` runs
`onVpnStopped` and `stopSelf()` in one main-thread message, so the next tap
reaches a new service instance.

#### N1b — a stale STOP intent on a fresh instance

Disconnect tap queues a STOP intent; a failed start has already finished the
instance (UI DISCONNECTED); the user taps Connect; the STOP intent is handled
by a **freshly created** instance whose launched `shutdown()` races the very
next START on that instance and cancels it. Same effect as N1: a lost tap, no
leak. The same `finish()` → `stopSelf()` → `onDestroy()` path also dropped a
START that was accepted (or already queued by Android) while a shutdown was
in flight.

Fixed by scoping the STOP intent and `finish()` (see the table). The fix relies
on documented `Service` behaviour that the model encodes as an assumption:
`stopSelf(startId)` does not stop the service when a newer start id exists,
and a STOP and a START are handled in the order they were sent. Neither was
checked on a device.

#### N2 — the catch block checked `isStale(epoch)` once, but acted later

Residual of F4. `startWithRetry` tested `!isActive || isStale(epoch)`, then
computed `permanent`, which may call `VpnService.prepare(this)` (a binder call),
and only then called `onVpnStartFailed` and `shutdown()`. If a Disconnect
finished **and the user tapped Connect again** inside that gap, the old job ran
`onVpnStartFailed` under the new tap: it cleared `vpnWanted` and ended the
session state; the new start (serialized behind the old job) then connected
with `vpnWanted == false` (no reconnect on failure, no restore), and the old
job's unscoped `shutdown()` could end the service the new start owned.

Trace (`mutant-n2…`, about 120 states): Connect; `prepare` fails; the job
passes the stale check; STOP processed, `finish` → DISCONNECTED; Connect again;
the old job's `onVpnStartFailed` → `vpnWanted = false`; the second start
connects with `vpnWanted == false`. Likelihood was very low (the window is one
binder call). Fixed by `failStart` (see the table).

### Looked for and **not** found

* **Deadlock / hang**: no deadlock reported (TLC deadlock check with `NEXT NextQ`)
  in the exhaustive configs listed as `pass` above nor in the random walks;
  `launchSerializedStart` joins only older jobs (job ids are launch order), so
  there is no join cycle, and `Inv7_RestNotConnecting` shows no start left
  hanging in CONNECTING in the passing configs. A job *does* wait behind an
  older job's blocking `prepare` (up to its handshake timeout) — a latency
  effect, not modelled in time.
* **A stale job stopping a newer session**: `Safe7_NoStopOfNewerSession` holds
  in every passing config; it is violated by `mutant-serial-start-jobs`, so the
  serialization is what prevents it.
* **Lock order / `shutdown()` on the main thread waiting for `interfaceLock`
  while `establish()` runs** — *code reading only, not TLC*: `establish()` is a
  binder call to system_server that does not need the app's main thread, and
  no path takes `stateLock` and then `interfaceLock` (order is `interfaceLock` →
  `stateLock`, `startLock` → `START_SERIAL`), so no cycle was found; a slow
  `establish()` would delay `onDestroy` on the main thread, which is a latency
  concern, not a deadlock.
* **`NetworkCallback` loss branch** — *code reading only, not TLC*:
  `TunnelController.kt:124` still does an unlocked read-then-write
  (`CONNECTED` → `RECONNECTING`) outside `stateLock`; the model keeps it atomic
  (`NetLose`), so it neither confirms nor refutes the same F6 shape there.

## What is NOT covered

* **Temporal properties are defined but not model-checked.** `Live3_*`,
  `Live4_*`, `Live5_*`, `Live6_*` are in `Lifecycle.tla`; TLC liveness checking
  on this model is far too slow. Their safety shadows (`Rest`-based invariants
  and the deadlock check) were checked in the explored scope.
* **Exhaustive coverage of the two-start scenarios** (F4, F7, N1, N1b, N2):
  random walks only. Violation configs found only by random walk pin a
  `\* SEED:` and run single-worker, so `check.sh` reproduces the same walk. Single-start scenarios (F1, F2, F3, F5, F6, baseline) are
  exhaustive within their bounds.
* Data plane, DNS policy, MTU, UDP table, token validation, telemetry values,
  split-tunnel exclusions, Always-on lockdown, notifications, the metrics
  notification coroutine, telemetry read failures, consent revocation (only a
  nondeterministic "permanent error" choice), `rejectStart`, the `onTaskRemoved`
  no-op, fd-number reuse, process death.
* **Blocking locks** (see abstraction choices) and any real-time latency.
* `pendingNativeStop`'s join guard is modelled (`GuardJoinNS`) but dropping it
  produces no invariant violation, so there is no mutant config for it.
* Scaled time: the *bound formula* is checked, not the 15 s / 3 s constants.
  The JVM memory model and Android's internal ordering of `onStartCommand` /
  `onDestroy` beyond a FIFO intent queue plus "`finish` then `onDestroy`" are
  abstracted; in particular that a START delivered between `shuttingDown`'s CAS
  and `stopSelf()` is destroyed by that `stopSelf()` is an assumption about
  Android's `Service` semantics (N1 depends on it).

## Model-checking notes

* TLC 2.19's default fingerprint set (`MSBDiskFPSet`) intermittently throws
  `ArithmeticException: Division by zero` in `AbstractChecker.reportSuccess`,
  i.e. after a search that found no violation (seen with a JVM exception log);
  `check.sh` uses `OffHeapDiskFPSet` instead and still retries once if it
  happens.
* Constants: `MaxSess MaxBr MaxTun MaxJobs MaxInst` (finite bounds, guarded by
  `BoundsOK`; kept as small as each scenario allows because TLC spills its BFS
  queue to disk at ~2 KB per queued state), `MaxFaults MaxStarts MaxDisc`
  (budgets), `FreshMax StalePolls BadBound` (scaled time), scenario switches
  `UiRaces Timed EnPoll En*`, `EnOnDestroy`, and `Mutants`.
* TLC processes roughly 0.8–1.3 M distinct states/min on 6 cores here.

## Run log

The authoritative run is the `formal` workflow on the commit in question; its
logs are uploaded as `tlc-logs-shard-*` artifacts.

Local runs on the final spec (TLC 2.19, tla2tools v1.7.4, JDK 21, 6 cores),
before the full suite moved to CI:

```text
PASS    baseline-connect             2220545 states generated, 309248 distinct, depth 68 (6 of 6 runs, OffHeapDiskFPSet)
PASS    fixed-n1b-stale-stop-intent  [simulate 300] no violation
EXPECTED-VIOLATION mutant-n1b-stale-stop-intent  [seed 22, 1 worker] Inv7_RestWantedNotDisconnected after ~3.1 M states (905 s)
```

Earlier rounds (the spec before the N1/N1b/N2 fixes) passed `fixed-f1`
(exhaustive, 2,960,384 distinct states, depth 70) and `fixed-f2` (exhaustive,
12,724,224 distinct states, depth 90); the configs are re-run by CI on the
final spec.
