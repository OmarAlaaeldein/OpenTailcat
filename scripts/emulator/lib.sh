# Shared helpers for scripts/emulator. Source it; do not run it.
# Needs adb (one attached device or emulator) and python3 on PATH.

PKG=${PKG:-com.tailcat.vpn}
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# ui_nodes prints "bounds[ clickable] | text | content-desc" for every node
# that shows text. Anything shaped like a Tailcat token is redacted.
ui_nodes() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  adb shell cat /sdcard/ui.xml | python3 -c '
import re, sys
s = sys.stdin.read()
redact = lambda v: re.sub(r"tc[A-Za-z0-9_-]{30,}", "<token redacted>", v)
for m in re.finditer(r"<node [^>]*>", s):
    n = m.group(0)
    text = redact(re.search(r" text=\"([^\"]*)\"", n).group(1))
    desc = redact(re.search(r" content-desc=\"([^\"]*)\"", n).group(1))
    bounds = re.search(r" bounds=\"([^\"]*)\"", n).group(1)
    click = " clickable" if "clickable=\"true\"" in n else ""
    if text or desc:
        print(f"{bounds}{click} | {text} | {desc}")
'
}

# tap_node PATTERN taps the centre of the first node line matching the
# extended regex PATTERN. Returns 1 when nothing matches.
tap_node() {
  local line x1 y1 x2 y2
  line=$(ui_nodes | grep -E -- "$1" | head -1 || true)
  [ -n "$line" ] || return 1
  local y
  read -r x1 y1 x2 y2 <<<"$(printf '%s\n' "$line" | sed -E 's/^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\].*/\1 \2 \3 \4/')"
  y=$(( (y1 + y2) / 2 ))
  # The status bar swallows taps near the top edge (toolbar icons centre
  # around y=127); aim at the bottom of such nodes instead.
  if [ "$y" -lt 150 ] && [ $(( y2 - 8 )) -gt "$y" ]; then y=$(( y2 - 8 )); fi
  adb shell input tap $(( (x1 + x2) / 2 )) "$y"
}

# tunnel_state prints the status label on the home screen (CONNECTED,
# RECONNECTING..., DEGRADED • ..., TAP TO CONNECT, ...), or nothing.
tunnel_state() {
  ui_nodes | sed -n -E 's/^[^|]*\| ((CONNECTED|CONNECTING|DISCONNECTED|RECONNECTING|DEGRADED|TAP TO CONNECT)[^|]*) \|.*/\1/p' | head -1 || true
}

# app_uid prints the Linux uid of the installed app.
app_uid() {
  adb shell dumpsys package "$PKG" | sed -n -E 's/.*(userId|appId)=([0-9]+).*/\2/p' | head -1
}

# route_dev prints the interface the shell uid (2000) uses to reach DEST
# (default 1.1.1.1): tun* while the VPN is up, else wlan0/eth0/...
route_dev() {
  adb shell ip route get "${1:-1.1.1.1}" uid 2000 | tr -d '\r' | sed -n -E 's/.* dev ([^ ]+).*/\1/p' | head -1
}

# install_test_apk installs the instrumentation APK built by
# ./gradlew assembleDebugAndroidTest.
install_test_apk() {
  local apk
  apk=$(ls "$ROOT"/app/build/outputs/apk/androidTest/debug/*.apk 2>/dev/null | head -1 || true)
  if [ -z "$apk" ]; then
    echo "no androidTest APK; run ./gradlew assembleDebugAndroidTest" >&2
    return 1
  fi
  adb install -r "$apk" >/dev/null
}

# run_instrumented CLASS [ARGS...] runs one instrumented test class (extra
# args go to am instrument, e.g. -e key value) and fails unless it reports OK.
# Token-shaped text is dropped from failure output.
run_instrumented() {
  local class=$1 out
  shift
  out=$(adb shell am instrument -w -e class "$class" "$@" \
    "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r')
  if ! grep -q '^OK (' <<<"$out"; then
    printf '%s\n' "$out" | grep -v -E 'tc[A-Za-z0-9_-]{30,}' | tail -40 >&2
    return 1
  fi
  echo "$class: $(grep '^OK (' <<<"$out")"
}

# has_mobile_data reports whether a cellular network is up besides Wi-Fi.
has_mobile_data() {
  adb shell dumpsys connectivity | grep -E 'NetworkAgentInfo\{' | grep -q -E 'Transports: CELLULAR([^|A-Z]|$)'
}

# tcp_probe sends HTTP HEAD to 1.1.1.1:80 from the shell uid and prints the
# status line (tunneled while the VPN is up).
tcp_probe() {
  adb shell 'printf "HEAD / HTTP/1.0\r\nHost: 1.1.1.1\r\n\r\n" | toybox nc -w 8 1.1.1.1 80 2>&1 | head -1' | tr -d '\r'
}
