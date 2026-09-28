# handoff.md history (September 2026)

Dated fix narratives and run logs moved out of `handoff.md` (review item 20).
They describe past states of the tree; `handoff.md` holds the current plan.

### Networking corruption on failed Connect — fixed in tree (200.x forced resolver)

In 1.3.2 and earlier (including the 1.2.14 checkpoint above) a failed `prepare`
— e.g. `gateway handshake failed: context deadline exceeded` after dialing a
`200.111.5.10:443`/`[2001:db8::1]:443` DERP or a user `FORCED_RESOLVER`
`200.160.0.8:53` — corrupted networking for **some time** (observed as “forces
me to use an IP that starts with 200”):

* `core-engine/lifecycle.go:219` `abandonPrepare()` cleared `sess/state` but
  left `globalCore.pendingDNS` (`core-engine/main.go:214`
  `globalCore.pendingDNS.Store(&cfg)`) from the `TailcatVpnService.kt:126`
  `updateNetworkState` that ran **before** `prepare`. The next `AttachTun`
  (`lifecycle.go:256` `dns := globalCore.pendingDNS.Load()`) therefore `Load()`ed
  the stale `FORCED_RESOLVER` (`app/libs/libtailcat.aar:19509312` build) and
  `bridge.go:97` `SetDNSConfig` forced subsequent port-53 flows through
  `Client.DialUDP` to the stale `200.x:53` (`netstack_proxy.go:resolveDNSDestination`).
  When that `200.x` was unreachable, `policyRejections`/`queueExhaustion`
  dropped DNS and, with the 5s `udpProbeTimeout` (`bridge.go:697`) latched
  `tcpOnly=true`, non-DNS UDP was also dropped until the 30s
  `udpReprobeInterval` (`bridge.go:701`) or an explicit `Stop()` finally cleared
  `pendingDNS` (`lifecycle.go:408`). The window was typically 30s + the 15s
  `HealthStale` grace (`service/EngineHealth.kt:13` `STALE_TEARDOWN_POLLS=3`) —
  i.e. “for some time” after a single failed tap.
* `app/src/main/java/com/tailcat/vpn/ui/screens/home/components/TelemetryCard.kt:57`
  used `tunnelActive = transportType != UNKNOWN`. After `FAILED`/`HealthStale`,
  `NetworkMetrics.kt:50` `isLiveRunning()` was already false (`state != "RUNNING"`
  or `healthUnixSec` stale) but `transportType` could remain `DIRECT_P2P`/`DERP_RELAY`,
  so the card kept showing `Exit IP: 200.x` (`metrics.tunnelEgressIp` from the
  previous session’s `bridge.go:894` `egressIP`) instead of `Device IP:`.
  Users saw a stale `200.` exit and perceived a forced `200.` route.

Fix in this tree (AAR `aa0fa1bd...`, `app/src/main/java/com/tailcat/vpn/ui/screens/home/components/TelemetryCard.kt:60`
now `isLiveRunning(nowSec)`; `core-engine/lifecycle.go:225` `pendingDNS.Store(nil)`
on `abandonPrepare`): a failed `prepare` no longer leaves a stale
`FORCED_RESOLVER`. Verified: `go test -race ./...`, `testDebugUnitTest`/`lintDebug`,
and `VpnStartupInstrumentedTest` (synthetic `tc…` with embedded `200.111.5.10`
DERP) now goes `CONNECTING (10s) → DISCONNECTED` with `lastError=gateway handshake
failed: context deadline exceeded`, `networkMetrics=UNKNOWN`/`tunnelEgressIp=null`,
`ip route` shows no `200.` TUN route, and a subsequent profile with `1.1.1.1`
correctly uses `1.1.1.1` — no forced `200.`.

Documented here per `AGENTS.md:Documentation rule` — `README.md`/`docs/releases/`
describe verified shipped behavior only; this handoff records the corrected
failure path.

## Split-tunnel exclusions now apply to the VPN interface (2026-09-21)

Through 1.3.3, Settings > Apps stored `splitTunnelExcludedApps`, but
`TunnelController.validateStartRequest` and `LeakGuard.refusalReasonForStartup`
refused Connect whenever the list was non-empty (`SPLIT_TUNNEL_BLOCKED`), and
`VpnService.Builder.addDisallowedApplication` was never called. A user could
select apps to bypass the VPN, but Connect then failed with
"Disable split-tunnel exclusions before connecting".

Fix in this tree (Kotlin-only; AAR unchanged):

- `TailcatVpnService.vpnBuilder` now applies each stored exclusion with
  `Builder.addDisallowedApplication` on both the warm (host-only) and routed
  (`0.0.0.0/0` + `::/0`) interfaces. The list is snapshotted once per start and
  filtered by `SplitTunnelExclusions.validPackages`, which skips blank entries
  and packages no longer installed so a stale entry cannot fail `establish`.
- The split-tunnel Connect refusal is removed from
  `TunnelController.validateStartRequest` and the service startup path;
  `LeakGuard` is deleted and `LOCKDOWN_REQUIRED_API` moved into `LockdownProbe`.
  Lockdown still never gates Connect (status only).
- Bypass remains leak-by-design per invariant 3: excluded apps use the ordinary
  OS network. UI copy states checked apps bypass the VPN and that the tunnel is
  not leak-free while any app is checked, and that with Always-on lockdown
  Android blocks checked apps from the network entirely (documented platform
  behavior for disallowed applications under lockdown).
