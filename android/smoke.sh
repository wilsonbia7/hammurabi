#!/bin/sh
# On the emulator CI starts (android.yml): the on-device tests (src/androidTest), then the app as a
# person uses it: started, the bubble tapped over another app, the screen translated, the menu held
# open, stopped, and the same from the Quick Settings tile. Fails on any failure, with Android's log;
# pictures (the subtitles, the translated screen) go in the log as JPEG/PNG in base64.
set -u
apk="$1"
tests="$2"
pkg=dev.menzo.hammurabi
fail=0

adb install -r "$apk" || exit 1
adb install -r "$tests" || exit 1
adb logcat -c

# A file the app left, in base64 lines of 1000: the emulator action's output takes no line of
# hundreds of kilobytes.
picture() {
  if adb shell run-as "$pkg" cat "files/$1" > "$1" 2> /dev/null && [ -s "$1" ]; then
    echo "---- $1 base64 ----"
    base64 -w 1000 "$1"
    echo "---- end ----"
  fi
}

echo "---- on-device tests ----"
adb shell am instrument -w "$pkg.test/androidx.test.runner.AndroidJUnitRunner" > instrument.txt 2>&1
cat instrument.txt
grep -q "^OK (" instrument.txt || { echo "On-device tests failed."; fail=1; }
picture overlay.jpg
picture layer.jpg
adb logcat -d -v time | grep -E "hammurabi|AndroidRuntime|MlKit|TranslateJni" | grep -v "AppsFilter\|WindowManager\|CarrierSvc\|ImsResolver" | tail -150
adb logcat -c

echo "---- the app as a person uses it ----"
adb shell am force-stop "$pkg"
adb shell input keyevent KEYCODE_HOME
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
adb shell appops set "$pkg" SYSTEM_ALERT_WINDOW allow
# Screen sharing without its dialog, as Android's own tests do; the dialog is tapped through if it shows.
adb shell appops set "$pkg" PROJECT_MEDIA allow
# Into Turkish (the emulator speaks English, the default), subtitles off.
cat > settings.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="target">tr</string>
    <boolean name="subtitles" value="false" />
