# Hammurabi for Android

Live captions and translation on a phone, with the desktop app's engine: the same voice detection,
Whisper, sentence assembly and translators, built for Android and run on the phone itself. Captions
show on the app's screen (for a conversation next to you) and over other apps (for a video).

Early work: it builds, but has been tried on few phones yet.

## How it works

- `app/` is a small Kotlin app with no libraries but ONNX Runtime. `CaptionService` records the
  microphone and/or what other apps play (`AudioPlaybackCapture`, Android 10+), 100 ms at a time.
- `rust/` is a JNI library around the `hammurabi` crate. The audio goes into
  `Engine::spawn_fed`, the engine the CLI also feeds, and its events come back as JSON. Captions use
  the JSON the desktop's browser source sends (`src/ui/web.rs`).
- `CaptionOverlay` draws captions over other apps, as the desktop overlay does; touches go through to
  the app below.

Nothing leaves the phone but the one-time model downloads from Hugging Face.

## Build

You need JDK 17, the Android SDK with the NDK (Android Studio installs both), and Rust:

```sh
rustup target add aarch64-linux-android
cargo install cargo-ndk
cd android
./gradlew assembleDebug        # also builds rust/ with cargo-ndk
adb install app/build/outputs/apk/debug/app-debug.apk
```

The NDK is found through `ANDROID_NDK_HOME`, or under the SDK. CI (`.github/workflows/android.yml`)
builds the same APK and keeps it as an artifact.

## What it cannot do

- Apps that forbid capture (Netflix and most paid video, banking apps) give silence. YouTube and
  most others can be heard.
- Calls are never captured: Android keeps them out of playback capture.
- The speech model runs on the processor. "Hearing takes … ms" under the button shows how long one
  run takes; above about a second the phone falls behind. Tiny is the model for most phones.
- The built-in translator (MADLAD, 1.7 GB) is heavy for a phone: it needs the memory and is slow on
  mid-range processors.
- Read aloud, Live Captions and reading subtitles off the screen are desktop-only.
