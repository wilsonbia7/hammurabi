//! The ONNX Runtime environment, one for the process, shared by everything that runs a model on
//! it: the voice detector, the Supertonic voices, the Android app's text recognizer. ort builds
//! one environment per process and never another, not even once the first is let go, so a second
//! engine, or a voice loaded while listening, has to use the same one.

use std::sync::Mutex;

use ort::environment::Environment;

/// The process's environment, built at the first call and kept until the process ends.
pub fn env() -> anyhow::Result<Environment> {
    static ENV: Mutex<Option<Environment>> = Mutex::new(None);
    let mut env = ENV.lock().unwrap_or_else(|p| p.into_inner());
    if let Some(e) = env.as_ref() {
        return Ok(e.clone());
    }
    let e = ort::init().with_name("hammurabi").build()?;
    *env = Some(e.clone());
    Ok(e)
}

#[cfg(test)]
mod tests {
    #[test]
    fn one_environment_for_everyone() {
        let a = super::env().unwrap();
        let b = super::env().unwrap();
        drop(a);
        // Built once: a later caller gets it too, not ort's "only one environment" error.
        drop(b);
        assert!(super::env().is_ok());
    }
}
