# wpm — Windows laptop test candidate

A portable x64 Windows 10/11 terminal battery-flow monitor, with a default-enabled
numeric notification-area icon. **Battery flow is not whole-system or wall power.**
Negative watts mean discharge, positive watts charge. Missing driver data is `null`
in JSON, empty CSV cells, and `—` in the UI/tray; missing data is never zero-filled.
This is a test-deployment candidate, not a claim of full initial-spec completion.
No measurement, provider, interactive dashboard, or native tray code was executed
on the development machine. Hardware and shell behavior require the procedure below.

## Copy to another laptop and test

1. Copy `dist\wpm-windows-x64.zip` and its `.sha256` file to the target laptop.
   Compare `Get-FileHash .\wpm-windows-x64.zip -Algorithm SHA256` with the checksum.
2. Extract the ZIP into a writable folder (for example `Downloads\wpm-test`).
   Open PowerShell or Windows Terminal in that folder. No Rust installation,
   administrator rights, service, installer, or network access is required.
   The executable is unsigned; follow your organization's application policy.
3. Validate the extracted EXE checksum against `SHA256SUMS.txt`:

```powershell
Get-FileHash .\wpm.exe -Algorithm SHA256
.\wpm.exe --self-test > .\diagnostics-ac.json
.\wpm.exe --once
.\wpm.exe --json > .\sample-ac.json
```

4. Disconnect the charger, wait 15–30 seconds for firmware to update, and run:

```powershell
.\wpm.exe --self-test > .\diagnostics-battery.json
.\wpm.exe --json > .\sample-battery.json
.\wpm.exe --ndjson --rate 2 --log .\battery.csv
```

   After about a minute press Ctrl+C. Inspect JSON/CSV: discharge must be negative,
   voltage positive when available, amps signed `watts / volts`. Missing readings
   must remain null/empty. Check diagnostics if watts are unavailable; do not infer
   system consumption from battery percentage or AC/full zero battery flow.
5. Launch the dashboard in an interactive terminal (do not redirect stdout):

```powershell
.\wpm.exe --rate 2 --x-length 5m --log .\dashboard.csv
```

   Check live chart, session/window statistics, and the notification area's overflow
   panel. Pin the icon if desired; Windows controls placement. Hover for signed
   watts, direction, source and `battery_flow` scope. Right-click offers Show dashboard
   and Exit. Reconnect the charger and verify charging, idle or unknown as actually
   reported by the driver; no assumption of zero wall consumption is valid.
6. Try `?`, space, `a`, `s`, `r`, `e`, `l`, then `q`. Space freezes only the displayed
   snapshot; sampling, statistics, logging and tray continue live. Resume returns to
   current data. `r` resets history/session stats, `e` writes the displayed window to
   timestamped CSV+JSON in the current directory, `l` toggles append logging
   (`--log` path or `wpm.csv`). `a` toggles chart magnitude only; exports/stats stay signed.
7. Repeat with `--tray=false`. Check no icon appears. Repeat default launch and Exit
   from the tray, and again with Ctrl+C. Verify terminal echo/cursor are restored,
   logs are flushed and the icon disappears. Closing the console forcibly is not a
   guaranteed graceful shutdown.
8. Suspend/resume the laptop: chart must show a gap, not a connecting line across
   missing time; energy must exclude gaps over twice the sampling interval. While
   suspended Windows cannot redraw; after resuming, stale data becomes `—` until a
   fresh sample arrives. If practical, restart Windows Explorer using Task Manager
   while the dashboard runs: the icon should be restored automatically.
9. Try a non-battery Windows PC if available: diagnostics/JSON should succeed with
   missing watts; `--require-battery --json` returns exit 3 if presence cannot be
   confirmed. Keep both AC/battery diagnostic files for reports. Review hardware
   instance identifiers before sharing reports.

## CLI and configuration

`--json`: one JSON object and exit. `--ndjson`: flushed JSON lines until Ctrl+C or
closed pipe. `--once`: one human-readable sample. `--self-test`: structured provider
results, sample, application version and OS/architecture. These modes are mutually
exclusive, never use the tray/TUI, and suppress the tray even with `--tray=true`.
Nonterminal stdout defaults to one JSON object, not an infinite stream.

`--rate 0.25..60` seconds (default 2; subsecond sampling warns), `--x-length` positive
seconds or `s`/`m`/`h` duration (default 300s). Span must exceed rate and estimate no
more than 500000 points. `--all-states` includes charging/idle in stats (default:
discharge only). Charge and discharge Wh remain separate and do not bridge state
transitions. Statistics use measured monotonic time and trapezoidal integration;
missing endpoints and intervals over twice rate are excluded. Counts/averages are
sample-weighted, not time-weighted. History is bounded both by age and count.

