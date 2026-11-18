//! The Android app's way into Hammurabi (android/app, `Native.kt`): one engine at a time, fed with
//! the audio the app records (`Engine::spawn_fed`), and read back as JSON events. Captions use the
//! JSON of the desktop's browser source (`ui::web`), so the same drawing code can read both. The
//! app reads and translates the screen itself, with Google's ML Kit.

use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard, Once};
use std::time::{Duration, Instant};

use crossbeam_channel::{Sender, TrySendError};
use hammurabi::models::{self, ModelId};
use hammurabi::pipeline::{Engine, EngineHandle};
use hammurabi::settings::{AsrBackend, SOURCE_LANGS, Settings};
use hammurabi::types::{
    AudioChunk, CaptionUpdate, EngineCommand, EngineEvent, EngineState, ProgressPhase, SourceKind,
    StatusLevel,
};
use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JShortArray, JString};
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

fn session() -> MutexGuard<'static, Option<Session>> {
    SESSION.lock().unwrap_or_else(|p| p.into_inner())
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
    hammurabi::paths::set_root(dir);
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
    // Listening only: no Windows Live Captions, no screen reading (the app does it), no voices,
    // no web page.
    if matches!(
        s.asr_backend,
        AsrBackend::WindowsLiveCaptions | AsrBackend::ScreenText
    ) {
        s.asr_backend = AsrBackend::Whisper;
    }
    s.speak = false;
    s.browser_source = false;
    s.screen_cover = false;
    Ok(s)
}

fn start(settings: Settings) {
    stop();
    hammurabi::i18n::set(settings.ui_language);
    let (engine, audio) = Engine::spawn_fed(Arc::new(|| {}));
    let target = settings.target_language.clone();
    engine.send(EngineCommand::Start(settings));
    *session() = Some(Session {
        engine: Arc::new(engine),
        audio,
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

/// `Native.info()`: what the app offers, as JSON: the languages heard, and the speech models with
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
        // `ModelChoice` names. Larger ones are too slow for a phone's processor.
        "models": [
            model("Tiny", ModelId::WhisperTiny),
            model("Base", ModelId::WhisperBase),
            model("Small", ModelId::WhisperSmall),
        ],
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
        // Read aloud is off here, and nothing covers a subtitle on the screen.
        EngineEvent::Speech(_) | EngineEvent::Place(_) => return None,
    })
}

/// As `ui::web` sends captions to its page, with the language heard (`lang`, empty when not
/// known yet), which the app translates from.
fn caption_json(c: &CaptionUpdate, target: &str) -> Value {
    match c {
        CaptionUpdate::Final {
            id,
            original,
            translated,
            lang,
        } => json!({
            "t": "final",
            "id": id,
            "o": original,
            "tr": translated,
            "same": lang == target,
            "lang": lang,
        }),
        CaptionUpdate::Live {
            original,
            translated,
            lang,
        } => json!({
            "t": "live",
            "o": original,
            "tr": translated,
            "same": lang.as_deref() == Some(target),
            "lang": lang.as_deref().unwrap_or(""),
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
            json!({"t": "final", "id": 7, "o": "Hello there.", "tr": "Merhaba.", "same": false,
                   "lang": "en"})
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
        assert_eq!(live["lang"], "tr");
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
