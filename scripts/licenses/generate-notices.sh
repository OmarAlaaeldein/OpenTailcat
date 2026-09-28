#!/usr/bin/env bash
# Regenerates app/src/main/assets/THIRD_PARTY_NOTICES.txt: every Go module
# linked into the checked-in libgojni.so (from `go version -m`) and every
# library on the release runtime classpath, with each distinct license text
# printed once. Run from anywhere; needs go, python3, unzip and network access
# for the first go-licenses download. Temporary files go to build/licenses/.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

WORK="build/licenses"
OUT="app/src/main/assets/THIRD_PARTY_NOTICES.txt"
GO_LICENSES_VERSION="v1.6.0"

rm -rf "$WORK"
mkdir -p "$WORK/bin" "$(dirname "$OUT")"

echo "==> Reading Go modules from the checked-in AAR"
unzip -q -o app/libs/libtailcat.aar 'jni/*' -d "$WORK/aar"
go version -m "$WORK/aar/jni/arm64-v8a/libgojni.so" > "$WORK/gomod.txt"
go version -m "$WORK/aar/jni/x86_64/libgojni.so" > "$WORK/gomod-x86_64.txt"
if ! diff <(grep -E '^[[:space:]](dep|=>)' "$WORK/gomod.txt") \
    <(grep -E '^[[:space:]](dep|=>)' "$WORK/gomod-x86_64.txt") > /dev/null; then
    echo "arm64-v8a and x86_64 libgojni.so link different modules" >&2
    exit 1
fi

echo "==> Collecting Go license texts with go-licenses $GO_LICENSES_VERSION"
# Built for the host; the package graph below is loaded for android/arm64.
env -u GOOS -u GOARCH GOBIN="$ROOT/$WORK/bin" \
    go install "github.com/google/go-licenses@$GO_LICENSES_VERSION"
(
    cd core-engine
    export GOOS=android GOARCH=arm64 CGO_ENABLED=1
    ../"$WORK"/bin/go-licenses save ./ golang.org/x/mobile/bind/seq \
        --ignore com.tailcat.vpn/engine --save_path="../$WORK/go" 2> "../$WORK/save.log"
    ../"$WORK"/bin/go-licenses report ./ golang.org/x/mobile/bind/seq \
        --ignore com.tailcat.vpn/engine > "../$WORK/go-report.csv" 2> "../$WORK/report.log"
    # Apache-2.0 requires passing on NOTICE files; go-licenses copies only licenses.
    awk '$1 == "dep" { print $2 }' "../$WORK/gomod.txt" \
        | grep -v -e '^com\.tailcat\.vpn/engine$' -e '^github\.com/tailscale/tailcat$' \
        | xargs go list -m -f '{{.Path}} {{.Dir}}' > "../$WORK/module-dirs.txt"
)
GOROOT_DIR="$(go env GOROOT)"
# Homebrew keeps the Go LICENSE next to libexec/ instead of inside GOROOT.
for candidate in "$GOROOT_DIR/LICENSE" "$GOROOT_DIR/../LICENSE"; do
    if [[ -f "$candidate" ]]; then
        cp "$candidate" "$WORK/go-stdlib-LICENSE"
        break
    fi
done
if [[ ! -f "$WORK/go-stdlib-LICENSE" ]]; then
    echo "Go standard library LICENSE not found under $GOROOT_DIR" >&2
    exit 1
fi
TAILCAT_COMMIT="$(git -C third_party/tailcat rev-parse HEAD)"

echo "==> Resolving the Android release runtime classpath"
./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath --console=plain \
    > "$WORK/android-deps.txt"

echo "==> Writing $OUT"
python3 - "$WORK" "$OUT" "$TAILCAT_COMMIT" <<'PY'
import csv, hashlib, os, re, sys

work, out, tailcat_commit = sys.argv[1:4]

def normalize(text):
    lines = [l.rstrip() for l in text.replace("\r\n", "\n").split("\n")]
    while lines and not lines[0]:
        lines.pop(0)
    while lines and not lines[-1]:
        lines.pop()
    return "\n".join(lines)

texts = {}   # hash -> [number, type, text, users]
order = []

def add_text(text, kind, user):
    text = normalize(text)
    key = hashlib.sha256(text.encode()).hexdigest()
    if key not in texts:
        texts[key] = [len(order) + 1, kind, text, []]
        order.append(key)
    entry = texts[key]
    if kind not in entry[1].split(" / "):
        entry[1] += " / " + kind
    entry[3].append(user)
    return entry[0]

# Go modules linked into the binary (the binary is authoritative).
go_version = None
modules = {}
lines = open(os.path.join(work, "gomod.txt")).read().split("\n")
for i, line in enumerate(lines):
    fields = line.strip().split("\t")
    if i == 0:
        go_version = line.split(": ", 1)[1].strip()
    elif fields[0] == "dep":
        modules[fields[1]] = fields[2]
local = {"com.tailcat.vpn/engine", "github.com/tailscale/tailcat"}

report = {}
with open(os.path.join(work, "go-report.csv")) as f:
    for row in csv.reader(f):
        if row:
            report[row[0]] = row[2]

def module_of(library):
    best = None
    for m in modules:
        if library == m or library.startswith(m + "/"):
            if best is None or len(m) > len(best):
                best = m
    return best

