# Hammurabi for Android

Live captions and translation on a phone, with the desktop app's engine: the same voice detection,
Whisper, sentence assembly and translators, built for Android and run on the phone itself.

- **Text on screen** (the first choice): foreign text anywhere on the screen, in any app (a post,
  comments, the words on a meme, a video's or a game's subtitles), is read and translated while
  the user scrolls or plays. The translation shows at the bottom middle, like a film's subtitles.
- **The phone's sound**: what videos and games say, heard and translated.
- **Microphone**: people talking next to the phone.

Captions show over other apps, and on the app's own screen. A Quick Settings tile turns them on and
off from any app (Android 13 offers to add it from the app).

Early work: it builds, but has been tried on few phones yet.

## How it works

- `app/` is a small Kotlin app with no libraries but ONNX Runtime. `CaptionService` records the
  microphone and/or what other apps play (`AudioPlaybackCapture`, Android 10+), 100 ms at a time.
- `rust/` is a JNI library around the `hammurabi` crate. The audio goes into
  `Engine::spawn_fed`, the engine the CLI also feeds, and its events come back as JSON. Captions use
  the JSON the desktop's browser source sends (`src/ui/web.rs`).
- `CaptionOverlay` draws captions over other apps, at the bottom middle; touches go through to the
  app below. Until there are captions it says how things go (a model downloading, an error).
- Text on screen: `ScreenCapture` sends a few pictures of the whole screen a second, with where our
  captions are in them. `rust/src/page.rs` reads a picture that changed at most twice a second:
  PaddleOCR's PP-OCRv4 mobile models (`rust/src/ocr.rs`, on the ONNX Runtime the app has anyway)
  find the rows of text and read them, the rows are stacked into paragraphs, and a paragraph goes to
  the engine (`Engine::spawn_fed_screen`) once it reads alike in the same place twice on end, which
  text being scrolled does not, and only once while it stays. Text already in the target language
  and apps' buttons and labels are skipped (`rust/src/lang.rs`: whatlang, and for short text the
  words common in Turkish and English); our own captions are blanked out of the picture first. The
  build takes the models from RapidOCR's wheel on PyPI, checked against its hash, into the APK's
  assets (`fetchOcrModels`). `rust/tests/page_pictures.py` makes phone screenshots the reader is
  tested on.

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

- Apps that forbid capture (Netflix and most paid video, banking apps) give silence, and their
  pictures come out black. YouTube and most others can be heard and read.
- Calls are never captured: Android keeps them out of playback capture.
- The speech model runs on the processor. "Hearing takes … ms" under the button shows how long one
  run takes; above about a second the phone falls behind. Tiny is the model for most phones.
- The built-in translator (MADLAD, 1.7 GB) is heavy for a phone: it needs the memory and is slow on
  mid-range processors.
- Text on screen reads Latin and Chinese letters (without some accents: ç, ş, ğ, ı, ö, ñ, ß); not
  yet Arabic, Cyrillic, Japanese or Korean. Reading the whole screen twice a second while a video
  plays keeps the processor busy, and the battery goes faster.
- Covering a subtitle with its translation, as the desktop does, needs the picture of what is under
  our captions, which Android does not give; the translation stands at the bottom middle.
- Read aloud and Windows Live Captions are desktop-only.
