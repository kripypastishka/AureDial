#!/usr/bin/env bash
set -euo pipefail

apk=${1:-app/build/outputs/apk/debug/app-debug.apk}
package=dev.auredial.app
number=2025550123
output=$(mktemp -d)
trap 'adb emu gsm cancel "$number" >/dev/null 2>&1 || true; rm -rf "$output"' EXIT

retry() {
    local timeout_seconds=$1 deadline=$((SECONDS + $1))
    shift
    until "$@"; do
        if (( SECONDS >= deadline )); then
            printf 'Timed out after %ss waiting for %s\n' "$timeout_seconds" "$1" >&2
            return 1
        fi
        sleep 2
    done
}

booted() {
    [[ $(adb shell getprop sys.boot_completed | tr -d '\r') == 1 ]]
}

dump_ui() {
    adb shell uiautomator dump /data/local/tmp/auredial-smoke.xml >/dev/null 2>&1 &&
        adb exec-out cat /data/local/tmp/auredial-smoke.xml > "$output/ui.xml"
}

has_number_in_keypad() {
    dump_ui && python3 - "$output/ui.xml" "$number" <<'PY'
import sys
import xml.etree.ElementTree as ET

nodes = ET.parse(sys.argv[1]).iter("node")
assert any(node.get("package") == "dev.auredial.app"
           and node.get("text") == "+1" + sys.argv[2] for node in nodes)
PY
}

notification_present() {
    adb shell dumpsys notification > "$output/notifications.txt" &&
        grep -Eq 'NotificationRecord\(.*pkg=dev\.auredial\.app .*id=72 .*channel=incoming_v2' "$output/notifications.txt"
}

timeout 240 adb wait-for-device
retry 240 booted
adb install -r "$apk"
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

adb shell am start -W -a android.intent.action.DIAL -d "tel:+1$number" -n "$package/.MainActivity"
retry 45 has_number_in_keypad
echo "PASS: ACTION_DIAL pre-fills the keypad"

adb shell cmd role add-role-holder --user 0 android.app.role.DIALER "$package"
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS
adb emu gsm call "$number"
retry 45 notification_present
echo "PASS: incoming Telecom call posts AureDial's call notification"

adb emu gsm cancel "$number"
adb shell logcat -d -b crash > "$output/crashes.txt"
if grep -Fq "Process: $package," "$output/crashes.txt"; then
    cat "$output/crashes.txt"
    exit 1
fi
echo "PASS: no AureDial crash recorded"