`--abs` changes chart magnitude, `--step` enables stepped interpolation without
bridging gaps; `--theme default|high-contrast|mono` changes chart color.
`--tray[=true|false]` controls only the interactive tray (default true).

Optional `%APPDATA%\wpm\config.toml`:

```toml
rate = 2.0
x_length = 300
theme = "default"
tray = true
all_states = false
log = ""
```

Precedence: CLI > `WPM_RATE`, `WPM_X_LENGTH`, `WPM_THEME` > file > defaults.
`--config` overrides `WPM_CONFIG`, which overrides the implicit file path. Unknown
keys/malformed/unreadable files warn and are ignored as a whole. Invalid effective
timing/environment settings exit 2. `all_states` is enabled by either config or CLI.

CSV schema: `Time,State,Watts,Volts,Amps,BatteryPercent`; times are UTC RFC3339,
amps are signed. Append logs flush every ten samples, on pause/export and exit.
Existing nonempty files are assumed to have the correct schema; use a new filename
if unsure. Runtime logging errors disable logging and report to status/stderr.
Startup log failure exits 4. Other runtime errors exit 1. Success, including missing
measurements, exits 0; `--require-battery` without confirmed presence exits 3.

## Implementation and known limits

- WMI `ROOT\WMI BatteryStatus` is queried inside a dedicated COM-owning sampler.
  Kernel `GetSystemPowerStatus` supplies battery presence, AC status, percentage,
  runtime seconds and fallback state. WMI milliwatts/millivolts convert to W/V.
  `u32::MAX` is unknown; zero voltage is unknown. Idle zero watts requires both
  charge/discharge rates explicitly zero, not merely AC online.
- Only one active battery is supported. Multiple batteries deliberately yield
  unavailable watts rather than an unsafe sum. Missing/contradictory WMI direction
  flags yield unknown. Firmware refresh rates can be slower than sampling rates.
- **Not implemented:** PDH/system-power meters, capacity-delta estimation,
  full/design capacity, health/cycles, WMI percentage/ETA fallback, drain-rate/ETA
  estimates, advanced responsive layouts or every visual detail of the draft spec.
  Existing CLI flags are implemented as described above; no desktop wattage is
  fabricated. Diagnostics explicitly identify unimplemented fallback providers.
- A failed WMI connection is not automatically reinitialized; restart after driver
  or permission changes. WMI calls have no reliable cancellation; a stuck Windows
  provider may delay exit (including Ctrl+C). Tray uses its own freshness timer.
- Tray uses a dedicated hidden-window/message thread, bounded latest-reading slot,
  per-window DPI-aware numeric bitmap, unknown/stale indication (older than 2x rate),
  Explorer restart handling and GDI cleanup. Numeric icon shows magnitude; tooltip
  carries sign/state/scope. DPI/Explorer behavior still needs real laptop testing.
  Show dashboard may not focus an existing Windows Terminal tab due to host limits.
  Tray errors degrade to dashboard-only, with status and retry on updates.
- UI/output channel is bounded (64); slow consumers may lose samples, reported on
  the next delivered diagnostic. History/stats live in the main loop rather than
  sampler. Log/export I/O is synchronous; slow storage can affect dashboard updates.
- Forced process termination, console close or OS shutdown cannot guarantee cleanup
  or final buffered log writes. Stale UI clears watts but leaves last secondary
  values visibly associated with a STALE reading. Pause is explicitly labelled.
- No performance/startup/size budget or hardware accuracy has been certified.
  The static CRT is requested for the x64 MSVC build; Windows system DLLs remain
  normal OS dependencies. No installer, signing, update mechanism or telemetry.

## Build and package (does not run wpm)

Requires Rust MSVC stable and Visual Studio C++ build tools/Windows SDK.

```powershell
cargo fmt --all --check
cargo test --locked --all-targets
cargo clippy --locked --all-targets -- -D warnings
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\package.ps1
```

Tests use synthetic data/config only. The CLI process tests launch only help,
version and invalid-argument paths, which return before starting providers/UI/tray.
Do not add local hardware smoke tests when validation is prohibited.

Packaging builds `target\x86_64-pc-windows-msvc\release\wpm.exe` without executing it.
Outputs: `dist\wpm-windows-x64\{wpm.exe,README.md,SHA256SUMS.txt}`,
`dist\wpm-windows-x64.zip` and `.zip.sha256`. `dist` is gitignored.
The `docs\initial_spec` documents remain the original draft goals; this README
records the actual candidate behavior and deliberate scope limits.
