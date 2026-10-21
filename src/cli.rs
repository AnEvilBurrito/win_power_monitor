use clap::{ArgGroup, Parser, ValueEnum};
use std::{path::PathBuf, time::Duration};

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, ValueEnum, serde::Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Theme {
    #[default]
    Default,
    HighContrast,
    Mono,
}

#[derive(Debug, Parser)]
#[command(name = "wpm", version, about = "Windows power monitor", group(ArgGroup::new("mode").args(["json", "ndjson", "once", "self_test"]).multiple(false)))]
pub struct Cli {
    /// Seconds between samples (default 5, range 0.25–60); below 1 s may skew measurements
    #[arg(long)]
    pub rate: Option<f64>,
    /// History span: seconds, or a duration such as 90s, 5m, 1h
    #[arg(long, value_parser = parse_span)]
    pub x_length: Option<f64>,
    #[arg(long)]
    pub log: Option<PathBuf>,
    #[arg(long)]
    pub json: bool,
    #[arg(long)]
    pub ndjson: bool,
    #[arg(long)]
    pub once: bool,
    #[arg(long)]
    pub self_test: bool,
    /// Display magnitudes (default); exports always retain signs
    #[arg(long = "abs", conflicts_with = "signed")]
    pub absolute: bool,
    /// Display signed battery flow (negative discharge, positive charge)
    #[arg(long)]
    pub signed: bool,
    #[arg(long)]
    pub step: bool,
    #[arg(long)]
    pub all_states: bool,
    #[arg(long)]
    pub require_battery: bool,
    #[arg(long, value_enum)]
    pub theme: Option<Theme>,
    #[arg(long)]
    pub config: Option<PathBuf>,
    /// Show a tray icon alongside the dashboard (default true; --tray=false disables)
    #[arg(long, num_args = 0..=1, default_missing_value = "true", require_equals = true, action = clap::ArgAction::Set)]
    pub tray: Option<bool>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    Dashboard,
    Json,
    Ndjson,
    Once,
    SelfTest,
}

impl Cli {
    pub fn mode(&self, stdout_is_terminal: bool) -> Mode {
        if self.json {
            Mode::Json
        } else if self.ndjson {
            Mode::Ndjson
        } else if self.once {
            Mode::Once
        } else if self.self_test {
            Mode::SelfTest
        } else if stdout_is_terminal {
            Mode::Dashboard
        } else {
            Mode::Json
        }
    }
}

pub fn parse_span(text: &str) -> Result<f64, String> {
    let (number, multiplier) = match text.as_bytes().last() {
        Some(b's') => (&text[..text.len() - 1], 1.0),
        Some(b'm') => (&text[..text.len() - 1], 60.0),
        Some(b'h') => (&text[..text.len() - 1], 3600.0),
        _ => (text, 1.0),
    };
    let value = number
        .parse::<f64>()
        .map_err(|_| "expected seconds or a duration such as 90s, 5m, 1h".to_owned())?
        * multiplier;
    if !value.is_finite() || value <= 0.0 {
        return Err("history span must be finite and positive".into());
    }
    Ok(value)
}

pub fn validate_timing(rate: f64, span: f64) -> Result<(Duration, Duration, usize), String> {
    if !rate.is_finite() || !(0.25..=60.0).contains(&rate) {
        return Err("--rate must be finite and between 0.25 and 60 seconds".into());
    }
    if !span.is_finite() || span <= 0.0 {
        return Err("--x-length must be finite and positive".into());
    }
    let count = (span / rate).ceil();
    if span <= rate || count > 500_000.0 {
        return Err(format!(
            "--x-length / --rate estimates {count} samples; require a span greater than --rate and at most 500000 samples"
        ));
    }
    let rate = Duration::try_from_secs_f64(rate).map_err(|e| e.to_string())?;
    let span = Duration::try_from_secs_f64(span).map_err(|e| e.to_string())?;
    if span <= rate {
        return Err("--x-length must exceed --rate at nanosecond precision".into());
    }
    Ok((rate, span, count as usize))
}
