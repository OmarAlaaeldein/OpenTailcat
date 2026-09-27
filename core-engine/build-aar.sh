#!/usr/bin/env bash
set -euo pipefail

# Deterministic and Reproducible Build Script for libtailcat.aar
# Requirements: Go 1.27.1, Android NDK (API 26+)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_AAR="${ROOT_DIR}/app/libs/libtailcat.aar"
CANONICAL_BUILD_DIR="/tmp/opentailcat-build"

echo "==> Resolving Android NDK..."
PIN_NDK="29.0.14206865"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    # Prefer the pinned SDK install over a newer Homebrew NDK that would
    # fail the version check below.
    if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk/${PIN_NDK}" ]]; then
        export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/${PIN_NDK}"
    elif [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}/ndk/${PIN_NDK}" ]]; then
        export ANDROID_NDK_HOME="${ANDROID_SDK_ROOT}/ndk/${PIN_NDK}"
    elif [[ -f "${ROOT_DIR}/local.properties" ]]; then
        SDK_DIR="$(sed -n 's/^sdk.dir=//p' "${ROOT_DIR}/local.properties" | head -n1 | tr -d '\r')"
        if [[ -n "${SDK_DIR}" && -d "${SDK_DIR}/ndk/${PIN_NDK}" ]]; then
            export ANDROID_NDK_HOME="${SDK_DIR}/ndk/${PIN_NDK}"
        fi
    fi
fi
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    if [[ -d "/opt/homebrew/share/android-ndk" ]]; then
        export ANDROID_NDK_HOME="/opt/homebrew/share/android-ndk"
    elif [[ -n "${ANDROID_HOME:-}" ]] && ls -d "${ANDROID_HOME}/ndk/"* 1>/dev/null 2>&1; then
        export ANDROID_NDK_HOME="$(ls -d "${ANDROID_HOME}/ndk/"* | tail -n 1)"
    elif [[ -n "${ANDROID_HOME:-}" ]] && [[ -d "${ANDROID_HOME}/ndk-bundle" ]]; then
        export ANDROID_NDK_HOME="${ANDROID_HOME}/ndk-bundle"
    else
        echo "ERROR: ANDROID_NDK_HOME is not set and could not be detected." >&2
        exit 1
    fi
fi

if [[ ! -d "${ANDROID_NDK_HOME}" ]]; then
    echo "ERROR: ANDROID_NDK_HOME directory '${ANDROID_NDK_HOME}' does not exist." >&2
    exit 1
fi

if [[ ! -f "${ANDROID_NDK_HOME}/source.properties" ]]; then
    echo "ERROR: source.properties not found in ANDROID_NDK_HOME: ${ANDROID_NDK_HOME}" >&2
    exit 1
fi

NDK_REVISION="$(grep "Pkg.Revision" "${ANDROID_NDK_HOME}/source.properties" | cut -d'=' -f2 | tr -d ' ')"
if [[ "${NDK_REVISION}" != "29.0.14206865"* ]]; then
    echo "ERROR: Pinned NDK version 29.0.14206865 is required. Found: ${NDK_REVISION} at ${ANDROID_NDK_HOME}" >&2
    exit 1
fi

echo "==> Using Android NDK: ${ANDROID_NDK_HOME} (Pkg.Revision = ${NDK_REVISION})"
echo "==> Using Go: $(go version)"

# Enforce exact Go version 1.27.1
GO_VER_STR="$(go version)"
if [[ "${GO_VER_STR}" != *"go version go1.27.1 "* ]]; then
    echo "ERROR: Pinned Go version 1.27.1 is required. Found: ${GO_VER_STR}" >&2
    exit 1
fi

# Locate readelf binary for ELF alignment verification
READELF_BIN=""
if [[ -x "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf" ]]; then
    READELF_BIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-readelf"
elif [[ -x "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" ]]; then
    READELF_BIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
elif command -v readelf &>/dev/null; then
    READELF_BIN="$(command -v readelf)"
else
    echo "ERROR: llvm-readelf / readelf binary could not be found in NDK or PATH." >&2
    exit 1
