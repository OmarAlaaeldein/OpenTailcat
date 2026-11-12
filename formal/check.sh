#!/usr/bin/env bash
# Model-check the OpenTailcat lifecycle with TLC.
#
#   formal/check.sh              run every config in formal/configs/
#   formal/check.sh NAME...      run only configs/NAME.cfg
#   formal/check.sh --quick      every config except those tagged `\* TIER: slow`
#   formal/check.sh --shard I/N  only shard I (0-based) of N; slow configs are
#                                spread first, so N parallel CI jobs finish together
#   formal/check.sh --translate  only regenerate the PlusCal translation
#
# Everything (jar, TLC state, logs, scratch) lives under formal/.tools/, which
# is git-ignored.  Each config declares its expected outcome on a comment line:
#
#   \* EXPECT: pass
#   \* EXPECT: violation <regex that must appear in the TLC output>
#   \* MODE: simulate <seconds>      (optional; random-walk simulation, not exhaustive)
#   \* TIER: slow                   (optional; skipped by --quick)
#   \* SEED: <n>                    (optional, simulate only; one worker, fixed seed,
#                                    so the same random walk is replayed every run)
#
# A config that is expected to pass but fails, or is expected to be violated
# but passes, makes this script exit non-zero.  "violation" configs document
# known design findings and mutation checks (see formal/README.md).
set -euo pipefail

TLA_VERSION="v1.7.4"
TLA_SHA256="936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88"
TLA_URL="https://github.com/tlaplus/tlaplus/releases/download/${TLA_VERSION}/tla2tools.jar"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"
TOOLS="$HERE/.tools"
JAR="$TOOLS/tla2tools-${TLA_VERSION}.jar"
mkdir -p "$TOOLS/tmp" "$TOOLS/states" "$TOOLS/logs"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

if [[ ! -f "$JAR" ]]; then
  echo "Downloading tla2tools.jar ${TLA_VERSION} ..."
  curl -fSL --retry 3 -o "$JAR.part" "$TLA_URL"
  mv "$JAR.part" "$JAR"
fi
actual="$(sha256_of "$JAR")"
if [[ "$actual" != "$TLA_SHA256" ]]; then
  echo "tla2tools.jar sha256 mismatch: expected $TLA_SHA256, got $actual" >&2
  echo "Delete $JAR and re-run to download again." >&2
  exit 2
fi

# OffHeapDiskFPSet: with TLC 2.19's default MSBDiskFPSet, the fingerprint
# collision report that runs after a *successful* search
# (AbstractChecker.reportSuccess) intermittently throws
# "ArithmeticException: Division by zero" and TLC exits non-zero.  Memory is
# configurable for small CI runners (TLC_XMX heap, TLC_DIRECT fingerprints).
JAVA_OPTS=(-Djava.io.tmpdir="$TOOLS/tmp" -XX:+UseParallelGC "-Xmx${TLC_XMX:-5g}"
  "-XX:MaxDirectMemorySize=${TLC_DIRECT:-2g}"
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet)

# Regenerate the TLA+ block from the PlusCal source (idempotent).
java "${JAVA_OPTS[@]}" -cp "$JAR" pcal.trans -nocfg Lifecycle.tla >"$TOOLS/logs/pcal.log" 2>&1 \
  || { cat "$TOOLS/logs/pcal.log" >&2; exit 2; }
rm -f Lifecycle.old
if [[ "${1:-}" == "--translate" ]]; then exit 0; fi

quick=0; shard=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --quick) quick=1; shift ;;
    --shard) shard="$2"; shift 2 ;;
    *) break ;;
  esac
done

