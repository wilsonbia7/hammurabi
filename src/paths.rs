use std::path::PathBuf;
use std::sync::OnceLock;

use anyhow::Context;
use directories::ProjectDirs;

static ROOT: OnceLock<PathBuf> = OnceLock::new();

/// Keeps every folder below under `dir` instead of the OS's places for them. Android apps have no
/// home folder, so the app passes its own files folder before the engine starts. Only the first
/// call counts; returns whether this one did.
pub fn set_root(dir: PathBuf) -> bool {
    ROOT.set(dir).is_ok()
}

fn dirs() -> anyhow::Result<ProjectDirs> {
    ProjectDirs::from("dev", "menzo", "Hammurabi").context("no home directory")
}

fn config_dir() -> anyhow::Result<PathBuf> {
    match ROOT.get() {
        Some(root) => Ok(root.join("config")),
        None => Ok(dirs()?.config_dir().to_path_buf()),
    }
}

fn cache_dir() -> anyhow::Result<PathBuf> {
    match ROOT.get() {
        Some(root) => Ok(root.join("cache")),
        None => Ok(dirs()?.cache_dir().to_path_buf()),
    }
}

fn data_local_dir() -> anyhow::Result<PathBuf> {
    match ROOT.get() {
        Some(root) => Ok(root.join("data")),
        None => Ok(dirs()?.data_local_dir().to_path_buf()),
    }
}

pub fn config_file() -> anyhow::Result<PathBuf> {
    Ok(config_dir()?.join("config.toml"))
}

/// `HAMMURABI_MODELS_DIR` overrides the cache location.
pub fn models_dir() -> anyhow::Result<PathBuf> {
    if let Some(dir) = std::env::var_os("HAMMURABI_MODELS_DIR").filter(|d| !d.is_empty()) {
        return Ok(dir.into());
    }
    Ok(cache_dir()?.join("models"))
}

/// App volumes to put back if Hammurabi dies while it has them turned down.
pub fn duck_journal() -> anyhow::Result<PathBuf> {
    Ok(data_local_dir()?.join("ducked.json"))
}

/// A second launch reaches the running copy through this socket (Windows uses named events).
#[cfg(unix)]
pub fn instance_socket() -> anyhow::Result<PathBuf> {
    Ok(data_local_dir()?.join("instance.sock"))
}

pub fn log_dir() -> anyhow::Result<PathBuf> {
    Ok(data_local_dir()?.join("logs"))
}

/// Setup programs of new versions, downloaded before they are installed.
pub fn updates_dir() -> anyhow::Result<PathBuf> {
    Ok(cache_dir()?.join("updates"))
}