fi
echo "==> Using readelf: ${READELF_BIN}"

# Prepare canonical staging directory to ensure byte-for-byte reproducibility
# independent of checkout filesystem paths.
echo "==> Setting up canonical staging directory at ${CANONICAL_BUILD_DIR}..."
rm -rf "${CANONICAL_BUILD_DIR}"
mkdir -p "${CANONICAL_BUILD_DIR}"
TMP_VERIFY_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_VERIFY_DIR}" "${CANONICAL_BUILD_DIR}"' EXIT
cp -R "${ROOT_DIR}/core-engine" "${CANONICAL_BUILD_DIR}/core-engine"
cp -R "${ROOT_DIR}/third_party" "${CANONICAL_BUILD_DIR}/third_party"

# Hash the exact native source tree the AAR is built from so CI can fail when
# Go/third_party changes land without a rebuild (AAR must not lag source).
# The hash is written only after the new AAR is built, verified, and in place.
native_source_hash() {
    (
        cd "${ROOT_DIR}" &&
        find core-engine third_party \
            -type f \
            ! -path '*/.git/*' \
            ! -name .git \
            ! -name '*.aar' \
            ! -name '*.aar.sha256' \
            ! -path '*/build/*' \
            -print0 |
            LC_ALL=C sort -z |
            xargs -0 shasum -a 256 |
            shasum -a 256 |
            awk '{print $1}'
    )
}
echo "==> Hashing native source inputs..."
SOURCE_HASH="$(native_source_hash)"
echo "==> Native source hash: ${SOURCE_HASH}"

cd "${CANONICAL_BUILD_DIR}/core-engine"

# Ensure gobind is available in PATH for gomobile
export PATH="$(go env GOPATH)/bin:${PATH}"
if ! command -v gobind &>/dev/null; then
    echo "==> Installing gobind tool..."
    go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260821190718-4776eadac327
fi

# Build into the staging directory; app/libs is touched only after every
# check below passes, so a failed build cannot leave a stale AAR that still
# matches a fresh sourcehash. gomobile also writes libtailcat-sources.jar
# next to the output; it stays in staging and never reaches app/libs.
STAGED_AAR="${CANONICAL_BUILD_DIR}/out/libtailcat.aar"
mkdir -p "$(dirname "${STAGED_AAR}")"

# Build with reproducible flags:
# -ldflags="-s -w": strip DWARF debugging tables and symbol references
# -trimpath: remove local workstation path prefixes from build artifacts
echo "==> Building libtailcat.aar with gomobile bind..."
go run golang.org/x/mobile/cmd/gomobile bind \
    -ldflags="-s -w" \
    -trimpath \
    -target=android/arm64,android/amd64 \
    -androidapi=26 \
    -javapkg=com.tailcat.vpn \
    -o "${STAGED_AAR}" \
    .

echo "==> Verifying AAR structure..."
unzip -l "${STAGED_AAR}"

unzip -q "${STAGED_AAR}" -d "${TMP_VERIFY_DIR}"

echo "==> Verifying Java API signatures..."
JAVAP_OUT="$(javap -classpath "${TMP_VERIFY_DIR}/classes.jar" com.tailcat.vpn.engine.Engine)"
echo "${JAVAP_OUT}"

REQUIRED_METHODS=(
    "touch"
    "attachTun"
    "detachTun"
    "disarmPumps"
    "getCapabilitiesJSON"
    "getStatsJSON"
    "measureTunnelPingMS"
    "measureTunnelDownloadMbps"
    "measureTunnelUploadMbps"
    "parseToken"
    "prepare"
    "stop"
    "updateNetworkState"
    "setSocketProtector"
)

for method in "${REQUIRED_METHODS[@]}"; do
    if ! echo "${JAVAP_OUT}" | grep -q "${method}"; then
        echo "ERROR: Required Engine method '${method}' is missing from generated Java API!" >&2
        exit 1
    fi
done
echo "==> All required Java API methods verified."

