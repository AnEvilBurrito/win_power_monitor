#![deny(clippy::unwrap_used)]

use clap::Parser;
use std::{io::IsTerminal, process::ExitCode};
use wpm_cli::{cli::Cli, config::Settings};

fn main() -> ExitCode {
    let cli = Cli::parse();
    let settings = match Settings::load(&cli, std::io::stdout().is_terminal()) {
        Ok(settings) => settings,
        Err(error) => {
            eprintln!("wpm: {error}");
            return ExitCode::from(2);
        }
    };
    for warning in &settings.warnings {
        eprintln!("wpm: warning: {warning}");
    }
    eprintln!(
        "wpm: {:?} mode is not implemented yet; CLI/config foundation only",
        settings.mode
    );
    ExitCode::FAILURE
}
