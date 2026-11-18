//! The Android app's way into Hammurabi (android/app, `Native.kt`): one engine at a time, fed with
//! the audio the app records, or with the text read off the screen pictures it captures
//! (`page`, `Engine::spawn_fed_screen`), and read back as JSON events. Captions use the JSON of
//! the desktop's browser source (`ui::web`), so the same drawing code can read both.

mod lang;
mod ocr;
mod page;

use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, Once, OnceLock};
use std::time::{Duration, Instant};

use crossbeam_channel::{Sender, TrySendError};
use hammurabi::models::{self, ModelId};
use hammurabi::pipeline::{Engine, EngineHandle};
use hammurabi::settings::{AsrBackend, SOURCE_LANGS, Settings, TARGET_LANGS};
use hammurabi::types::{
    AudioChunk, CaptionUpdate, EngineCommand, EngineEvent, EngineState, ProgressPhase, SourceKind,
    StatusLevel,
};
use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteBuffer, JClass, JShortArray, JString};
use jni::sys::{jboolean, jint};
use serde_json::{Value, json};

/// How long `stop` waits for the engine thread to let go of its models.
const STOP_WAIT: Duration = Duration::from_secs(3);

struct Session {
    engine: Arc<EngineHandle>,
    audio: Sender<AudioChunk>,
    /// `Settings::target_language`: a caption already in it is shown as it is.
    target: String,
    /// Audio chunks dropped because the engine fell behind.
    dropped: u64,
}

static SESSION: Mutex<Option<Session>> = Mutex::new(None);
/// Reading the screen, while Text on screen runs: apart from `SESSION`, since a picture takes the
/// recognizer a while and events must not wait. A picture's reading takes the reader out and puts
/// it back after, so a stop, or a new start, never waits for a reading to end: a reader that comes
/// back to another run is let go.
static SCREEN: Mutex<Screen> = Mutex::new(Screen {
    read: None,
    run: 0,
    target: None,
});

struct Screen {
    /// The reader, while no picture is being read.
    read: Option<page::PageRead>,
    /// Counts starts and stops.
    run: u64,
    /// A target language set while a picture was being read, for the reader when it is back.
    target: Option<String>,
}
/// The app's files folder (`setup`).
static FILES: OnceLock<PathBuf> = OnceLock::new();

fn session() -> MutexGuard<'static, Option<Session>> {
    SESSION.lock().unwrap_or_else(|p| p.into_inner())
}

fn screen() -> MutexGuard<'static, Screen> {
    SCREEN.lock().unwrap_or_else(|p| p.into_inner())
}

/// `Native.setup(filesDir, threads)`: before `start`, once per process (later calls change
/// nothing). `threads` = 0 picks the phone's fast cores. Returns null, or why the engine cannot
/// run here.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_menzo_hammurabi_Native_setup<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    files_dir: JString<'local>,
    threads: jint,
) -> JString<'local> {
    unowned
        .with_env(|env| -> jni::errors::Result<JString<'local>> {
            let dir = files_dir.try_to_string(env)?;
            match setup(PathBuf::from(dir), threads) {
                Ok(()) => Ok(JString::null()),
                Err(e) => JString::from_str(env, format!("{e:#}")),
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn setup(dir: PathBuf, threads: i32) -> anyhow::Result<()> {
    static PANICS: Once = Once::new();
    logger();
    // A panic would otherwise print to a stderr nobody reads.
    PANICS.call_once(|| {
        let previous = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            log::error!("{info}");
            previous(info);
        }));
    });
    hammurabi::paths::set_root(dir.clone());
    let _ = FILES.set(dir);
    set_threads(threads);
    // Silero VAD runs on ONNX Runtime from the app's onnxruntime-android package. Loading it now
    // turns a missing or too old library into an error here instead of a panic in the engine.
    #[cfg(target_os = "android")]
    let _env = ort::init_from("libonnxruntime.so")?;
    Ok(())
}

