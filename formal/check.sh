#!/usr/bin/env bash
# Model-check the OpenTailcat lifecycle with TLC.
#
#   formal/check.sh              run every config in formal/configs/
#   formal/check.sh NAME...      run only configs/NAME.cfg
#   formal/check.sh --quick      every config except those tagged `\* TIER: slow`
#   formal/check.sh --regressions  baseline, saved counterexamples, focused models
#   formal/check.sh --extended     remaining exhaustive scenarios and sampling
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
#   \* REPLAY: replays/name.json    (saved schedule, checked against current actions)
#   \* MODEL: Shutdown             (optional; default Lifecycle)
#
# Exploratory sampling is labelled SAMPLED, never CHECKED. An unexpected
# outcome makes this script exit non-zero. "violation" configs document
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
# Any exception still fails the check; no JVM failure is converted into success.
JAVA_OPTS=(-Djava.io.tmpdir="$TOOLS/tmp" -XX:+UseParallelGC "-Xmx${TLC_XMX:-5g}"
  "-XX:MaxDirectMemorySize=${TLC_DIRECT:-2g}"
  -Dtlc2.tool.fp.FPSet.impl=tlc2.tool.fp.OffHeapDiskFPSet)

# Regenerate the TLA+ block from the PlusCal source (idempotent).
java "${JAVA_OPTS[@]}" -cp "$JAR" pcal.trans -nocfg Lifecycle.tla >"$TOOLS/logs/pcal.log" 2>&1 \
  || { cat "$TOOLS/logs/pcal.log" >&2; exit 2; }
rm -f Lifecycle.old
if [[ "${1:-}" == "--translate" ]]; then exit 0; fi

quick=0; shard=""; selection=all
while [[ $# -gt 0 ]]; do
  case "$1" in
    --quick) quick=1; shift ;;
    --regressions) selection=regressions; shift ;;
    --extended) selection=extended; shift ;;
    --shard) shard="$2"; shift 2 ;;
    *) break ;;
  esac
done

if [[ $# -gt 0 ]]; then
  configs=(); for n in "$@"; do configs+=("configs/$n.cfg"); done
else
  configs=(configs/*.cfg)
fi

selected=(); selected_count=0
for c in "${configs[@]}"; do
  regression=0
  if [[ "$c" == configs/baseline-connect.cfg ]] || grep -Eq '^\\\* (MODEL|REPLAY):' "$c"; then regression=1; fi
  if [[ "$selection" == all || ( "$selection" == regressions && $regression -eq 1 ) || ( "$selection" == extended && $regression -eq 0 ) ]]; then
    selected+=("$c")
    selected_count=$((selected_count + 1))
  fi
done
if [[ $selected_count -eq 0 ]]; then
  echo "No configurations selected; no verification was performed." >&2
  exit 2
fi
configs=("${selected[@]}")

if [[ -n "$shard" ]]; then
  [[ "$shard" =~ ^([0-9]+)/([0-9]+)$ ]] && (( BASH_REMATCH[1] < BASH_REMATCH[2] )) \
    || { echo "--shard expects I/N with 0 <= I < N" >&2; exit 2; }
  si=${BASH_REMATCH[1]}; sn=${BASH_REMATCH[2]}
  ordered=(); for c in "${configs[@]}"; do grep -q '^\\\* TIER: slow' "$c" && ordered+=("$c"); done
  for c in "${configs[@]}"; do grep -q '^\\\* TIER: slow' "$c" || ordered+=("$c"); done
  configs=(); shard_count=0
  for i in "${!ordered[@]}"; do
    if (( i % sn == si )); then configs+=("${ordered[$i]}"); shard_count=$((shard_count + 1)); fi
  done
  if [[ $shard_count -eq 0 ]]; then
    echo "Empty shard; no verification was performed." >&2
    exit 2
  fi
fi

# Python owns the timeout and checks positive TLC completion/progress evidence.
# JVM crashes, external signals and resource guards always fail the check.
failed=0; checked=0
for cfg in "${configs[@]}"; do
  name="$(basename "$cfg" .cfg)"
  expect="$(sed -n 's/^\\\* EXPECT: *//p' "$cfg" | head -1)"
  [[ -n "$expect" ]] || { echo "$cfg: missing EXPECT" >&2; exit 2; }
  mode="$(sed -n 's/^\\\* MODE: *//p' "$cfg" | head -1)"
  tier="$(sed -n 's/^\\\* TIER: *//p' "$cfg" | head -1)"
  model="$(sed -n 's/^\\\* MODEL: *//p' "$cfg" | head -1)"
  model="${model:-Lifecycle}"
  if [[ $quick -eq 1 && "$tier" == slow ]]; then echo "SKIP    $name (TIER: slow, --quick)"; continue; fi
  runner_opts=(--log "$TOOLS/logs/$name.log" --expect "$expect" --min-free-kb "${TLC_MIN_FREE_KB:-4194304}")
  tlc_opts=(-config "$HERE/$cfg" -workers auto -metadir "$TOOLS/states/$name" -cleanup -nowarning)
  if grep -q '^\\\* REPLAY:' "$cfg"; then
    python3 "$HERE/replay.py" --prepare "$HERE/$cfg" --directory "$TOOLS/replays"
    replay_name="Replay_${name//-/_}"
    model="$TOOLS/replays/$replay_name.tla"
    tlc_opts=(-config "$TOOLS/replays/$replay_name.cfg" -workers 1 -metadir "$TOOLS/states/$name" -cleanup)
  elif [[ "$mode" == simulate* ]]; then
    [[ "$expect" == pass ]] || { echo "$cfg: mutation regressions require a REPLAY" >&2; exit 2; }
    runner_opts+=(--sample-seconds "${mode#simulate }")
    tlc_opts+=(-simulate num=1000000000 -depth 300)
  elif [[ -n "$mode" ]]; then
    echo "$cfg: unsupported MODE $mode" >&2; exit 2
  fi
  # INIT/NEXT models use NextQ or a terminal stutter. Temporal models use SPEC.
  [[ -n "${TLC_CHECK_TIMEOUT:-}" ]] && runner_opts+=(--timeout-seconds "$TLC_CHECK_TIMEOUT")
  echo "Checking $name ..."
  checked=$((checked + 1))
  if ! python3 "$HERE/tlc_runner.py" "${runner_opts[@]}" -- \
      java "${JAVA_OPTS[@]}" -cp "$JAR" tlc2.TLC "${tlc_opts[@]}" -lncheck final "$model"; then
    failed=1
  fi
  rm -rf "$TOOLS/states/$name"
done
if [[ $checked -eq 0 ]]; then
  echo "Every configuration was skipped; no verification was performed." >&2
  exit 2
fi
exit $failed