if [[ $# -gt 0 ]]; then
  configs=(); for n in "$@"; do configs+=("configs/$n.cfg"); done
else
  configs=(configs/*.cfg)
fi

if [[ -n "$shard" ]]; then
  [[ "$shard" =~ ^([0-9]+)/([0-9]+)$ ]] && (( BASH_REMATCH[1] < BASH_REMATCH[2] )) \
    || { echo "--shard expects I/N with 0 <= I < N" >&2; exit 2; }
  si=${BASH_REMATCH[1]}; sn=${BASH_REMATCH[2]}
  ordered=(); for c in "${configs[@]}"; do grep -q '^\\\* TIER: slow' "$c" && ordered+=("$c"); done
  for c in "${configs[@]}"; do grep -q '^\\\* TIER: slow' "$c" || ordered+=("$c"); done
  configs=(); for i in "${!ordered[@]}"; do (( i % sn == si )) && configs+=("${ordered[$i]}"); done
fi

# TLC spills its BFS queue to disk (~2 KB per queued state).  Stop it before it
# can fill the volume: below MIN_FREE_KB free, kill TLC and report a FAIL.
MIN_FREE_KB=${TLC_MIN_FREE_KB:-4194304}
disk_guard() {
  local pid="$1" log="$2" free
  while kill -0 "$pid" 2>/dev/null; do
    free="$(df -Pk "$TOOLS" | awk 'NR==2 {print $4}')"
    if [[ -n "$free" && "$free" -lt "$MIN_FREE_KB" ]]; then
      echo "disk guard: only ${free} KiB free; killing TLC" >>"$log"
      kill "$pid" 2>/dev/null || true
      return
    fi
    sleep 5
  done
}

failed=0
for cfg in "${configs[@]}"; do
  name="$(basename "$cfg" .cfg)"
  expect="$(sed -n 's/^\\\* EXPECT: *//p' "$cfg" | head -1)"
  [[ -n "$expect" ]] || { echo "$cfg: missing '\\* EXPECT:' line" >&2; exit 2; }
  # configs that use NEXT NextQ keep TLC's deadlock check (stuck != quiescent)
  if grep -q '^NEXT NextQ' "$cfg"; then dl=""; else dl="-deadlock"; fi
  mode="$(sed -n 's/^\\\* MODE: *//p' "$cfg" | head -1)"
  tier="$(sed -n 's/^\\\* TIER: *//p' "$cfg" | head -1)"
  if [[ $quick -eq 1 && "$tier" == slow ]]; then echo "SKIP    $name (TIER: slow, --quick)"; continue; fi
  log="$TOOLS/logs/$name.log"
  start=$(date +%s)
  set +e
  if [[ "$mode" == simulate* ]]; then
    # Random-walk simulation (NOT exhaustive): run for N seconds, stop at the
    # first violation.  Used for scenarios whose state space is too large.
    secs_cap="${mode#simulate}"; secs_cap="${secs_cap# }"; secs_cap="${secs_cap:-120}"
    seed="$(sed -n 's/^\\\* SEED: *//p' "$cfg" | head -1)"
    if [[ -n "$seed" ]]; then sim_opts=(-seed "$seed" -workers 1); else sim_opts=(-workers auto); fi
    java "${JAVA_OPTS[@]}" -cp "$JAR" tlc2.TLC -config "$cfg" $dl -simulate num=1000000000 \
      -depth 300 "${sim_opts[@]}" -metadir "$TOOLS/states/$name" -cleanup -nowarning Lifecycle.tla >"$log" 2>&1 &
    tlc_pid=$!
    ( sleep "$secs_cap"; kill "$tlc_pid" 2>/dev/null ) &
    killer=$!
    wait "$tlc_pid"; rc=$?
    kill "$killer" 2>/dev/null || true; wait "$killer" 2>/dev/null || true
    # killed by the time cap (143) without a violation counts as "no violation found"
    if [[ $rc -eq 143 || $rc -eq 137 ]] && ! grep -Eq "is violated|were violated|Deadlock reached" "$log"; then rc=0; fi
  else
    # TLC 2.19 occasionally dies at the very end of a short run with
    # "java.lang.ArithmeticException: Division by zero" in its own statistics
    # code (no model error).  Re-run such a config once.
    for attempt in 1 2; do
      java "${JAVA_OPTS[@]}" -cp "$JAR" tlc2.TLC -config "$cfg" $dl -workers auto \
        -metadir "$TOOLS/states/$name" -cleanup -nowarning Lifecycle.tla >"$log" 2>&1 &
      tlc_pid=$!
      disk_guard "$tlc_pid" "$log" &
      guard=$!
      wait "$tlc_pid"; rc=$?
      kill "$guard" 2>/dev/null || true; wait "$guard" 2>/dev/null || true
      if [[ $rc -ne 0 ]] && grep -q 'ArithmeticException' "$log" && ! grep -Eq 'is violated|were violated|Deadlock reached' "$log"; then
        echo "note: $name hit TLC's ArithmeticException (attempt $attempt); retrying" >&2
        continue
      fi
      break
    done
  fi
  set -e
  rm -rf "$TOOLS/states/$name"     # TLC's on-disk queue can be many GB
  secs=$(( $(date +%s) - start ))
  if grep -q '^disk guard:' "$log"; then echo "FAIL    $name: stopped by the disk guard (see $log)"; failed=1; continue; fi
  summary="$(grep -E '^[0-9,]+ states generated' "$log" | tail -1 || true)"
  depth="$(grep -E 'depth of the complete state graph' "$log" | tail -1 || true)"
  [[ -n "$mode" ]] && summary="[$mode] ${summary:-simulated}"
  case "$expect" in
    pass)
      if [[ $rc -eq 0 ]]; then
        echo "PASS    $name (${secs}s)  $summary  $depth"
      else
        echo "FAIL    $name: expected pass, TLC exit $rc (see $log)"; failed=1
        grep -E 'Error:|is violated|were violated' "$log" | head -3 || true
      fi ;;
    violation*)
      pattern="${expect#violation}"; pattern="${pattern# }"
      if [[ $rc -ne 0 ]] && grep -Eq "is violated|were violated|Deadlock reached" "$log" \
         && { [[ -z "$pattern" ]] || grep -Eq "$pattern" "$log"; }; then
        echo "EXPECTED-VIOLATION $name (${secs}s)  $(grep -E 'is violated|were violated|Deadlock reached' "$log" | head -1)"
      else
        echo "FAIL    $name: expected violation '$pattern', TLC exit $rc (see $log)"; failed=1
      fi ;;
    *) echo "$cfg: bad EXPECT '$expect'" >&2; exit 2 ;;
  esac
done
exit $failed
