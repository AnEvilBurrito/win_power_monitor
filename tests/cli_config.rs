use clap::Parser;
use std::{
    ffi::OsString,
    process::Command,
    sync::atomic::{AtomicUsize, Ordering},
    time::Duration,
};
use wpm_cli::{
    cli::{Cli, Mode, Theme, parse_span, validate_timing},
    config::Settings,
};

fn resolve(args: &[&str], vars: &[(&str, &str)], tty: bool) -> Result<Settings, String> {
    let cli =
        Cli::try_parse_from(std::iter::once("wpm").chain(args.iter().copied())).expect("valid CLI");
    Settings::resolve(&cli, tty, |key| {
        vars.iter()
            .find(|(k, _)| *k == key)
            .map(|(_, value)| OsString::from(value))
    })
}

struct ConfigFile(std::path::PathBuf);
impl ConfigFile {
    fn new(text: &str) -> Self {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        let path = std::env::current_dir()
            .expect("project directory")
            .join(format!(
                "wpm-config-{}-{}.toml",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
        std::fs::write(&path, text).expect("write fixture");
        Self(path)
    }
    fn path(&self) -> &str {
        self.0.to_str().expect("Unicode fixture path")
    }
}
impl Drop for ConfigFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

#[test]
fn defaults_and_tray_switches() {
    let settings = resolve(&[], &[], true).expect("defaults");
    assert_eq!(settings.rate, Duration::from_secs(5));
    assert_eq!(settings.x_length, Duration::from_secs(300));
    assert_eq!(settings.sample_count, 60);
    assert_eq!(settings.theme, Theme::Default);
    assert!(settings.absolute);
    assert!(resolve(&["--abs"], &[], true).unwrap().absolute);
    assert!(!resolve(&["--signed"], &[], true).unwrap().absolute);
    assert!(Cli::try_parse_from(["wpm", "--abs", "--signed"]).is_err());
    assert_eq!(settings.tray, cfg!(windows));
    assert!(resolve(&["--tray"], &[], true).expect("tray").tray);
    assert!(!resolve(&["--tray=false"], &[], true).expect("no tray").tray);
}

#[test]
fn mode_dispatch_suppresses_tray_even_when_explicit() {
    for (flag, expected) in [
        ("--json", Mode::Json),
        ("--ndjson", Mode::Ndjson),
        ("--once", Mode::Once),
        ("--self-test", Mode::SelfTest),
    ] {
        for tty in [true, false] {
            let settings = resolve(&[flag, "--tray=true"], &[], tty).expect("mode");
            assert_eq!(settings.mode, expected);
            assert!(!settings.tray);
        }
    }
    let piped = resolve(&["--tray=true"], &[], false).expect("piped");
    assert_eq!(piped.mode, Mode::Json);
    assert!(!piped.tray);
    assert!(Cli::try_parse_from(["wpm", "--json", "--once"]).is_err());
}

#[test]
fn duration_and_timing_guards() {
    for (text, secs) in [
        ("300", 300.0),
        ("90s", 90.0),
        ("5m", 300.0),
        ("1h", 3600.0),
        ("1.5m", 90.0),
    ] {
        assert_eq!(parse_span(text), Ok(secs));
    }
    for text in ["", "0", "-1", "NaN", "inf", "1d", "1e309h"] {
        assert!(parse_span(text).is_err(), "{text}");
    }
    for rate in [0.0, 0.24, 61.0, f64::NAN, f64::INFINITY] {
        assert!(validate_timing(rate, 300.0).is_err());
    }
    for span in [0.0, -1.0, 2.0, f64::NAN, f64::INFINITY] {
        assert!(validate_timing(2.0, span).is_err());
    }
    assert!(validate_timing(0.25, 125_000.0).is_ok());
    assert!(
        validate_timing(0.25, 125_000.25)
            .expect_err("cap")
            .contains("500001")
    );
    assert!(
        validate_timing(60.0, 60.0)
            .expect_err("count")
            .contains("1 samples")
    );
    assert_eq!(validate_timing(0.25, 86400.0).expect("below cap").2, 345600);
    assert!(
        !resolve(&["--rate", "0.25"], &[], true)
            .expect("fast")
            .warnings
            .is_empty()
    );
}

#[test]
fn precedence_cli_then_environment_then_file_then_defaults() {
    let file = ConfigFile::new(
        "rate = 5.0\nx_length = 600\ntheme = 'mono'\ntray = false\nall_states = true\nlog = 'power.csv'\n",
    );
    let from_file = resolve(&["--config", file.path()], &[], true).expect("file");
    assert_eq!(from_file.rate, Duration::from_secs(5));
    assert_eq!(from_file.x_length, Duration::from_secs(600));
    assert_eq!(from_file.theme, Theme::Mono);
    assert!(!from_file.tray);
    assert!(from_file.all_states);
    assert_eq!(from_file.log, Some("power.csv".into()));
    let vars = [
        ("WPM_CONFIG", file.path()),
        ("WPM_RATE", "3"),
        ("WPM_X_LENGTH", "15m"),
        ("WPM_THEME", "high-contrast"),
    ];
    let from_env = resolve(&[], &vars, true).expect("env");
    assert_eq!(from_env.rate, Duration::from_secs(3));
    assert_eq!(from_env.x_length, Duration::from_secs(900));
    assert_eq!(from_env.theme, Theme::HighContrast);
    let from_cli = resolve(
        &[
            "--rate",
            "4",
            "--x-length",
            "1h",
            "--theme",
            "default",
            "--tray=true",
            "--log",
            "other.csv",
        ],
        &vars,
        true,
    )
    .expect("cli");
    assert_eq!(from_cli.rate, Duration::from_secs(4));
    assert_eq!(from_cli.x_length, Duration::from_secs(3600));
    assert_eq!(from_cli.theme, Theme::Default);
    assert!(from_cli.tray);
    assert_eq!(from_cli.log, Some("other.csv".into()));
    let other = ConfigFile::new("rate = 7\n");
    assert_eq!(
        resolve(&["--config", other.path()], &vars[..1], true)
            .expect("config override")
            .rate,
        Duration::from_secs(7)
    );
}

#[test]
fn malformed_config_warns_and_invalid_effective_values_fail() {
    for text in ["not toml", "theme = 'invalid'", "unknown = 1"] {
        let file = ConfigFile::new(text);
        let settings = resolve(&["--config", file.path()], &[], true).expect("fallback");
        assert_eq!(settings.rate, Duration::from_secs(5));
        assert_eq!(settings.warnings.len(), 1);
    }
    let file = ConfigFile::new("rate = 0\n");
    assert!(resolve(&["--config", file.path()], &[], true).is_err());
    assert!(resolve(&[], &[("WPM_RATE", "invalid")], true).is_err());
    assert!(resolve(&[], &[("WPM_THEME", "invalid")], true).is_err());
    assert!(
        resolve(
            &["--rate", "3", "--theme", "mono"],
            &[("WPM_RATE", "invalid"), ("WPM_THEME", "invalid")],
            true
        )
        .is_ok()
    );
    let missing = file.0.with_extension("missing");
    assert_eq!(
        resolve(&["--config", missing.to_str().expect("path")], &[], true)
            .expect("missing fallback")
            .warnings
            .len(),
        1
    );
}

#[test]
fn appdata_config_and_empty_log() {
    let file = ConfigFile::new("log = ''\n");
    assert!(
        resolve(&["--config", file.path()], &[], true)
            .expect("empty log")
            .log
            .is_none()
    );
    let root = file.0.with_extension("appdata");
    let directory = root.join("wpm");
    std::fs::create_dir_all(&directory).expect("config directory");
    let cli = Cli::try_parse_from(["wpm"]).expect("CLI");
    let settings = Settings::resolve(&cli, true, |key| {
        (key == "APPDATA").then(|| root.clone().into_os_string())
    })
    .expect("missing default config");
    assert_eq!(settings.rate, Duration::from_secs(5));
    assert!(settings.warnings.is_empty());
    std::fs::write(directory.join("config.toml"), "rate = 6\n").expect("default config");
    let settings = Settings::resolve(&cli, true, |key| {
        (key == "APPDATA").then(|| root.clone().into_os_string())
    })
    .expect("APPDATA config");
    std::fs::remove_dir_all(&root).expect("clean fixture directory");
    assert_eq!(settings.rate, Duration::from_secs(6));
}

#[cfg(not(windows))]
#[test]
fn xdg_config_home_and_home_precede_appdata() {
    let cli = Cli::try_parse_from(["wpm"]).expect("CLI");
    let root = std::env::current_dir()
        .expect("project directory")
        .join(format!("wpm-xdg-{}", std::process::id()));
    let xdg = root.join("xdg");
    let home = root.join("home");
    std::fs::create_dir_all(xdg.join("wpm")).expect("xdg directory");
    std::fs::create_dir_all(home.join(".config").join("wpm")).expect("home directory");
    std::fs::write(xdg.join("wpm").join("config.toml"), "rate = 9\n").expect("xdg config");
    std::fs::write(
        home.join(".config").join("wpm").join("config.toml"),
        "rate = 11\n",
    )
    .expect("home config");
    let xdg_os = xdg.clone().into_os_string();
    let home_os = home.clone().into_os_string();
    let settings = Settings::resolve(&cli, true, |key| {
        (key == "XDG_CONFIG_HOME").then(|| xdg_os.clone())
    })
    .expect("xdg config");
    assert_eq!(settings.rate, Duration::from_secs(9));
    let settings = Settings::resolve(&cli, true, |key| (key == "HOME").then(|| home_os.clone()))
        .expect("home config");
    assert_eq!(settings.rate, Duration::from_secs(11));
    // HOME must not shadow an explicit WPM_CONFIG.
    let settings = Settings::resolve(&cli, true, |key| match key {
        "HOME" => Some(home_os.clone()),
        "WPM_CONFIG" => Some(xdg.join("wpm").join("config.toml").into_os_string()),
        _ => None,
    })
    .expect("explicit config");
    assert_eq!(settings.rate, Duration::from_secs(9));
    std::fs::remove_dir_all(&root).expect("clean fixture directory");
}

#[test]
fn binary_help_version_and_usage_errors() {
    for flag in ["--help", "--version"] {
        let output = Command::new(env!("CARGO_BIN_EXE_wpm"))
            .arg(flag)
            .output()
            .expect("run binary");
        assert!(output.status.success());
        assert!(String::from_utf8_lossy(&output.stdout).contains("wpm"));
    }
    for args in [
        vec!["--rate", "0"],
        vec!["--json", "--once"],
        vec!["--tray=maybe"],
        vec!["--x-length", "NaN"],
    ] {
        let output = Command::new(env!("CARGO_BIN_EXE_wpm"))
            .args(args)
            .output()
            .expect("run binary");
        assert_eq!(output.status.code(), Some(2));
        assert!(output.stdout.is_empty());
    }
}