- OpenTailcat's own UID is never on the list; the Settings picker filters out
  the app package. Magicsock/DERP bypass still relies on `VpnService.protect`
  (`TransportSocketProtect`); the app does not exclude its own UID.
- Tests: `SplitTunnelExclusionsTest` (blank/uninstalled filtering, ordering);
  `LeakGuardTest` deleted; `LockdownProbeTest` updated.

Still pending: the Phase 8 split-tunnel acceptance evidence above (second-UID
probe traffic must appear directly on the uplink pcap and be absent from the
gateway pcap while exclusions are set). Pass locally only after that capture.

## Settings > Apps picker now lists all installed apps (2026-09-21)

Through 1.3.4 the split-tunnel picker used `LauncherApps.getActivityList`,
which returns only apps with a launcher icon. Background and headless apps
never appeared. In 1.3.5 the picker enumerates every package installed for
the user via `PackageManager.getInstalledApplications` (`SettingsScreen.kt`),
adds a search field filtering by app name or package name, and declares
`QUERY_ALL_PACKAGES` in the manifest (required on API 30+ for full package
enumeration; lint advisory suppressed with `tools:ignore`). Exclusion
application, leak-by-design copy, and the Phase 8 split-tunnel packet-capture
acceptance are unchanged.


#### Phase 8 dual-capture run log (2026-09-23)

Physical phone + live Tailcat gateway (nullexit stack in Colima/Docker on the
same Mac). Phone app connected (token from `~/.opentailcat-private/live-token.txt`);
user opened `https://1.1.1.1`, `https://8.8.8.8`, `https://9.9.9.9` on the phone
during the capture window. Pcaps are gitignored under `captures/`.

**What was captured**

| File | Where | How |
|---|---|---|
| `captures/gateway.pcap` | Inside the `warp` / tailcat container network namespace (after WireGuard decrypt, before WARP encapsulation) | `colima ssh` → `sudo nsenter -t $(docker inspect -f '{{.State.Pid}}' warp) -n tcpdump -i any -s 0 -w /tmp/p8cap/gateway.pcap` |
| `captures/outer.pcap` | Colima **host** namespace (outer view: DERP/WARP/docker bridge — **not** the phone radio) | `colima ssh` → `sudo tcpdump -i any -s 0 -w /tmp/p8cap/outer.pcap` |

Classic pcap (magic `a1b2c3d4`), link type LINUX_SLL2 (276). Sizes ~104 MB /
~166 MB, ~136k / ~234k packets. Stopped with `pkill tcpdump`, copied out via
`colima ssh cat`.

**Counts (non-DNS probe dests only; DNS:53 to those IPs is ignored by the analyzer)**

| File | `1.1.1.1` | `8.8.8.8` | `9.9.9.9` |
|---|---|---|---|
| gateway | 297 | 40 | 26 |
| outer | 0 | 0 | 0 |

Gateway flows are mostly `172.16.0.2 → 1.1.1.1/8.8.8.8/9.9.9.9:443` (TCP) plus
ICMP to `1.1.1.1`. `172.16.0.2` is the WARP `tun0` address inside the netns —
i.e. decrypted phone traffic being forwarded out the gateway path.

**Analyzer (after SLL2 fix)**

```bash
scripts/phase8/analyze-uplink.sh \
  captures/outer.pcap 1.1.1.1,8.8.8.8,9.9.9.9 captures/gateway.pcap
# PASS uplink: probe destinations absent
# PASS gateway: probe destinations present
# exit 0

# Control: gateway misused as uplink must fail
scripts/phase8/analyze-uplink.sh \
  captures/gateway.pcap 1.1.1.1,8.8.8.8,9.9.9.9 captures/gateway.pcap
# FAIL uplink leak dests: [1.1.1.1 8.8.8.8 9.9.9.9]  exit 1
```

SLL2 bug fixed in `core-engine/phase8_pcap.go` (now `core-engine/phase8/pcap.go`): payload offset is a fixed
header of 20 bytes; `12+addr_len` is wrong when `addr_len=0` (common on
`tcpdump -i any` — all 463 probe packets in this gateway pcap had `addr_len=0`).
Test covers `addr_len` 0 and 8. AAR rebuilt: sha256
`986c21150a4da2890b78023523a9b2bd6415ddc68de301000e792af6c16a2436`,
sourcehash `1916945b42a9ae78ffa2ad070752c1d60063f1276eaa5d968e2fb8af2764a075`.
`scripts/phase8/run-host-gates.sh` green after the fix.

**Scope (plain)**

- Proven for this run: phone probe traffic reached the gateway and left toward
  the probe destinations on the gateway path; cleartext probe dests were not
  seen on the Colima host outer capture taken at the same time.
- Not proven: that the **phone’s own radio / home AP** never saw cleartext
  `1.1.1.1`. `outer.pcap` is this Mac’s outer interfaces, not the phone’s
  Wi‑Fi hop. Session path was **DERP relay** (`path=relay nyc`, 0 direct peers),
  so the phone’s first hop is Cloudflare, not this Mac. Full Phase 8 still needs
  a simultaneous capture on the phone’s actual uplink (AP/next hop, or rooted
  `wlan0`) paired with the gateway pcap, then the same analyzer invocation.
- Synthetic `e2e-analyze.sh` and host gates are tooling only (see above).

Commands used for host gates after the fix:

```bash
cd core-engine && GOPROXY=off go test ./... && GOPROXY=off go vet ./...
bash core-engine/build-aar.sh
scripts/phase8/run-host-gates.sh
```