echo "==> Verifying 16 KB ELF load alignment for ABIs..."
for abi in "arm64-v8a" "x86_64"; do
    SO_PATH="${TMP_VERIFY_DIR}/jni/${abi}/libgojni.so"
    if [[ ! -f "${SO_PATH}" ]]; then
        echo "ERROR: Shared library missing for ABI ${abi}: ${SO_PATH}" >&2
        exit 1
    fi
    echo "--- Checking ${abi} (${SO_PATH}) ---"
    LOAD_LINES="$("${READELF_BIN}" -l "${SO_PATH}" | grep "LOAD")"
    echo "${LOAD_LINES}"
    # Every LOAD segment must have an alignment of at least 0x4000 (16 KB)
    while IFS= read -r line; do
        ALIGN_VAL="$(echo "${line}" | awk '{print $NF}')"
        if [[ "${ALIGN_VAL}" != "0x4000" && "${ALIGN_VAL}" != "0x10000" && "${ALIGN_VAL}" != "0x200000" ]]; then
            echo "ERROR: ELF LOAD segment in ${abi} does not satisfy 16 KB alignment: ${line}" >&2
            exit 1
        fi
    done <<< "${LOAD_LINES}"
done
echo "==> 16 KB ELF load alignment verified for all target ABIs."

echo "==> Verifying NDK identity notes (.note.android.ident)..."
"${READELF_BIN}" -n "${TMP_VERIFY_DIR}/jni/arm64-v8a/libgojni.so" || true

if [[ "$(native_source_hash)" != "${SOURCE_HASH}" ]]; then
    echo "ERROR: native sources changed during the build; rerun build-aar.sh." >&2
    exit 1
fi

echo "==> Generating verification metadata..."
METADATA_DIR="${ROOT_DIR}/build/reports/aar"
mkdir -p "${METADATA_DIR}"
AAR_HASH="$(shasum -a 256 "${STAGED_AAR}" | awk '{print $1}')"
printf '%s  %s\n' "${AAR_HASH}" "${OUTPUT_AAR}" > "${METADATA_DIR}/sha256.txt"
unzip -l "${STAGED_AAR}" > "${METADATA_DIR}/contents.txt"
echo "${JAVAP_OUT}" > "${METADATA_DIR}/signatures.txt"
go version -m "${TMP_VERIFY_DIR}/jni/arm64-v8a/libgojni.so" > "${METADATA_DIR}/go-version-m.txt"
"${READELF_BIN}" -l "${TMP_VERIFY_DIR}/jni/arm64-v8a/libgojni.so" > "${METADATA_DIR}/alignment-arm64.txt"
"${READELF_BIN}" -l "${TMP_VERIFY_DIR}/jni/x86_64/libgojni.so" > "${METADATA_DIR}/alignment-x86_64.txt"
"${READELF_BIN}" -n "${TMP_VERIFY_DIR}/jni/arm64-v8a/libgojni.so" > "${METADATA_DIR}/ndk-ident-arm64.txt" || true
"${READELF_BIN}" -n "${TMP_VERIFY_DIR}/jni/x86_64/libgojni.so" > "${METADATA_DIR}/ndk-ident-x86_64.txt" || true

echo "==> Installing verified AAR into app/libs..."
# Invalidate the recorded hashes first: if anything below fails, CI sees a
# mismatch instead of a stale AAR paired with valid-looking hashes.
: > "${ROOT_DIR}/app/libs/libtailcat.aar.sourcehash"
: > "${ROOT_DIR}/app/libs/libtailcat.aar.sha256"
cp "${STAGED_AAR}" "${OUTPUT_AAR}.tmp"
mv -f "${OUTPUT_AAR}.tmp" "${OUTPUT_AAR}"
printf '%s\n' "${AAR_HASH}" > "${ROOT_DIR}/app/libs/libtailcat.aar.sha256"
printf '%s\n' "${SOURCE_HASH}" > "${ROOT_DIR}/app/libs/libtailcat.aar.sourcehash"

echo "==> Build complete and verified successfully."
