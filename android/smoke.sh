#!/bin/sh
# Installs the APK on the emulator CI starts (android.yml), opens the app, and fails when it is not
# running after a while: a crash at start shows here, with Android's log of it.
set -u
apk="$1"
adb install -r "$apk"
adb logcat -c
adb shell am start -W -n dev.menzo.hammurabi/.MainActivity
sleep 20
adb logcat -d -v time > logcat.txt
echo "---- the app's log, and crashes ----"
grep -E "AndroidRuntime|FATAL|hammurabi|DEBUG|libc|ActivityManager.*dev.menzo" logcat.txt | tail -300
if adb shell pidof dev.menzo.hammurabi > /dev/null; then
  echo "Hammurabi is running."
else
  echo "Hammurabi is not running: it closed at start."
  exit 1
fi
