use crate::cli::{Cli, Mode, Theme, parse_span, validate_timing};
use std::{ffi::OsString, fs, path::PathBuf, time::Duration};

#[derive(Debug, Default, serde::Deserialize)]
#[serde(deny_unknown_fields)]
struct FileConfig {
    rate: Option<f64>,
    x_length: Option<f64>,
    theme: Option<Theme>,
    tray: Option<bool>,
    all_states: Option<bool>,
    log: Option<String>,
}

#[derive(Debug)]
pub struct Settings {
    pub rate: Duration,
    pub system_meter: Option<String>,
    pub x_length: Duration,
    pub sample_count: usize,
    pub theme: Theme,
    pub tray: bool,
    pub all_states: bool,
    pub log: Option<PathBuf>,
    pub mode: Mode,
    pub absolute: bool,
    pub step: bool,
    pub require_battery: bool,
    pub warnings: Vec<String>,
}

impl Settings {
    pub fn load(cli: &Cli, stdout_is_terminal: bool) -> Result<Self, String> {
        Self::resolve(cli, stdout_is_terminal, |key| std::env::var_os(key))
    }

    pub fn resolve(
        cli: &Cli,
        stdout_is_terminal: bool,
        env: impl Fn(&str) -> Option<OsString>,
    ) -> Result<Self, String> {
        let explicit_path = cli
            .config
            .clone()
            .or_else(|| env("WPM_CONFIG").map(PathBuf::from));
        let path = explicit_path
            .clone()
            .or_else(|| env("APPDATA").map(|p| PathBuf::from(p).join("wpm").join("config.toml")));
        let mut warnings = Vec::new();
        let file = match path {
            Some(path) => match fs::read_to_string(&path) {
                Ok(text) => match toml::from_str::<FileConfig>(&text) {
                    Ok(file) => file,
                    Err(error) => {
                        warnings.push(format!("ignoring config {}: {error}", path.display()));
                        FileConfig::default()
                    }
                },
                Err(error)
                    if error.kind() == std::io::ErrorKind::NotFound && explicit_path.is_none() =>
                {
                    FileConfig::default()
                }
                Err(error) => {
                    warnings.push(format!(
                        "cannot read config {}: {error}; using defaults",
                        path.display()
                    ));
                    FileConfig::default()
                }
            },
            None => FileConfig::default(),
        };
        let text_env = |key: &str| -> Result<Option<String>, String> {
            env(key)
                .map(|value| {
                    value
                        .into_string()
                        .map_err(|_| format!("{key} must be Unicode"))
                })
                .transpose()
        };
        let rate = if let Some(value) = cli.rate {
            value
        } else if let Some(value) = text_env("WPM_RATE")? {
            value
                .parse::<f64>()
                .map_err(|_| "WPM_RATE must be seconds".to_owned())?
        } else {
            file.rate.unwrap_or(5.0)
        };
        let span = if let Some(value) = cli.x_length {
            value
        } else if let Some(value) = text_env("WPM_X_LENGTH")? {
            parse_span(&value).map_err(|e| format!("WPM_X_LENGTH: {e}"))?
        } else {
            file.x_length.unwrap_or(300.0)
        };
        let (rate, x_length, sample_count) = validate_timing(rate, span)?;
        let theme = if let Some(theme) = cli.theme {
            theme
        } else if let Some(value) = text_env("WPM_THEME")? {
            match value.as_str() {
                "default" => Theme::Default,
                "high-contrast" => Theme::HighContrast,
                "mono" => Theme::Mono,
                _ => return Err("WPM_THEME must be default, high-contrast, or mono".into()),
            }
        } else {
            file.theme.unwrap_or_default()
        };
        if rate < Duration::from_secs(1) {
            warnings.push("sampling below 1 second may skew power measurements".into());
        }
        let mode = cli.mode(stdout_is_terminal);
        Ok(Self {
            rate,
            system_meter: cli.system_meter.clone(),
            x_length,
            sample_count,
            theme,
            tray: mode == Mode::Dashboard && cli.tray.or(file.tray).unwrap_or(true),
            all_states: cli.all_states || file.all_states.unwrap_or(false),
            log: cli
                .log
                .clone()
                .or_else(|| file.log.filter(|p| !p.is_empty()).map(PathBuf::from)),
            mode,
            absolute: !cli.signed,
            step: cli.step,
            require_battery: cli.require_battery,
            warnings,
        })
    }
}
