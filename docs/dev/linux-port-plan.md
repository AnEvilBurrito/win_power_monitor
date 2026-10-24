# Linux provider — implementation plan

Status: implemented on Linux (`src/sysfs.rs`, `src/provider.rs`, packaging, CI).
Windows path unchanged and cross-checked with `--target x86_64-pc-windows-msvc`;
see §4. Target: `x86_64-unknown-linux-gnu` laptops with
`/sys/class/power_supply` batteries.

Scope: dashboard, chart, history, CSV/JSON/NDJSON, `--once`, `--self-test`.
Out of scope: system tray, PDH-equivalent desktop wattage, suspend/resume hooks.

## 1. Windows-build safety rules (the actual constraint)

This box is Linux; a Windows build cannot be verified locally. The guarantee rests
on gating plus the `windows` CI job, not on local compilation. Therefore:

1. **No new dependencies.** sysfs is `std::fs` string parsing. `Cargo.toml` and
   `Cargo.lock` stay byte-identical, so `cargo build --locked` on Windows is unaffected.
2. **No edits to `#[cfg(windows)]` blocks.** `src/provider.rs:81-164`,
   `src/native_battery.rs:353-717` (`mod device` + `WindowsBackend`),
   `src/power_meter.rs:42-193`, `src/tray.rs:91-431` are read-only.
3. **No edits to shared helpers whose behavior Windows depends on:** `apply_members`
   (`native_battery.rs:257`), `canonical` (`:70`), `Collector::collect` (`:98`),
   `Stats`/`Metrics`/`History` (`model.rs`), `ui::draw`. Linux differences are
   absorbed by the backend, not by branching the shared path.
4. **Every non-`pub` Linux-only item gets an explicit `cfg`.** Windows CI runs
   `cargo clippy --locked --all-targets -- -D warnings`; a `cfg`-less helper unused
   on Windows is a hard failure.
5. **Test files that touch the backend start with `#![cfg(target_os = "linux")]`.**
   Repo-wide `cargo test --locked` runs on `windows-latest`; an ungated Linux test
   breaks that job at compile time.
6. **Merge gate:** `ci.yml` `windows` job green, unchanged. Do not relax it.

## 2. File changes

### New

| File | Why |
|---|---|
| `src/sysfs.rs` | Pure parser + `SysfsBackend`. Split below so parsing is testable on every OS. |
| `tests/sysfs.rs` | Fixture-driven parser tests (run on Windows too), plus `#![cfg(target_os = "linux")]` backend walk test. |
| `scripts/package-linux.sh` | Tarball + `sha256sum`, mirroring `package.ps1` (which is scoped to `dist\wpm-v*-windows-x64*` and therefore unaffected). |
| `scripts/verify-package-linux.sh` | Mirrors `verify-package.ps1`. |

`src/sysfs.rs` contents and gating (as implemented):

```
pub fn parse_member(id, t: &Attrs, ac: Option<bool>, diagnostics: &mut Vec<String>) -> Member  // always compiled
pub fn is_battery / ac_online / percent / state / micro / power_mw / remaining_mwh / full_mwh / design_mwh // always compiled
#[cfg(target_os = "linux")] pub struct SysfsBackend { root, ac, pct }                           // fs walk only
#[cfg(target_os = "linux")] impl Backend for SysfsBackend                                      // reuses existing trait
```

`parse_member` never fails: unusable readings become the shared model's own
sentinels (`i32::MIN` rate, `u32::MAX` capacity), which `diagnostic()` already
converts into published reason codes. `Attrs` is a `BTreeMap<String, String>` so
fixtures need no filesystem.

`pub` items in a library crate are not dead-code-linted, so the always-compiled
parser is warning-free on Windows and keeps fixture tests in the Windows CI job —
consistent with the synthetic coverage this repo already relies on.

Unit mapping into `native_battery::Reading` (milli-units, Windows sentinels):