#[cfg(target_os = "android")]
fn logger() {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("hammurabi"),
    );
}

#[cfg(not(target_os = "android"))]
fn logger() {}

/// The threads the speech model computes on (candle reads `RAYON_NUM_THREADS`), set before its
/// first run: `threads`, else the phone's fast cores.
fn set_threads(threads: i32) {
    if std::env::var_os("RAYON_NUM_THREADS").is_some() {
        return;
    }
    let n = match usize::try_from(threads) {
        Ok(n) if n > 0 => n,
        _ => match fast_cores() {
            Some(n) => n,
            None => return,
        },
    };
    log::info!("speech model threads: {n}");
    // SAFETY: runs from `setup`, before any engine or worker thread exists; the app's own threads
    // do not read or write the environment.
    unsafe { std::env::set_var("RAYON_NUM_THREADS", n.to_string()) };
}

/// Phones pair a few fast cores with slower ones. A matrix product split evenly waits for its
/// slowest thread, so with four or more fast cores the slow ones only hold them back; with fewer,
/// all cores together still finish first.
fn fast_cores() -> Option<usize> {
    let mut freqs = Vec::new();
    for entry in std::fs::read_dir("/sys/devices/system/cpu").ok()?.flatten() {
        let name = entry.file_name();
        let Some(id) = name.to_str().and_then(|n| n.strip_prefix("cpu")) else {
            continue;
        };
        if id.is_empty() || !id.bytes().all(|b| b.is_ascii_digit()) {
            continue;
        }
        let max = std::fs::read_to_string(entry.path().join("cpufreq/cpuinfo_max_freq")).ok()?;
        freqs.push(max.trim().parse::<u64>().ok()?);
    }
    let slowest = *freqs.iter().min()?;
    let fast = freqs.iter().filter(|&&f| f > slowest).count();
    Some(if fast >= 4 { fast } else { freqs.len() })
}

