#!/bin/sh
# On the emulator CI starts (android.yml): the on-device tests (src/androidTest), then the app as a
# person uses it: opened, Text on screen started, the screen read. Fails on any failure, with
# Android's log; the screen at the end goes in the log as a small PNG in base64.
set -u
apk="$1"
tests="$2"
pkg=dev.menzo.hammurabi
fail=0

adb install -r "$apk" || exit 1
adb install -r "$tests" || exit 1
adb logcat -c

echo "---- on-device tests ----"
adb shell am instrument -w "$pkg.test/androidx.test.runner.AndroidJUnitRunner" > instrument.txt 2>&1
cat instrument.txt
grep -q "^OK (" instrument.txt || { echo "On-device tests failed."; fail=1; }
adb logcat -d -v time | grep -E "hammurabi|AndroidRuntime" | grep -v "AppsFilter\|WindowManager\|CarrierSvc\|ImsResolver" | tail -150
adb logcat -c

echo "---- the app as a person uses it ----"
adb shell am force-stop "$pkg"
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
adb shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow
# Screen sharing without its dialog, as Android's own tests do; the dialog is tapped through if it shows.
adb shell appops set "$pkg" PROJECT_MEDIA allow
# Reading the screen into Turkish, without the translator (it would download 1.7 GB): what is read
# shows in the log ("screen: ...").
cat > choices.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="source">SCREEN</string>
    <boolean name="translate" value="false" />
    <string name="target">tr</string>
    <boolean name="overlay" value="true" />
</map>
XML
adb push choices.xml /data/local/tmp/choices.xml > /dev/null
adb shell "run-as $pkg sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/choices.xml shared_prefs/choices.xml'"

ui() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb shell cat /sdcard/ui.xml
}
# Taps the middle of the first node matching $1 in the screen's layout.
tap() {
  b=$(ui | tr '>' '\n' | grep -m1 "$1" | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p')
  [ -n "$b" ] || return 1
  set -- $b
  adb shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
}

adb shell am start -W -n "$pkg/.MainActivity"
sleep 8
tap "resource-id=\"$pkg:id/toggle\"" || { echo "No Start button."; fail=1; }
sleep 5
if ui | grep -q 'package="com.android.systemui"'; then
  echo "Android asks for screen sharing; tapping Start."
  tap 'resource-id="android:id/button1"' || tap 'text="Start"' || tap 'text="Start now"'
fi
# Reading under translation on an emulator is slow.
sleep 90
echo "---- services ----"
adb shell dumpsys activity services "$pkg" | grep -E "ServiceRecord|isForeground|types=" | head
echo "---- overlay windows ----"
adb shell dumpsys window windows | grep -E "Window\{.*$pkg" | head
adb logcat -d -v time > logcat.txt
grep -E "hammurabi|AndroidRuntime|FATAL" logcat.txt | grep -v "AppsFilter\|WindowManager\|CarrierSvc\|ImsResolver" | tail -200
if grep -q "screen: " logcat.txt; then
  echo "The screen was read."
else
  echo "Nothing was read off the screen."
  fail=1
fi
adb shell pidof "$pkg" > /dev/null || { echo "Hammurabi is not running."; fail=1; }

adb exec-out screencap -p > screen.png
if command -v convert > /dev/null; then convert screen.png -resize 360x screen.png; fi
echo "---- screen.png base64 ----"
base64 -w 0 screen.png
echo
echo "---- end ----"
exit $fail
