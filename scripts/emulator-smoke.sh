#!/usr/bin/env bash
set -euo pipefail

apk=${1:-app/build/outputs/apk/debug/app-debug.apk}
package=dev.auredial.app
number=2025550123
output=$(mktemp -d)
fixture=scripts/fixtures/tts

adb() {
    command adb -e "$@"
}

trap 'adb emu gsm cancel "$number" >/dev/null 2>&1 || true; adb shell settings delete secure tts_default_synth >/dev/null 2>&1 || true; rm -rf "$output"' EXIT

retry() {
    local timeout_seconds=$1 deadline=$((SECONDS + $1))
    shift
    until "$@"; do
        if (( SECONDS >= deadline )); then
            printf 'Timed out after %ss waiting for %s\n' "$timeout_seconds" "$1" >&2
            adb emu gsm list >&2 || true
            adb shell dumpsys activity activities | sed -n '/ResumedActivity/p' >&2 || true
            if [[ -s "$output/ui.xml" ]]; then
                python3 - "$output/ui.xml" <<'PYTHON'
import sys
import xml.etree.ElementTree as ET
for node in ET.parse(sys.argv[1]).iter("node"):
    label = node.get("text") or node.get("content-desc")
    if label:
        print(node.get("package"), repr(label), "clickable=" + node.get("clickable", ""))
PYTHON
            fi
            adb logcat -d -b crash >&2 || true
            adb logcat -d -s 'TextToSpeech:I' 'AureDialTestVoice:I' >&2 || true
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

ui_node() {
    local label=$1 click=${2:-false} coords
    dump_ui || return 1
    coords=$(python3 - "$output/ui.xml" "$label" <<'PYTHON'
import re
import sys
import xml.etree.ElementTree as ET

try:
    root = ET.parse(sys.argv[1])
except ET.ParseError:
    sys.exit(1)

matching = [
    node for node in root.iter("node")
    if node.get("package") == "dev.auredial.app"
    and sys.argv[2] in (node.get("text"), node.get("content-desc"))
]
for node in sorted(matching, key=lambda node: node.get("clickable") != "true"):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    if x1 < x2 and y1 < y2:
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PYTHON
    ) || return 1
    if [[ $click == true ]]; then
        local x y
        read -r x y <<< "$coords"
        adb shell input tap "$x" "$y"
    fi
}

call_activity_resumed() {
    adb shell dumpsys activity activities > "$output/activity.txt" &&
        grep -Eq 'ResumedActivity[:=].*dev\.auredial\.app/\.CallActivity' "$output/activity.txt"
}

active_call() {
    local calls
    calls=$(adb emu gsm list) && grep -Eiq "$number.*active" <<< "$calls"
}

answer_call() {
    if active_call; then
        return 0
    fi
    ui_node "Answer" true || true
    return 1
}

call_ended() {
    local calls
    calls=$(adb emu gsm list) && [[ $calls != *"$number"* ]]
}

notification_present() {
    adb shell dumpsys notification > "$output/notifications.txt" &&
        grep -Eq 'NotificationRecord\(.*pkg=dev\.auredial\.app .*id=72 .*channel=incoming_v2' "$output/notifications.txt"
}

voice_called() {
    adb logcat -d -s 'AureDialTestVoice:I' > "$output/voice.txt" &&
        grep -Fq 'Incoming number reached offline voice' "$output/voice.txt"
}

install_voice_fixture() {
    local android_jar="$ANDROID_HOME/platforms/android-36/android.jar"
    local tools="$ANDROID_HOME/build-tools/35.0.0"
    mkdir -p "$output/classes" "$output/dex"
    javac --release 8 -classpath "$android_jar" -d "$output/classes" "$fixture/SmokeVoiceService.java"
    "$tools/d8" --min-api 29 --lib "$android_jar" --output "$output/dex" \
        "$output/classes/dev/auredial/testvoice/SmokeVoiceService.class"
    "$tools/aapt2" link -I "$android_jar" --manifest "$fixture/AndroidManifest.xml" \
        --min-sdk-version 29 --target-sdk-version 35 -o "$output/unsigned.apk"
    zip -q -j "$output/unsigned.apk" "$output/dex/classes.dex"
    keytool -genkeypair -storetype PKCS12 -keystore "$output/test-voice.p12" -storepass android \
        -alias test-voice -keyalg RSA -keysize 2048 -validity 2 -dname "CN=AureDial CI" >/dev/null 2>&1
    "$tools/apksigner" sign --ks "$output/test-voice.p12" --ks-pass pass:android \
        --out "$output/test-voice.apk" "$output/unsigned.apk"
    adb install -r "$output/test-voice.apk"
    adb shell settings put secure tts_default_synth dev.auredial.testvoice
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
keypad_passed=true
if retry 30 ui_node "+1$number"; then
    echo "PASS: ACTION_DIAL pre-fills the keypad"
else
    keypad_passed=false
fi

adb shell cmd role add-role-holder --user 0 android.app.role.DIALER "$package"
adb shell pm grant "$package" android.permission.POST_NOTIFICATIONS
install_voice_fixture

adb shell input keyevent KEYCODE_HOME
adb shell cmd appops set "$package" USE_FULL_SCREEN_INTENT deny
adb emu gsm call "$number"
retry 30 notification_present
retry 30 voice_called
if call_activity_resumed; then
    echo "Expected voice while the full-screen call activity was closed" >&2
    exit 1
fi
echo "PASS: call notification and offline voice with the call activity closed"
adb emu gsm cancel "$number"
retry 30 call_ended

adb shell cmd appops set "$package" USE_FULL_SCREEN_INTENT allow
adb shell input keyevent KEYCODE_SLEEP
sleep 1
adb emu gsm call "$number"
retry 30 call_activity_resumed
retry 30 ui_node "Decline"
if [[ -n ${AURE_TEST_SCREENSHOT:-} ]]; then
    adb exec-out screencap -p > "$AURE_TEST_SCREENSHOT"
fi
retry 30 ui_node "Decline" true
retry 30 call_ended
echo "PASS: a locked screen opens AureDial and Decline rejects the incoming call"

adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am start -W -n "$package/.MainActivity"
adb emu gsm call "$number"
retry 30 call_activity_resumed
retry 35 answer_call
retry 30 ui_node "End call" true
retry 30 call_ended
echo "PASS: Answer connects the call and End call disconnects it"

adb shell logcat -d -b crash > "$output/crashes.txt"
if grep -Fq "Process: $package," "$output/crashes.txt"; then
    cat "$output/crashes.txt"
    exit 1
fi
echo "PASS: no AureDial crash recorded"

[[ $keypad_passed == true ]]