| sysfs attribute | Field | Transform |
|---|---|---|
| `power_now` (µW) | `rate` (mW, signed) | `/1000`, negative when `status=Discharging` |
| `voltage_now` (µV) | `voltage` | `/1000` |
| `current_now` (µA) + `voltage_now` | `rate` | `µA·µV·1e-9` → mW, only when `power_now` absent |
| `energy_now` (µWh) | `capacity` | `×1e-3` |
| `energy_full` (µWh) | `full_charged_capacity` | `×1e-3` |
| `charge_now`/`charge_full` (µAh) | capacity fields | `µAh·µV·1e-9` → mWh |
| `status` | `power_state` | bit0 = AC (`type=Mains` `online`), bit1 discharging, bit2 charging |
| `capabilities` | synthesized | `0x8000_0000` set; `0x2000_0000`/`0x4000_0000` clear |

Filtering (as implemented, and note the inversion from the draft): a supply is a
battery when `type=Battery`, `scope` is **not** `Device`, `present` is not `0`, and
at least one of `energy_now`/`charge_now`/`power_now`/`voltage_now` parses. `scope`
above is deliberate: kernel default scope is `System`, so requiring `Device` would
have discarded every laptop pack while keeping wireless-mouse batteries. The
attribute requirement is what excludes HID peripherals that publish only
`capacity`. AC comes from `type=Mains` (`online`), encoded into `power_state` bit 0
so `apply_members` stays truthful without a post-hoc overwrite, and re-applied to
`s.ac_online` by the provider. Missing attribute → `None`, never `0`.

Two deliberate behaviours worth knowing:

- `status=Full`/`Not charging` with a non-zero `power_now` is recorded as 0 W, with
a diagnostic line. `diagnostic()` rejects idle-with-flow (`native_unknown_or_conflicting_rate`),
so without this every plugged-in, fully-charged laptop would report *unavailable*.
Windows' own native path does the same (idle requires zero flow).
- `Collector` calls `snapshot` three times per tick, so per-supply notes repeat;
the provider calls `s.diagnostics.dedup()` (shared code untouched).

### Edits (5 files, all additive)

| File | Change | Why |
|---|---|---|
| `src/lib.rs` | `+ pub mod sysfs;` | Register module. Only shared-file line touched. |
| `src/provider.rs` | Narrow the existing `#[cfg(not(windows))]` stub (`:165-186`) to `#[cfg(all(not(windows), not(target_os = "linux")))]`; add `#[cfg(target_os = "linux")] impl Provider` | Exactly one `impl Provider` per platform; keeps macOS/BSD stub honest. Linux impl: `SysfsBackend` → `Collector::collect` → repair `s.source` → AC override → no-battery reason → `capacity`% fallback. `--system-meter` emits a diagnostic instead of silently ignoring the flag. |
| `src/config.rs` | `:46-48` add `XDG_CONFIG_HOME` else `$HOME/.config` fallback; `:122` `unwrap_or(true)` → `unwrap_or(cfg!(windows))` | `%APPDATA%` is unset on Linux; `cfg!` is a literal bool so Windows resolution is unchanged. Explicit `--tray=true` still honored. |
| `src/cli.rs` | `:14` `about`, `--system-meter`/`--tray` help text made OS-neutral | "Windows power monitor" is wrong on Linux. Cosmetic; no parsing change. |
| `src/ui.rs` | `:163-168` add `"native:sysfs_power_supply" => "sysfs"` | Without it, `other => other` already renders the raw source string truthfully, so this is display polish. |
| `tests/cli_config.rs` | `assert!(settings.tray)` → `assert_eq!(settings.tray, cfg!(windows))`; add an `xdg_config_home_and_home_precede_appdata` test | The old assertion encoded the Windows-only default and would fail on Linux; the new test pins the XDG order and that `WPM_CONFIG` still wins. |

### Untouched

`src/model.rs`, `src/export.rs`, `src/app.rs`, `src/tray.rs`, `src/power_meter.rs`,
`src/native_battery.rs` (windows blocks and shared helpers), `Cargo.toml`,
`Cargo.lock`, `scripts/*.ps1`, `.github/workflows/candidate.yml`.

`src/app.rs` needs nothing: the tray is already `Option<Tray>` and `tray_hwnd`
is `None` when disabled (`:66-69`, `:106-108`); the non-Windows `platform` stub
(`tray.rs:433`) is never constructed once the default is `false`.

### `.github/workflows/ci.yml`

