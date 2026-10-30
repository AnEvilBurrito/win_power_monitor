pub mod analytics;
pub mod app;
pub mod cli;
pub mod config;
pub mod export;
pub mod macos;
#[cfg(target_os = "macos")]
mod macos_diagnostics;
#[cfg(target_os = "macos")]
mod macos_refresh;
pub mod model;
pub mod native_battery;
pub mod power_meter;
pub mod provider;
pub mod sysfs;
pub mod theme;
pub mod tray;
pub mod ui;
