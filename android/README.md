# Hammurabi for Android

Translate what is on the phone's screen without leaving the app it is in: a post, comments, a meme,
a game. Started once, a small bubble stays at the screen's edge over every app.

- **Tap the bubble**: the screen is translated in place, as Google Lens does. Each block of text is
  covered with its translation, in its own colours; text already in the chosen language is left as
  it is. Tap a translation to see its original and copy it; tap anywhere else, or Back, to close.
- **Hold the bubble**: its menu — translate the screen, live translation on or off and the area it
  watches, automatic subtitles on or off, the language, open the app, close Hammurabi. Drag it to move
  it; it goes back to the nearest edge.
- **Live translation** (videos and games, off at first): the bottom of the screen (or the top, all of
  it, or an area drawn over the app) is read about four times a second. A subtitle, or a game's
  dialogue once it is all written out, is translated and shown just above it in a subtitle box fitted
  to the screen, until it changes or goes; touches go through to the video or the game. Text in
  capitals is made sentences first, which translates far better.
- **Automatic subtitles** (off at first): speech in videos and games (the phone's sound) or people
  nearby (the microphone), heard with the desktop app's engine and translated at the bottom middle,
  like a film's subtitles. When the phone's sound comes silent while something plays (a muted video,
  or an app that keeps its sound to itself), the microphone listens instead until it is heard again.

A Quick Settings tile turns Hammurabi on and off from any app (Android 13 offers to add it).

Android 8 to 16, on 64-bit and 32-bit ARM phones and x86_64 (emulators, Chromebooks); tried on a
Galaxy S25 and on emulators of every version from 8 to 16.

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
- Live translation: while it runs, `ScreenCapture` keeps the screen mirrored and hands
  `LiveTranslator` the watched area of each new picture, at most every 250 ms. It reads the text,
  puts lines together (`LiveText`: a centred subtitle's, a dialogue box's), waits until the text
  reads the same twice (not while it is still being written out), translates it, and `LiveOverlay`
  shows it in a box above the text, in a window touches go through. The boxes are left out of what
  is read; a picture like the last is not read again.
- A screen that comes out pure black (an app with FLAG_SECURE: Netflix, banks) is told as such.
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

The NDK is found through `ANDROID_NDK_HOME`, or under the SDK. The engine is built for each ABI
(`rustup target add armv7-linux-androideabi x86_64-linux-android` too), or for those of
`-PrustAbis=arm64-v8a`. A phone of an ABI without it still translates the screen. The build makes an
APK per ABI and a universal one. CI (`.github/workflows/android.yml`) builds the same APKs and keeps
them as artifacts: `hammurabi-android` holds the universal APK and the 64-bit ARM one. Every build is signed with `app/debug.keystore`, a
debug key, so a new APK installs over the old one and keeps the downloaded models; a store release
needs a key of its own.

## Tests

- `cd rust && cargo test`: the bridge's settings and events.
- CI runs everything below on emulators of Android 8, 9, 10, 11, 12, 13, 14, 15 and 16, with the
  x86_64 APK, and with the 64-bit ARM one on Android 11 and 14.
- `./gradlew connectedDebugAndroidTest` on a phone or an emulator (`app/src/androidTest`): the
  settings and languages, how lines become paragraphs, live translation's rules (lines put together,
  a line still being written out, capitals) and its whole flow with ML Kit, the subtitles, the engine
  through the app's
  native calls, the screen translated end to end with ML Kit on a drawn feed (English translated,
  Turkish left, numbers skipped), subtitles translated as they come, and the translated layer over
  another app, whose picture goes to `files/layer.jpg`.
- CI then uses the app as a person would (`smoke.sh`): starts it, taps the bubble over another app
  and checks its English is translated, closes the translation with Back, holds the bubble for its
  menu, stops; turns live translation on over a video whose subtitles change and over a game whose
  dialogue is written out letter by letter (only whole lines translated), checks an app that forbids
  capture is told as such; and starts and stops from the Quick Settings tile. Lint runs on every build.

## What it cannot do

- Apps that forbid capture (Netflix and most paid video, banking apps) come out black; Hammurabi
  says so. Their sound may be kept too: the microphone then hears the loudspeaker.
- Cyrillic (Russian) and Arabic letters cannot be read: ML Kit's text recognition has no such
  models.
- Calls are never captured: Android keeps them out of playback capture.
- The speech model runs on the processor. "Hearing time … ms" on the main screen shows how long one
  run takes; above about a second the phone falls behind. Tiny is the model for most phones.
- Screen sharing ends when Android ends it (the screen locked, or stopped from the status bar); the
  bubble then shows a red dot, and a tap asks for it again.
- Live translation shows its boxes above the text rather than over it: a window covering the text
  would hide it from the next reading, and Android lets touches through only fairly see-through
  windows.
- Some phone makers stop apps in the background (Xiaomi, Huawei, Oppo): the main screen has a button
  to leave battery saving, and notes for their own settings.
- Read aloud and Windows Live Captions are desktop-only.