/// `Native.start(settingsJson)`: a new engine with these settings (`Settings` as JSON, missing
/// fields at their defaults), in place of a running one. False when the settings do not parse.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_menzo_hammurabi_Native_start<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    settings: JString<'local>,
) -> jboolean {
    unowned
        .with_env(|env| -> jni::errors::Result<jboolean> {
            let json = settings.try_to_string(env)?;
            Ok(match parse_settings(&json) {
                Ok(s) => {
                    start(s);
                    true
                }
                Err(e) => {
                    log::error!("settings: {e:#}");
                    false
                }
            })
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// `Native.reconfigure(settingsJson)`: new settings for the running engine.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_menzo_hammurabi_Native_reconfigure<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    settings: JString<'local>,
) {
    unowned
        .with_env(|env| -> jni::errors::Result<()> {
            let json = settings.try_to_string(env)?;
            match parse_settings(&json) {
                Ok(s) => {
                    {
                        let mut screen = screen();
                        match screen.read.as_mut() {
                            Some(read) => read.set_target(&s.target_language),
                            None => screen.target = Some(s.target_language.clone()),
                        }
                    }
                    if let Some(session) = session().as_mut() {
                        hammurabi::i18n::set(s.ui_language);
                        session.target = s.target_language.clone();
                        session.engine.send(EngineCommand::Reconfigure(s));
                    }
                }
                Err(e) => log::error!("settings: {e:#}"),
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn parse_settings(json: &str) -> anyhow::Result<Settings> {
    let mut s: Settings = serde_json::from_str(json)?;
    // Listening or the screen's subtitles; no Windows Live Captions, no voices, no web page.
    if s.asr_backend == AsrBackend::WindowsLiveCaptions {
        s.asr_backend = AsrBackend::Whisper;
    }
    s.speak = false;
    s.browser_source = false;
    // The translation goes in the caption box: what is read can be anywhere on the screen.
    s.screen_cover = false;
    Ok(s)
}

/// The recognizer's models, copied out of the app by `CaptionService` (`OcrModels.kt`).
fn ocr_dir() -> PathBuf {
    FILES.get().cloned().unwrap_or_default().join("ocr")
}

fn start(settings: Settings) {
    stop();
    hammurabi::i18n::set(settings.ui_language);
    let (engine, fed) = Engine::spawn_fed_screen(Arc::new(|| {}));
    let target = settings.target_language.clone();
    if settings.asr_backend == AsrBackend::ScreenText {
        let threads = std::thread::available_parallelism().map_or(2, |n| n.get().min(4));
        match page::PageRead::new(fed.screen.clone(), &ocr_dir(), threads, &target) {
            Ok(read) => screen().read = Some(read),
            Err(e) => {
                let _ = fed
                    .screen
                    .send(hammurabi::screen::ScreenEvent::Error(format!("{e:#}")));
            }
        }
    }
    engine.send(EngineCommand::Start(settings));
    *session() = Some(Session {
        engine: Arc::new(engine),
        audio: fed.audio,
        target,
        dropped: 0,
    });
}

/// `Native.feed(samples, count, sampleRate, system)`: 16-bit mono audio as recorded, from other
/// apps (`system`) or the microphone. False when no engine runs, or it is behind and the audio
/// was dropped.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_menzo_hammurabi_Native_feed<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    samples: JShortArray<'local>,
    count: jint,
    sample_rate: jint,
    system: jboolean,
) -> jboolean {
    unowned
        .with_env(|env| -> jni::errors::Result<jboolean> {
            let (Ok(n), Ok(rate)) = (usize::try_from(count), u32::try_from(sample_rate)) else {
                return Ok(false);
            };
            if n == 0 || rate == 0 {
                return Ok(false);
            }
            let mut pcm = vec![0i16; n];
            samples.get_region(env, 0, &mut pcm)?;
            Ok(feed(AudioChunk {
                source: if system {
                    SourceKind::System
                } else {
                    SourceKind::Microphone
                },
                samples: pcm.iter().map(|&s| f32::from(s) / 32768.0).collect(),
                sample_rate: rate,
            }))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn feed(chunk: AudioChunk) -> bool {
    let mut guard = session();
    let Some(s) = guard.as_mut() else {
        return false;
    };
    match s.audio.try_send(chunk) {
        Ok(()) => true,
        Err(TrySendError::Full(_)) => {
            s.dropped += 1;
            if s.dropped.is_power_of_two() {
                log::warn!("engine behind: {} audio chunks dropped", s.dropped);
            }
            false
        }
        Err(TrySendError::Disconnected(_)) => false,
    }
}

/// `Native.screenFrame(frame, width, height, rowStride, maskLeft, maskTop, maskRight,
/// maskBottom)`: a picture of the screen as an ImageReader gives it (a direct buffer of RGBA
/// rows), while Translate the screen runs, and where our captions are in it (all 0: none). False
/// when nothing reads pictures now.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_dev_menzo_hammurabi_Native_screenFrame<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    frame: JByteBuffer<'local>,
    width: jint,
    height: jint,
    row_stride: jint,
    mask_left: jint,
    mask_top: jint,
    mask_right: jint,
    mask_bottom: jint,
) -> jboolean {
    unowned
        .with_env(|env| -> jni::errors::Result<jboolean> {
            let (Ok(w), Ok(h), Ok(stride)) = (
                usize::try_from(width),
                usize::try_from(height),
                usize::try_from(row_stride),
            ) else {
                return Ok(false);
            };
            let ptr = env.get_direct_buffer_address(&frame)?;
            let len = env.get_direct_buffer_capacity(&frame)?;
            if ptr.is_null() {
                return Ok(false);
            }
            // SAFETY: a direct buffer's memory stays valid while the Java call that passed it
            // runs (the image stays open until this returns), and is only read here.
            let rgba = unsafe { std::slice::from_raw_parts(ptr.cast_const(), len) };
            let mask = (mask_right > mask_left && mask_bottom > mask_top)
                .then(|| [mask_left, mask_top, mask_right, mask_bottom].map(|v| v as f32));
            Ok(screen_frame(rgba, w, h, stride, mask))
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn screen_frame(rgba: &[u8], w: usize, h: usize, stride: usize, mask: Option<[f32; 4]>) -> bool {
    let (mut read, run, target) = {
        let mut screen = screen();
        let Some(read) = screen.read.take() else {
            return false;
        };
        (read, screen.run, screen.target.take())
    };
    if let Some(t) = target {
        read.set_target(&t);
    }
    if let Err(e) = read.frame(rgba, w, h, stride, mask) {
        // The engine has been told, stops, and says why.
        log::error!("screen: {e:#}");
        return false;
    }
    let mut screen = screen();
    // Stopped, or started anew, meanwhile: this reader is done.
    if screen.run == run && screen.read.is_none() {
        screen.read = Some(read);
    }
    true
}

/// `Native.poll(timeoutMs)`: the next event as JSON, or null when none came in that time.
/// `{"t":"ended"}` once when the engine stopped by itself (it failed).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_menzo_hammurabi_Native_poll<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    timeout_ms: jint,
) -> JString<'local> {
    unowned
        .with_env(|env| -> jni::errors::Result<JString<'local>> {
            let timeout = Duration::from_millis(u64::try_from(timeout_ms).unwrap_or(0));
            match next_event(timeout) {
                Some(json) => JString::from_str(env, json),
                None => Ok(JString::null()),
            }
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn next_event(timeout: Duration) -> Option<String> {
    let current = session()
        .as_ref()
        .map(|s| (s.engine.clone(), s.target.clone()));
    let Some((engine, target)) = current else {
        std::thread::sleep(timeout);
        return None;
    };
    let deadline = Instant::now() + timeout;
    loop {
        match engine.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
            Some(ev) => {
                if let Some(v) = event_json(ev, &target) {
                    return Some(v.to_string());
                }
            }
            None if engine.finished() => {
                let mut guard = session();
                if guard
                    .as_ref()
                    .is_some_and(|s| Arc::ptr_eq(&s.engine, &engine))
                {
                    *guard = None;
                    return Some(json!({"t": "ended"}).to_string());
                }
                return None;
            }
            None => return None,
        }
    }
}

/// `Native.stop()`: ends the engine, waiting a moment for it to free its models.
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_menzo_hammurabi_Native_stop<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) {
    unowned
        .with_env(|_env| -> jni::errors::Result<()> {
            stop();
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn stop() {
    {
        let mut screen = screen();
        screen.read = None;
        screen.run += 1;
        screen.target = None;
    }
    let Some(s) = session().take() else {
        return;
    };
    s.engine.send(EngineCommand::Stop);
    s.engine.send(EngineCommand::Shutdown);
    let deadline = Instant::now() + STOP_WAIT;
    while !s.engine.finished() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(20));
    }
}

/// `Native.info()`: what the app offers, as JSON: the languages, and the speech models with
/// their download sizes and whether they are downloaded (call `setup` first).
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_menzo_hammurabi_Native_info<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    unowned
        .with_env(|env| -> jni::errors::Result<JString<'local>> {
            JString::from_str(env, info().to_string())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

fn info() -> Value {
    let model = |choice: &str, id: ModelId| {
        json!({
            "id": choice,
            "label": models::spec(id).label,
            "bytes": models::spec(id).approx_bytes,
            "cached": models::is_cached(id),
        })
    };
    json!({
        "source": SOURCE_LANGS,
        "target": TARGET_LANGS,
        // `ModelChoice` names. Larger ones are too slow for a phone's processor.
        "models": [
            model("Tiny", ModelId::WhisperTiny),
            model("Base", ModelId::WhisperBase),
            model("Small", ModelId::WhisperSmall),
        ],
        "translator": model("Builtin", ModelId::Madlad3bQ4k),
    })
}

fn event_json(ev: EngineEvent, target: &str) -> Option<Value> {
    Some(match ev {
        EngineEvent::Caption(c) => caption_json(&c, target),
        EngineEvent::State(s) => json!({
            "t": "state",
            "state": match s {
                EngineState::Idle => "idle",
                EngineState::Loading => "loading",
                EngineState::Running => "running",
                EngineState::Error => "error",
            },
        }),
        EngineEvent::Status(s) => json!({
            "t": "status",
            "level": match s.level {
                StatusLevel::Info => "info",
                StatusLevel::Warning => "warning",
                StatusLevel::Error => "error",
            },
            "text": s.text,
            "ttl_ms": s.ttl.map(|d| d.as_millis() as u64),
        }),
        EngineEvent::Progress(p) => json!({
            "t": "progress",
            "model": models::spec(p.model).label,
            "phase": match p.phase {
                ProgressPhase::Downloading => "downloading",
                ProgressPhase::Loading => "loading",
            },
            "done": p.done,
            "total": p.total,
        }),
        EngineEvent::Stats { asr_ms, device } => {
            json!({"t": "stats", "asr_ms": asr_ms, "device": device})
        }
        EngineEvent::Translated { ms } => json!({"t": "translated", "ms": ms}),
        // Read aloud is off here, and translations do not cover what was read on the screen.
        EngineEvent::Speech(_) | EngineEvent::Place(_) => return None,
    })
}

/// As `ui::web` sends captions to its page.
fn caption_json(c: &CaptionUpdate, target: &str) -> Value {
    match c {
        CaptionUpdate::Final {
            id,
            original,
            translated,
            lang,
        } => {
            json!({"t": "final", "id": id, "o": original, "tr": translated, "same": lang == target})
        }
        CaptionUpdate::Live {
            original,
            translated,
            lang,
        } => json!({
            "t": "live",
            "o": original,
            "tr": translated,
            "same": lang.as_deref() == Some(target),
        }),
        CaptionUpdate::Clear => json!({"t": "clear"}),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn settings_from_partial_json() {
        let s = parse_settings(
            r#"{"spoken_language":"en","target_language":"tr","translate":true,"model":"Tiny",
                "speak":true,"ui_language":"tr"}"#,
        )
        .unwrap();
        assert_eq!(s.spoken_language.as_deref(), Some("en"));
        assert_eq!(s.model, hammurabi::settings::ModelChoice::Tiny);
        assert!(s.translate && !s.speak && !s.browser_source);
        assert_eq!(s.ui_language, Some(hammurabi::i18n::Lang::Tr));
        assert!(parse_settings("{\"model\":\"Huge\"}").is_err());
    }

    #[test]
    fn captions_as_the_web_page_reads_them() {
        let final_ = caption_json(
            &CaptionUpdate::Final {
                id: 7,
                original: "Hello there.".into(),
                translated: Some("Merhaba.".into()),
                lang: "en".into(),
            },
            "tr",
        );
        assert_eq!(
            final_,
            json!({"t": "final", "id": 7, "o": "Hello there.", "tr": "Merhaba.", "same": false})
        );
        let live = caption_json(
            &CaptionUpdate::Live {
                original: "Nasılsın".into(),
                translated: None,
                lang: Some("tr".into()),
            },
            "tr",
        );
        assert_eq!(live["same"], true);
        assert!(event_json(EngineEvent::Speech(hammurabi::tts::Activity::Idle), "tr").is_none());
    }

    #[test]
    fn engine_round_trip_without_audio_devices() {
        // A fed engine with nothing to listen to still answers Start and Stop.
        start(parse_settings(r#"{"model":"Tiny","translate":false}"#).unwrap());
        assert!(feed(AudioChunk {
            source: SourceKind::Microphone,
            samples: vec![0.0; 1600],
            sample_rate: 16_000,
        }));
        stop();
        assert!(session().is_none());
        assert!(!feed(AudioChunk {
            source: SourceKind::Microphone,
            samples: vec![0.0; 1600],
            sample_rate: 16_000,
        }));
    }
}