rows = []
covered = set()
std = add_text(open(os.path.join(work, "go-stdlib-LICENSE")).read(), "BSD-3-Clause",
               "Go standard library")
rows.append(("Go standard library", go_version, "BSD-3-Clause", std))

saved = os.path.join(work, "go")
for dirpath, _, files in sorted(os.walk(saved)):
    for name in sorted(files):
        library = os.path.relpath(dirpath, saved)
        module = module_of(library)
        if module is None:
            print(f"warning: {library} is not linked into libgojni.so; skipped", file=sys.stderr)
            continue
        covered.add(module)
        kind = report.get(library, "Unknown")
        if kind == "Unknown":
            sys.exit(f"go-licenses could not classify the license of {library}")
        version = modules[module]
        if module == "github.com/tailscale/tailcat":
            version = f"submodule {tailcat_commit[:12]}"
        number = add_text(open(os.path.join(dirpath, name), errors="replace").read(), kind, library)
        rows.append((library, version, kind, number))

missing = sorted(set(modules) - covered - {"com.tailcat.vpn/engine"})
if missing:
    sys.exit("no license found for linked modules: " + ", ".join(missing))

# Apache-2.0 NOTICE files of linked modules.
notices = []
for line in open(os.path.join(work, "module-dirs.txt")):
    parts = line.split()
    if len(parts) != 2 or parts[0] not in modules or parts[0] in local:
        continue
    for name in ("NOTICE", "NOTICE.txt", "NOTICE.md"):
        path = os.path.join(parts[1], name)
        if os.path.isfile(path):
            notices.append((parts[0], normalize(open(path, errors="replace").read())))

# Android libraries on the release runtime classpath.
ANDROID_LICENSES = {
    "androidx": "Apache-2.0",
    "com.google.code.gson": "Apache-2.0",
    "com.google.crypto.tink": "Apache-2.0",
    "com.google.guava": "Apache-2.0",
    "org.jetbrains": "Apache-2.0",
    "org.jetbrains.kotlin": "Apache-2.0",
    "org.jetbrains.kotlinx": "Apache-2.0",
    "org.jspecify": "Apache-2.0",
}
apache = open("LICENSE").read()
android = {}
for line in open(os.path.join(work, "android-deps.txt")):
    line = line.rstrip()
    m = re.search(r"[+\\]--- ([\w.\-]+):([\w.\-]+)(?::([^ ]+))?(?: -> ([^ ]+))?", line)
    if not m or line.endswith("(c)") or m.group(2).endswith("-bom"):
        continue
    group, name = m.group(1), m.group(2)
    version = m.group(4) or m.group(3)
    kind = next((v for k, v in ANDROID_LICENSES.items()
                 if group == k or group.startswith(k + ".")), None)
    if kind is None:
        sys.exit(f"no license mapping for Android dependency {group}:{name}; add it to ANDROID_LICENSES")
    android[f"{group}:{name}"] = (version, kind)
if not android:
    sys.exit("no Android dependencies resolved")
apache_number = None
for coord, (version, kind) in sorted(android.items()):
    apache_number = add_text(apache, kind, coord)
own = add_text(apache, "Apache-2.0", "OpenTailcat")

def table(entries):
    name_width = max(len(e[0]) for e in entries)
    version_width = max(len(e[1]) for e in entries)
    return "\n".join(
        f"  {e[0].ljust(name_width)}  {e[1].ljust(version_width)}  {e[2]} [{e[3]}]" for e in entries
    )

doc = []
doc.append("OpenTailcat third-party notices")
doc.append("")
doc.append("Generated by scripts/licenses/generate-notices.sh; do not edit by hand.")
doc.append(f"OpenTailcat itself is licensed under the Apache License 2.0 [{own}].")
doc.append("Numbers in brackets refer to the license texts at the end.")
doc.append("")
doc.append("NATIVE ENGINE (libgojni.so)")
doc.append("")
doc.append(f"Built with {go_version}. Go modules linked into the library:")
doc.append("")
doc.append(table(rows))
doc.append("")
doc.append("ANDROID LIBRARIES (release runtime classpath)")
doc.append("")
doc.append(table([(c, v, k, apache_number) for c, (v, k) in sorted(android.items())]))
doc.append("")
doc.append("Debug builds also include androidx.compose.ui:ui-tooling and")
doc.append(f"androidx.compose.ui:ui-test-manifest (Apache-2.0 [{apache_number}]).")
if notices:
    doc.append("")
    doc.append("NOTICE FILES")
    for module, text in notices:
        doc.append("")
        doc.append(f"--- {module} ---")
        doc.append(text)
doc.append("")
doc.append("LICENSE TEXTS")
for key in order:
    number, kind, text, users = texts[key]
    doc.append("")
    doc.append("=" * 72)
    doc.append(f"[{number}] {kind}")
    doc.append(f"Used by: {', '.join(users[:6])}" + (f" and {len(users) - 6} more" if len(users) > 6 else ""))
    doc.append("=" * 72)
    doc.append("")
    doc.append(text)
open(out, "w").write("\n".join(doc) + "\n")
print(f"{len(rows)} Go libraries, {len(android)} Android libraries, {len(order)} distinct license texts")
PY
