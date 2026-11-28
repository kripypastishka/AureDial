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
            adb shell cmd notification list >&2 || true
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
    local label=$1 click=${2:-false} owner=${3:-$package} coords
    dump_ui || return 1
    coords=$(python3 - "$output/ui.xml" "$label" "$owner" <<'PYTHON'
import re
import sys
import xml.etree.ElementTree as ET

try:
    root = ET.parse(sys.argv[1])
except ET.ParseError:
    sys.exit(1)

matching = [
    node for node in root.iter("node")
    if node.get("package") == sys.argv[3]
    and any(
        sys.argv[2] == value or sys.argv[2] in re.split(r", |\n", value or "")
        for value in (node.get("text"), node.get("content-desc"))
    )
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

ui_scroll_to() {
    local width height remaining=5
    read -r width height < <(adb shell wm size | awk -F '[: x]+' 'END {print $(NF-1), $NF+0}')
    while ! ui_node "$@"; do
        if (( remaining == 0 )); then return 1; fi
        adb shell input swipe "$((width / 2))" "$((height * 78 / 100))" "$((width / 2))" "$((height * 39 / 100))" 300
        remaining=$((remaining - 1))
    done
}

call_activity_resumed() {
    adb shell dumpsys activity activities > "$output/activity.txt" &&
        grep -Eq 'ResumedActivity[:=].*dev\.auredial\.app/\.CallActivity' "$output/activity.txt"
}

answer_call() {
    if ui_node "Call in progress"; then
        return 0
    fi
    ui_node "Answer" true || true
    return 1
}

call_ended() {
    local notifications
    notifications=$(adb shell cmd notification list) || return 1
    if grep -Fq '|dev.auredial.app|72|' <<< "$notifications"; then
        return 1
    fi
    ! call_activity_resumed
}

notification_present() {
    adb shell dumpsys notification > "$output/notifications.txt" &&
        grep -Eq 'NotificationRecord\(.*pkg=dev\.auredial\.app .*id=72 .*channel=incoming_v2' "$output/notifications.txt"
}

voice_called() {
    adb logcat -d -s 'AureDialTestVoice:I' > "$output/voice.txt" &&
        grep -Fq 'Incoming number reached offline voice' "$output/voice.txt"
}

capture() {
    if [[ -n ${AURE_TEST_SCREENSHOTS:-} ]]; then
        mkdir -p "$AURE_TEST_SCREENSHOTS"
        adb exec-out screencap -p > "$AURE_TEST_SCREENSHOTS/$1.png"
    fi
}

preview_called() {
    adb logcat -d -s 'AureDialTestVoice:I' > "$output/voice.txt" &&
        grep -Fq 'Preview reached offline voice' "$output/voice.txt"
}

install_voice_fixture() {
    local android_jar="$ANDROID_HOME/platforms/android-36/android.jar"
    local tools="$ANDROID_HOME/build-tools/35.0.0"
    mkdir -p "$output/classes" "$output/dex"
    javac --release 8 -classpath "$android_jar" -d "$output/classes" "$fixture/"*.java
    "$tools/d8" --min-api 29 --lib "$android_jar" --output "$output/dex" \
        "$output/classes/dev/auredial/testvoice/"*.class
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
    capture keypad
    retry 25 ui_node "5" true
    retry 25 ui_node "+1${number}5"
    retry 25 ui_node "Delete digit" true
    retry 25 ui_node "+1$number"
    echo "PASS: ACTION_DIAL pre-fills the keypad, and digit entry and deletion update it"
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
adb shell cmd statusbar expand-notifications
retry 35 ui_node "Answer" true com.android.systemui
retry 30 call_activity_resumed
retry 30 ui_node "Call in progress"
capture connected-from-notification
retry 30 ui_node "Mute" true
retry 30 ui_node "Unmute" true
retry 30 ui_node "Mute"
retry 30 ui_node "End call" true
retry 30 call_ended
echo "PASS: Answer in the system notification opens the connected call UI, controls the microphone and ends the call"

adb shell cmd appops set "$package" USE_FULL_SCREEN_INTENT allow
adb shell input keyevent KEYCODE_SLEEP
sleep 1
adb emu gsm call "$number"
retry 30 call_activity_resumed
retry 30 ui_node "Decline"
capture incoming-locked
retry 30 ui_node "Decline" true
retry 30 call_ended
echo "PASS: a locked screen opens AureDial and Decline rejects the incoming call"

adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell am start -W -n "$package/.MainActivity"
adb emu gsm call "$number"
retry 30 call_activity_resumed
retry 35 answer_call
capture connected
retry 30 ui_node "Audio output" true
retry 30 ui_node "Speaker"
capture audio-output
adb shell input keyevent KEYCODE_BACK
retry 30 ui_node "Dial" true
capture call-keypad
retry 30 ui_node "5" true
retry 30 ui_node "Close keypad" true
retry 30 ui_node "End call" true
retry 30 call_ended
echo "PASS: Answer connects the call and End call disconnects it"

adb shell pm grant dev.auredial.testvoice android.permission.WRITE_CONTACTS
adb shell pm grant "$package" android.permission.READ_CONTACTS
adb shell am start -W -n dev.auredial.testvoice/.SeedContactsActivity
adb shell am force-stop "$package"
adb shell am start -W -n "$package/.MainActivity"
retry 30 ui_node "Recents"
retry 30 ui_node "$number"
capture recents
retry 30 ui_node "Missed" true
retry 30 ui_node "All" true

# Exercise navigation and sheets with motion enabled after testing call controls
# at Android's reduced-motion setting.
adb shell settings put global animator_duration_scale 1
retry 30 ui_node "Contacts" true
retry 30 ui_node "All contacts"
retry 30 ui_node "Atlas Support"
retry 30 ui_node "Mira Chen"
capture contacts
retry 30 ui_node "Favorites" true
retry 30 ui_node "Mira Chen"
if ui_node "Atlas Support"; then
    echo "Non-favorite contact shown in Favorites" >&2
    exit 1
fi
capture favorites
retry 30 ui_node "Mira Chen" true
retry 30 ui_node "Send message"
retry 30 ui_node "Edit contact"
capture number-details
retry 30 ui_node "Block" true
retry 30 ui_node "Block this number?"
capture block-confirmation
retry 30 ui_node "Block" true
retry 30 ui_node "Protection" true
retry 30 ui_node "Call protection"
capture protection
retry 30 ui_scroll_to "Blocked numbers"
retry 30 ui_scroll_to "+12025550188"
capture blocked-numbers
retry 30 ui_scroll_to "Remove +12025550188" true
retry 30 ui_scroll_to "No entries"
echo "PASS: favorites, number details and block-list changes"

retry 30 ui_node "Dial" true
for digit in 6 4 7 2; do
    retry 25 ui_node "$digit" true
done
retry 30 ui_scroll_to "Mira Chen" true
retry 30 ui_node "+12025550188"
adb shell input keyevent KEYCODE_BACK
echo "PASS: T9 selects a matching contact and fills the redesigned keypad"

retry 30 ui_node "Settings" true
retry 30 ui_node "Caller announcement"
capture settings
retry 30 ui_node "Incoming call setup" true
retry 30 ui_node "Contacts and SIM"
capture call-setup
adb shell input keyevent KEYCODE_BACK
retry 30 ui_node "Call recording" true
retry 30 ui_node "Why is recording unavailable?" true
retry 30 ui_node "Close"
capture recording
retry 30 ui_node "Close" true
adb shell input keyevent KEYCODE_BACK
retry 30 ui_node "Caller announcement" true
retry 30 ui_node "Play test announcement" true
retry 30 preview_called
capture voice
retry 30 ui_scroll_to "Voice settings" true
retry 30 ui_scroll_to "Speech engine"
capture voice-options
retry 30 ui_node "Speech engine" true
retry 30 ui_node "System default"
capture voice-selection
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_BACK
echo "PASS: call setup, recording information, voice playback and engine picker"

adb shell cmd uimode night yes
retry 30 ui_node "Recents" true
retry 30 ui_node "Search"
capture recents-dark
adb shell cmd locale set-app-locales "$package" --user 0 --locales ru-RU
retry 30 ui_node "Недавние"
capture recents-ru-dark
adb shell settings put system font_scale 1.35
retry 30 ui_node "Контакты" true
retry 30 ui_node "Все контакты"
capture contacts-ru-large
echo "PASS: the Russian app locale, dark theme and larger text"

adb shell logcat -d -b crash > "$output/crashes.txt"
if grep -Fq "Process: $package," "$output/crashes.txt"; then
    cat "$output/crashes.txt"
    exit 1
fi
echo "PASS: no AureDial crash recorded"

[[ $keypad_passed == true ]]