</map>
XML
adb push settings.xml /data/local/tmp/settings.xml > /dev/null
adb shell "run-as $pkg sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/settings.xml shared_prefs/settings.xml && rm -f shared_prefs/bubble.xml'"

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
# Android's screen-sharing dialog, when it shows.
consent() {
  sleep 4
  if ui | grep -q 'package="com.android.systemui"'; then
    echo "Android asks for screen sharing; tapping Start."
    tap 'resource-id="android:id/button1"' || tap 'text="Start"' || tap 'text="Start now"'
  fi
}
# The screen, a third of its size, as a PNG in base64 (Python's own zlib: no image tools needed).
shot() {
  adb exec-out screencap > screen.raw
  python3 - "$1" << 'PY'
import base64, struct, sys, zlib
raw = open("screen.raw", "rb").read()
w, h, _ = struct.unpack("<III", raw[:12])
# Android 9+ adds a colour space word to the header.
start = len(raw) - w * h * 4
step = 3
rows = []
for y in range(0, h, step):
    row = raw[start + y * w * 4 : start + (y + 1) * w * 4]
    rows.append(b"\0" + b"".join(row[x * 4 : x * 4 + 3] for x in range(0, w, step)))
sw, sh = len(range(0, w, step)), len(rows)
def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", sw, sh, 8, 2, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(b"".join(rows), 9)) + chunk(b"IEND", b"")
print("---- " + sys.argv[1] + " base64 ----")
b64 = base64.b64encode(png).decode()
for i in range(0, len(b64), 1000):
    print(b64[i : i + 1000])
print("---- end ----")
PY
}
# Waits up to $2 seconds for $1 in the app's log.
seen() {
  i=0
  while [ "$i" -lt "$2" ]; do
    adb logcat -d -v time > logcat.txt
    grep -q "$1" logcat.txt && return 0
    sleep 3
    i=$((i + 3))
  done
  return 1
}
running() { adb shell dumpsys activity services "$pkg" | grep -q "CaptionService"; }
log() { grep -E "hammurabi|AndroidRuntime|FATAL" logcat.txt | grep -v "AppsFilter\|WindowManager\|CarrierSvc\|ImsResolver\|Accessibility" | tail -60; }
# The bubble's middle, from where the app says it put it.
bubble() {
  set -- $(grep "bubble: shown at" logcat.txt | tail -1 | sed -n 's/.*shown at \([0-9-]*\),\([0-9-]*\) size \([0-9]*\).*/\1 \2 \3/p')
  [ $# -eq 3 ] || return 1
  echo "$(($1 + $3 / 2)) $(($2 + $3 / 2))"
}

# In Turkish, as on the phone it is made for (Android 13's per-app language).
adb shell cmd locale set-app-locales "$pkg" --locales tr-TR
adb shell am start -W -n "$pkg/.MainActivity"
sleep 6
tap "resource-id=\"$pkg:id/toggle\"" || { echo "No Start button."; fail=1; }
consent
if seen "bubble: shown" 30; then echo "The bubble shows."; else echo "No bubble."; fail=1; fi
log
at=$(bubble)
echo "The bubble is at $at."
# The main screen, on and scrolled down: how it looks.
sleep 2
shot main.png
adb shell input swipe 540 1900 540 500 400
sleep 1
shot main2.png
adb shell input swipe 540 1900 540 300 400
sleep 1
shot main3.png

echo "---- another app in front, the bubble tapped ----"
adb logcat -c
adb shell am start -W -n "$pkg.test/$pkg.TextActivity"
sleep 3
front=$(adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity')
echo "In front: $front"
echo "$front" | grep -q TextActivity || { echo "The other app is not in front."; fail=1; }
[ -n "$at" ] && adb shell input tap $at
# Models download the first time on an emulator; it is slow.
if seen "translate: done" 300; then
  grep "translate:" logcat.txt
  if grep -q "translate: done, [1-9][0-9]* of" logcat.txt; then echo "The other app's English was translated."; else echo "Nothing was translated."; fail=1; fi
else
  echo "The screen was not translated."
  fail=1
fi
grep -q "translate: languages.*tr=" logcat.txt || { echo "The Turkish post was not told apart."; fail=1; }
grep -E "ActivityTaskManager|AndroidRuntime" logcat.txt | grep -v "Accessibility" | head -20
sleep 2
shot translated.png
adb shell input keyevent KEYCODE_BACK
if seen "translate: closed" 10; then echo "Back closes the translation."; else echo "Back did not close the translation."; fail=1; fi

echo "---- the bubble held: its menu ----"
adb logcat -c
[ -n "$at" ] && adb shell input swipe $at $at 1200
if seen "menu: shown" 10; then echo "The menu shows."; else echo "No menu."; fail=1; fi
# A tap outside closes it.
adb shell input tap 60 300
sleep 1
log
running || { echo "Hammurabi stopped."; fail=1; }

echo "---- stop ----"
adb shell am start -W -n "$pkg/.MainActivity"
sleep 3
tap "resource-id=\"$pkg:id/toggle\"" || { echo "No Stop button."; fail=1; }
sleep 5
if running; then echo "Still running after Stop."; fail=1; else echo "Stopped."; fi
adb shell dumpsys display | grep -q "Hammurabi screen" && { echo "The screen capture was left behind."; fail=1; }

echo "---- the Quick Settings tile ----"
adb logcat -c
adb shell input keyevent KEYCODE_HOME
adb shell cmd statusbar add-tile "$pkg/.CaptionTile"
sleep 2
# As a person does it: Quick Settings open, the tile tapped.
adb shell cmd statusbar expand-settings
sleep 3
adb shell cmd statusbar click-tile "$pkg/.CaptionTile"
consent
adb shell cmd statusbar collapse
if seen "bubble: shown" 30; then echo "Started from the tile."; else echo "The tile did not start Hammurabi."; fail=1; fi
adb shell cmd statusbar expand-settings
sleep 3
adb shell cmd statusbar click-tile "$pkg/.CaptionTile"
sleep 5
adb shell cmd statusbar collapse
if running; then echo "The tile did not stop Hammurabi."; fail=1; else echo "Stopped from the tile."; fi
adb logcat -d -v time > logcat.txt
grep "tile:" logcat.txt
log
adb logcat -d -v time | grep -q "FATAL EXCEPTION.*$pkg\|Process: $pkg" && { echo "Hammurabi crashed."; fail=1; }
echo "---- the app's own log ----"
adb shell run-as "$pkg" cat files/log.txt | tail -80
exit $fail
