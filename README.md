<div align="center">

# wpm · Windows Power Monitor

**See your laptop’s battery flow, live.**

A portable Windows terminal dashboard with a numeric tray icon, live charts, and simple exports.

**Windows 10/11 · x64 · GPL-3.0-only**

![wpm terminal dashboard showing battery flow, history, and session statistics](docs/promo.png)

</div>

## Features

- **Live dashboard.** Watts, voltage, current, battery percentage, remaining-time estimates, and a scrolling chart.
- **Battery capacity (v0.1.2).** Each battery shows remaining / full-charge Wh, for example `bat 1: 18.0 / 23.0 Wh; bat 2: 30.0 / 45.0 Wh`. Unknown, relative or inconsistent values show a dash. Device-based numbers stay stable for this program session across reorder/detach/reattach (not physical locations); changed device IDs get new numbers. Invalid/transition collections clear capacities, and stale readings display dashes. Narrow lines truncate with an ellipsis; help/JSON retain all batteries.
- **Elapsed time.** The final metrics row shows live monotonic **Program elapsed** (also during display freeze) and **stats** duration since launch/reset. Reset clears statistics, not program runtime; paused stats duration follows the frozen snapshot. Wh integrates valid covered intervals only, excluding gaps, and is not wall consumption.
- **Watts in system tray.** A numeric notification-area icon keeps the current reading visible alongside the dashboard.
- **Useful history.** Session/window statistics and separate charge/discharge energy totals.
- **Take the data with you.** Append CSV logs, export the displayed window to CSV + JSON, or stream NDJSON.

**Battery flow is not whole-system or wall power.** On AC, a full battery can show little or no flow while the laptop still consumes power. Hardware and firmware determine which readings are available and how often they refresh.

## Experimental multi-battery support

**Experimental multi-battery aggregation; user-confirmed working on a Surface Book with v0.1.1.**

Native system batteries are deduplicated by Windows device instance ID and device-scoped tag. Combined watts are signed physical flow; mixed charge/discharge remains **Mixed**, including zero net flow. Multiple batteries have no combined voltage/current. Energy and capacity-weighted percentage require complete compatible absolute capacity metadata; mixed ETA is unavailable.

Three fresh discovery/status passes bracket each collection (status IOCTL `Timeout=0`); membership, tags and direction/AC observations must agree. A failed or changing collection publishes unavailable, never partial/cached totals or WMI substitutes. The first stable sample after a transition is also unavailable; the following stable tick can recover. Calls are sequential, not an atomic hardware snapshot. Retries are bounded (at most two tag attempts per device per pass), but synchronous driver calls cannot be cancelled: a stalled driver can delay sampling/shutdown. No elevation is required.

Topology epochs, invalid samples and long time gaps break charts/integration and reset estimators. Session/window statistics can include older sets; net battery energy is not gross per-battery throughput, and mixed energy is included only with `--all-states`. System input energy remains separate. Freeze affects only the displayed snapshot; sampling, logs and tray continue.

JSON adds `topology_epoch`, `transition`, `battery_count`, collection start/end Unix seconds, and `batteries` with device IDs/tags, read availability/reasons, raw capacity/information metadata and electrical values. Review IDs before sharing diagnostics. CSV columns stay unchanged; consumers must accept the new `Mixed` state and null/unavailable transition fields. JSON per-battery fields add `index`, `remaining_wh` and `full_charged_wh` (Wh or null). Main dashboard uses session ordinals, not device IDs. No physical location is inferred from enumeration order.

Version 0.1.2 adds capacity and elapsed metrics; v0.1.1 checkpoints experimental multi-battery support; the existing v0.1.0 tag is unchanged. Validation packages are unpublished candidates, not replacements for released assets.

## Install

Requires **Windows 10 or 11 (x64)** and a supported laptop battery/driver for battery-flow readings. No Rust installation, separate runtime, service, or administrator elevation is required to run the portable executable; local security policies still apply.

**Portable builds are unsigned.** Winget distribution is planned. For now, build from source using the instructions below, or extract a supplied candidate ZIP into a writable folder after verifying its SHA-256 checksum.


## Usage

Open PowerShell or Windows Terminal in the extracted folder:

```powershell
.\wpm.exe                                  # Dashboard + tray; samples every 5 seconds
.\wpm.exe --rate 2 --x-length 5m             # Two-second sampling, five-minute chart
.\wpm.exe --log .\battery.csv               # Dashboard with CSV logging
.\wpm.exe --once                            # One readable sample
.\wpm.exe --json                            # One JSON sample
.\wpm.exe --ndjson --rate 2                  # JSON stream; Ctrl+C to stop
.\wpm.exe --self-test                       # Provider diagnostics
```

Displays use magnitudes with direction shown separately. Use `--signed` for signed values; exports always use negative watts for discharge and positive for charge. `--tray=false` disables the tray. One-shot/stream modes do not open the dashboard or tray.

| Key | Action |
| --- | --- |
| `?` | Help and diagnostics |
| `Space` | Freeze/resume the display; sampling continues |
| `a` / `s` | Toggle magnitude/signed display / stepped chart |
| `r` | Reset history and session statistics |
| `e` / `l` | Export displayed window / toggle CSV logging |
| `q` | Quit |

## Configuration

Optional settings live in `%APPDATA%\wpm\config.toml`:

```toml
rate = 5.0
x_length = 300
tray = true
theme = "default"
```

CLI options override `WPM_RATE`, `WPM_X_LENGTH`, and `WPM_THEME`, then the file and defaults. Use `--config` for another file and `--help` for options.

## Build from source

Requires **Rust stable (MSVC toolchain)**, **Visual Studio C++ Build Tools**, and the **Windows SDK**.

```powershell
git clone https://github.com/AnEvilBurrito/win_power_monitor.git
cd win_power_monitor
cargo build --locked --release --target x86_64-pc-windows-msvc
.\target\x86_64-pc-windows-msvc\release\wpm.exe
```

Desktop watts require specialized hardware and explicit verified meter selection; most desktops cannot report total system power.

## License

GNU GPL version 3 only (`GPL-3.0-only`). Binary redistribution requires matching complete Corresponding Source under GPLv3.