`linux` job added (`ubuntu-latest`) with the same four steps as `windows`,
ending in `./scripts/package-linux.sh`. The `windows` job is unchanged.

### Test seam: `WPM_SYSFS_ROOT`

The Linux `Provider` reads `WPM_SYSFS_ROOT` (default
`/sys/class/power_supply`) so the whole binary can be exercised against a
fixture tree. `tests/sysfs.rs::binary_reads_a_fixture_root_and_reports_absent_batteries`
runs `wpm --once` against a discharging fixture (asserting `Discharging: 12.00 W`)
and `wpm --json --require-battery` against an AC-only fixture (asserting exit 3
and `no_battery_supply`). Without it the Linux CI job would validate no
hardware-dependent behaviour at all.

## 3. Phases (all complete)

| # | Work | Gate |
|---|---|---|
| 1 | `src/sysfs.rs` parser + `tests/sysfs.rs` fixtures (no fs access) | `cargo test` green, fixtures cover: `power_now` present/absent, `current_now`×`voltage_now`, `charge_*`-only, dual `BAT0`/`BAT1`, `status=Unknown`, missing `energy_full` |
| 2 | `SysfsBackend` + `#[cfg(target_os = "linux")] impl Provider` | `wpm --once` and `--self-test` report real values on a laptop; `--require-battery` exit code 3 on desktop |
| 3 | `config.rs` XDG path, tray default, `cli.rs` text, `ui.rs` label | `wpm` with no config file resolves `~/.config/wpm/config.toml`; nothing constructs the tray stub |
| 4 | `scripts/package-linux.sh`, `verify-package-linux.sh`, `ci.yml` linux job, README | `dist/wpm-v*-linux-x64.tar.gz` verifies; `windows` CI still green |

## 4. Verification

```bash
cargo fmt --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked
cargo build --locked --release
./target/release/wpm --self-test
./scripts/package-linux.sh
```

Result on the reference Linux machine: `fmt` clean, `clippy -D warnings` clean,
69 tests green (7 suites, including 11 sysfs), release tarball verified and
byte-reproducible, and `--once`/`--self-test`/dashboard read a real `BAT0`
(`Idle`, 11.66 V, 80.33%, 76.85 Wh) — the battery is on AC, so the discharging
path is covered by fixtures rather than hardware.

Windows could not be built here (no MSVC linker), but the Windows code path *is*
verified: `cargo check` and `cargo clippy --all-targets -- -D warnings` both pass
with `--target x86_64-pc-windows-msvc` after `rustup target add`, which compiles
the `wmi`/`windows-sys` provider, the tray, and every test binary's Windows
branch. CI's `windows` job additionally runs `cargo test` and `package.ps1`,
which need a real toolchain and a real machine.

Acceptance = the `windows` CI job green with only the gated additions in §2. If a
Windows warning ever appears, the cause is a `cfg`-less item per §1 rule 4 — fix
the gate, do not touch shared code.

## 5. Decisions taken

- **Name:** `wpm` stays (already `[[bin]] name = "wpm"`). Collision to document:
  `pip install --user wpm` (typing test) writes `~/.local/bin/wpm`, the same path
  the portable tarball would use.
- **Desktop wattage:** no PDH analogue; `--system-meter` reports unsupported on Linux.
- **Tray:** permanently out of scope for Linux; no `libappindicator` dependency.

## 6. Known gaps, deliberately deferred

- `--self-test` `limitations` text lives in shared `src/app.rs:150-152` and is
  Windows-flavored (`PDH meter`, `WMI percentage fallback`). Accurate for Windows,
  misleading in a Linux report. Fixing it is a shared-file edit that needs Windows
  review, so the Linux report currently carries it unchanged; the per-sample
  `diagnostics` array is accurate.
- A UPS exposing `type=Battery` with `energy_now` attributes would be collected as
  a battery. Same ambiguity as Windows `BATTERY_IS_SHORT_TERM` filtering; not
  resolved here.
- No suspend/resume or udev hooks; a topology change is detected on the next poll
  by `Collector`'s membership/tag comparison.
- `status=Unknown` with a usable `power_now` is reported unavailable
  (`native_unknown_or_conflicting_rate`) rather than inferring direction from the
  sign, per the project's no-inference stance. It self-heals on the next poll.
