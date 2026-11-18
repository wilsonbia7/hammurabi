# Hammurabi for Android

Translate what is on the phone's screen without leaving the app it is in: a post, comments, a meme,
a game. Started once, a small bubble stays at the screen's edge over every app.

- **Tap the bubble**: the screen is translated in place, as Google Lens does. Each block of text is
  covered with its translation, in its own colours; text already in the chosen language is left as
  it is. Tap a translation to see its original and copy it; tap anywhere else, or Back, to close.
- **Hold the bubble**: its menu — translate the screen, automatic subtitles on or off, the language,
  open the app, close Hammurabi. Drag it to move it; it goes back to the nearest edge.
- **Automatic subtitles** (off at first): speech in videos and games (the phone's sound) or people
  nearby (the microphone), heard with the desktop app's engine and translated at the bottom middle,
  like a film's subtitles.

A Quick Settings tile turns Hammurabi on and off from any app (Android 13 offers to add it).

Early work: tried on emulators and few phones yet.

## How it works

- `app/` is a small Kotlin app. `CaptionService` runs while Hammurabi is on: the bubble
  (`Bubble.kt`), the screen sharing (MediaProjection), and subtitles when on.
- Translating the screen: as the bubble is pressed, `ScreenCapture` starts mirroring the screen; the
  bubble goes, the empty `TranslationLayer` comes over the screen, and the picture after that is
  taken (the mirror sleeps again between taps, so it costs nothing the rest of the time).
  `ScreenTranslator` reads it with Google's ML Kit (text recognition, bundled: Latin, or Chinese,
  Japanese or Korean with Latin), tells each block's language (ML Kit language identification;
  short blocks take the screen's main language), and translates those not in the target language
  with ML Kit's on-device translation, top to bottom. `TranslationLayer` draws each translation over
  its block in the block's colours, fitted to its size, as it comes.
- Subtitles: `rust/` is a JNI library around the `hammurabi` crate. The audio goes into
  `Engine::spawn_fed`, the engine the CLI also feeds; it only listens (Whisper). Its events come
  back as JSON, as the desktop's browser source sends them (`src/ui/web.rs`), with the language
  heard; `SubtitleTranslator` translates them with ML Kit, and `CaptionOverlay` shows them.
- `AppLog` keeps the app's own log in a file (crashes too); "Copy the error log" on the main screen
  copies it with the phone and the settings, to send when something goes wrong.

Nothing leaves the phone but the one-time model downloads: a translation model per language (about
30 MB, from Google) and the speech model (from Hugging Face).

ML Kit is Google's and not open source; its on-device translation may be used on phones, tablets and
computers, not on TVs, cars or other embedded devices, and its translations carry Google's
attribution ("powered by Google Translate") and disclaimer, as its terms ask.

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
builds the same APK and keeps it as an artifact. Every build is signed with `app/debug.keystore`, a
debug key, so a new APK installs over the old one and keeps the downloaded models; a store release
needs a key of its own.

## Tests

- `cd rust && cargo test`: the bridge's settings and events.
- `./gradlew connectedDebugAndroidTest` on a phone or an emulator (`app/src/androidTest`): the
  settings and languages, how lines become paragraphs, the subtitles, the engine through the app's
  native calls, the screen translated end to end with ML Kit on a drawn feed (English translated,
  Turkish left, numbers skipped), subtitles translated as they come, and the translated layer over
  another app, whose picture goes to `files/layer.jpg`.
- CI runs those on an emulator, then uses the app as a person would (`smoke.sh`): starts it, taps
  the bubble over another app and checks its English is translated, closes the translation with
  Back, holds the bubble for its menu, stops, and starts and stops from the Quick Settings tile.
  Lint runs on every build.

## What it cannot do

- Apps that forbid capture (Netflix and most paid video, banking apps) come out black, and their
  sound silent.
- Cyrillic (Russian) and Arabic letters cannot be read: ML Kit's text recognition has no such
  models.
- Calls are never captured: Android keeps them out of playback capture.
- The speech model runs on the processor. "Hearing time … ms" on the main screen shows how long one
  run takes; above about a second the phone falls behind. Tiny is the model for most phones.
- Screen sharing ends when Android ends it (the screen locked, or stopped from the status bar); the
  bubble then shows a red dot, and a tap asks for it again.
- Read aloud and Windows Live Captions are desktop-only.
