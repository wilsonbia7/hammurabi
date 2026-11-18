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
# Waits up to $2 seconds for $1 in the app's log.
seen() {
  i=0
  while [ "$i" -lt "$2" ]; do
    adb logcat -d -v time > logcat.txt
    grep -q "$1" logcat.txt && return 0
    sleep 5
    i=$((i + 5))
  done
  return 1
}
running() { adb shell dumpsys activity services "$pkg" | grep -q "CaptionService"; }
log() { grep -E "hammurabi|AndroidRuntime|FATAL" logcat.txt | grep -v "AppsFilter\|WindowManager\|CarrierSvc\|ImsResolver\|Accessibility" | tail -60; }

# Reading under translation on an emulator is slow.
if seen "screen: " 240; then echo "Hammurabi's own screen was read."; else echo "Nothing was read off the screen."; fail=1; fi
echo "---- services ----"
adb shell dumpsys activity services "$pkg" | grep -E "ServiceRecord|isForeground|types=" | head
echo "---- overlay windows ----"
adb shell dumpsys window windows | grep -E "Window\{.*$pkg" | head
log

echo "---- another app in front ----"
adb logcat -c
adb shell am start -W -n "$pkg.test/$pkg.TextActivity"
if seen "screen: Where did you put the keys" 240; then
  echo "The other app's English text was read."
else
  echo "The other app's text was not read."
  fail=1
fi
grep -q "screen: Bug" logcat.txt && { echo "The Turkish text was given on: wrong."; fail=1; }
log
running || { echo "Captions stopped."; fail=1; }

echo "---- stop ----"
adb shell am start -W -n "$pkg/.MainActivity"
sleep 3
tap "resource-id=\"$pkg:id/toggle\"" || { echo "No Stop button."; fail=1; }
sleep 5
if running; then echo "Still running after Stop."; fail=1; else echo "Stopped."; fi
adb shell dumpsys display | grep -q "Hammurabi screen" && { echo "The screen capture was left behind."; fail=1; }

echo "---- the Quick Settings tile ----"
adb logcat -c
adb shell cmd statusbar add-tile "$pkg/.CaptionTile"
sleep 2
# As a person does it: Quick Settings open, the tile tapped.
adb shell cmd statusbar expand-settings
sleep 3
adb shell cmd statusbar click-tile "$pkg/.CaptionTile"
sleep 5
if ui | grep -q 'package="com.android.systemui"'; then
  tap 'resource-id="android:id/button1"' || tap 'text="Start"' || tap 'text="Start now"'
fi
adb shell cmd statusbar collapse
adb shell am start -W -n "$pkg.test/$pkg.TextActivity"
if seen "screen: Where did you put the keys" 240; then echo "Started from the tile, reading."; else echo "The tile did not start reading."; fail=1; fi
adb shell cmd statusbar expand-settings
sleep 3
adb shell cmd statusbar click-tile "$pkg/.CaptionTile"
sleep 5
adb shell cmd statusbar collapse
if running; then echo "The tile did not stop captions."; fail=1; else echo "Stopped from the tile."; fi
adb logcat -d -v time > logcat.txt
grep "tile:" logcat.txt
log
adb shell pidof "$pkg" > /dev/null || echo "(Hammurabi's process has ended.)"
adb logcat -d -v time | grep -q "FATAL EXCEPTION.*$pkg\|Process: $pkg" && { echo "Hammurabi crashed."; fail=1; }

adb shell am start -W -n "$pkg.test/$pkg.TextActivity" > /dev/null
sleep 2
# The screen, a third of its size, as a PNG in base64 (Python's own zlib: no image tools needed).
adb exec-out screencap > screen.raw
python3 - << 'PY'
import base64, struct, zlib
raw = open("screen.raw", "rb").read()
w, h, _ = struct.unpack("<III", raw[:12])
# Android 9+ adds a colour space word to the header.
start = len(raw) - w * h * 4
rows = []
for y in range(0, h, 3):
    row = raw[start + y * w * 4 : start + (y + 1) * w * 4]
    rows.append(b"\0" + b"".join(row[x * 4 : x * 4 + 3] for x in range(0, w, 3)))
sw, sh = len(range(0, w, 3)), len(rows)
def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", sw, sh, 8, 2, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")
print("---- screen.png base64 ----")
print(base64.b64encode(png).decode())
print("---- end ----")
PY
exit $fail
