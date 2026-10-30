//! Separate opt-in-to-sharing experimental diagnostic, without battery identifiers.
use std::io::{Seek, SeekFrom, Write};
use std::os::unix::fs::OpenOptionsExt;

#[derive(Default)]
pub struct DiagnosticLog {
    file: Option<std::fs::File>,
    rows: Vec<serde_json::Value>,
    model: Option<String>,
    macos_version: Option<String>,
}

impl DiagnosticLog {
    pub fn record(
        &mut self,
        sample: &crate::model::Sample,
        refresh: &crate::macos_refresh::RefreshResult,
        capacity: &serde_json::Value,
    ) {
        if self.file.is_none() {
            let Some(home) = std::env::var_os("HOME") else {
                return;
            };
            let sysctl = |key: &str| {
                let output = std::process::Command::new("/usr/sbin/sysctl")
                    .args(["-n", key])
                    .output()
                    .ok()?;
                output
                    .status
                    .success()
                    .then(|| String::from_utf8_lossy(&output.stdout).trim().to_owned())
            };
            self.model = sysctl("hw.model");
            self.macos_version = sysctl("kern.osproductversion");
            let stamp = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos();
            let path =
                std::path::PathBuf::from(home).join(format!("wpm-macos-diagnostic-{stamp}.json"));
            self.file = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(path)
                .ok();
        }
        self.rows.push(serde_json::json!({
            "timestamp": sample.ts, "mode": refresh, "watts": sample.watts,
            "volts": sample.volts, "amps": sample.amps, "state": sample.state,
            "capacity": capacity
        }));
        if self.rows.len() > 720 {
            self.rows.remove(0);
        }
        if let Some(file) = &mut self.file {
            let document = serde_json::json!({"experimental": true, "version": env!("CARGO_PKG_VERSION"), "architecture": std::env::consts::ARCH, "model": self.model, "macos_version": self.macos_version, "samples": self.rows});
            if file.seek(SeekFrom::Start(0)).is_ok()
                && serde_json::to_writer(&mut *file, &document).is_ok()
            {
                if let Ok(position) = file.stream_position() {
                    let _ = file.set_len(position);
                }
                let _ = file.flush();
            }
        }
    }
}
