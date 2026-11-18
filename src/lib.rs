pub mod asr;
pub mod audio;
pub mod config;
pub mod device;
pub mod i18n;
pub mod models;
pub mod onnx;
pub mod paths;
pub mod pipeline;
pub mod screen;
pub mod settings;
pub mod translate;
pub mod tts;
pub mod types;
/// The desktop app. On Android the app in android/ is the UI and feeds the engine itself.
#[cfg(not(target_os = "android"))]
pub mod ui;
