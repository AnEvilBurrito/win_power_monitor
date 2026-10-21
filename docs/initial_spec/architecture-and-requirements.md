# wpm — Architecture & Requirements

**Document:** 2 of 3 — repository layout, module design, functional and non-functional requirements
**Companion docs:** `design-spec.md`, `install-and-publishing.md`
**Status:** draft, pre-implementation

---

## 1. Technical baseline

| Item | Choice | Rationale |
|---|---|---|
| Language | Rust | single static `.exe`, no runtime dependency, negligible overhead while measuring power |
| TUI | `ratatui` 0.29 + `crossterm` 0.28 | mature, Braille `Canvas` for sub-cell plotting |
| Target | `x86_64-pc-windows-msvc` (arm64 as a later second installer entry) | winget portable |
| Windows APIs | `windows` 0.59 (`Win32_System_Power`, `Win32_System_Performance`, `Win32_UI_Shell`, `Win32_UI_WindowsAndMessaging`, `Win32_Graphics_Gdi`), `wmi` 0.15 | direct API access, including notification-area icon, no shell-outs |
| Distribution | winget portable, single `.exe` | no admin, no ARP pollution, trivial manifest |

Deliberately avoided: shelling out to PowerShell/`powercfg` on the hot path (process
spawn costs tens of ms and would dominate a 1 s sampling budget), and any MSIX packaging
(sandbox blocks PDH counters and `root\wmi`).

---

## 2. Repository layout

```
win_power_monitor/
├── README.md
├── design-spec.md                        # doc 1
├── architecture-and-requirements.md      # doc 2 (this file)
├── install-and-publishing.md             # doc 3
├── CHANGELOG.md
├── LICENSE                               # MIT OR Apache-2.0
├── .gitignore
├── rust-toolchain.toml
├── Cargo.toml
├── Cargo.lock
├── src/
│   ├── main.rs                # arg parse, mode dispatch, panic hook, terminal guard
│   ├── cli.rs                 # clap definitions, flag validation, NonZero guards
│   ├── config.rs              # TOML load/merge, precedence resolution
│   ├── sample.rs              # Sample, State, quality flags
│   ├── ring.rs                # time-based eviction ring buffer
│   ├── stats.rs               # session + window aggregation, energy integration
│   ├── export.rs              # CSV / NDJSON / JSON writers, BufWriter lifecycle
│   ├── app.rs                 # event loop, sampler thread, key handling, state machine
│   ├── tray.rs                # notification-area icon, Windows message loop, menu, cleanup
│   ├── ui/
│   │   ├── mod.rs             # band layout, responsive breakpoints, draw()
│   │   ├── chart.rs           # Canvas renderer, envelope bucketing, gap markers
│   │   ├── kpi.rs             # band A + B
│   │   ├── stats_band.rs      # band D
│   │   └── theme.rs           # color tokens, mono and high-contrast themes
│   └── source/
│       ├── mod.rs             # probe chain, ordering, provider selection
│       ├── probe.rs           # trait WattProbe, capability report
│       ├── wmi.rs             # root\wmi BatteryStatus / health / cycle count
│       ├── system.rs          # GetSystemPowerStatus (pct, AC, ETA fallback)
│       └── pdh.rs             # \Power Meter(*)\Power counter set
├── tests/
│   ├── csv_compat.rs          # schema compatibility with the original PS script
│   ├── stats_math.rs          # trapezoid energy, gap exclusion, windowed extrema
│   └── cli_validation.rs      # rate/x-length guards, exit codes
├── .github/workflows/
│   ├── ci.yml                 # fmt, clippy, test, cross-target check
│   └── release.yml            # build, smoke test, sign, release, winget submit
└── manifests/y/YourHandle/Wpm/0.1.0/
    ├── YourHandle.Wpm.yaml
    ├── YourHandle.Wpm.installer.yaml
    └── YourHandle.Wpm.locale.en-US.yaml
```

---

## 3. Module responsibilities

| Module | Owns | Must not |
|---|---|---|
| `main.rs` | process lifecycle, mode selection, terminal restoration, exit codes | hold domain logic |
| `cli.rs` | flag surface, validation, error text | touch Windows APIs |
| `config.rs` | TOML parsing, precedence | validate ranges (delegates to `cli`) |
| `source/*` | all Windows API access | format output, know about TUI |
| `ring.rs` | retention and eviction by age | compute statistics |
| `stats.rs` | aggregation, integration | read the clock directly (takes `Instant`s) |
| `export.rs` | serialization, file lifecycle | decide *when* to export |
| `app.rs` | orchestration, key dispatch, sampling thread | draw anything |
| `ui/*` | pure rendering from immutable app state | mutate state or call APIs |
| `tray.rs` | Windows message loop, `Shell_NotifyIconW`, numeric icon rendering, menu and icon lifecycle | query providers or compute statistics |

The `ui` layer is a pure function of `(&App, &Frame)` — no I/O, no clock reads. This
keeps the layout unit-testable against synthetic `Sample` vectors via ratatui's
`TestBackend`, which is how the responsive breakpoints in doc 1 §9 get tested.

---

## 4. Concurrency model

Two threads when the tray is disabled; three when enabled (the interactive default).

**Sampler thread** — owns the WMI connection and the ring buffer, sleeps on a deadline,
and publishes snapshots to the UI thread over an `mpsc` channel.

```
main (UI)                     sampler
  |                             |
  |-- spawn ------------------> |  builds probes here (COM thread affinity)
  |                             |  loop { sample; tx.send(snapshot)?; sleep_until(next) }
  |<-- mpsc::Receiver ----------|
  |-- draw(15-30 fps) --------->|
```

Hard constraints:

1. **COM is thread-affine.** `COMLibrary::new()` and the `WMIConnection` are constructed
   *inside* the sampler thread and never cross threads. Probe construction before
   `thread::spawn` is a correctness bug, not a style choice.
2. The UI thread never blocks on sampling. It drains with `try_iter()` each frame.
3. The sampler owns the ring; the UI receives owned snapshots (or an `Arc` with an
   epoch counter). No `Mutex` shared between both threads.
4. The ring prunes by age (`t < now − x_length`) so a stalled sampler shortens the span
   honestly rather than silently under-filling a fixed-capacity buffer.

**Sampling schedule** — deadline based, not sleep-after-work:

```rust
let start = Instant::now();
let mut n: u32 = 0;
loop {
    let next = start + rate * (n + 1);
    // ... sample and publish ...
    let now = Instant::now();
    if next > now { thread::sleep(next - now); }
    n += 1;
}
```

`Start-Sleep`-style drifting sleep makes `--rate 2` actually run at 2.1–2.3 s, which
falsifies the x-axis span. Energy integration uses measured `dt` regardless, so a stall
can never inflate Wh.

---

### 4.1 Windows notification area (system tray)

Interactive `wpm` launches the dashboard **and** a notification-area icon by default
(`--tray=true`). `--tray` is equivalent to `--tray=true`; `--tray=false` disables only
the icon and leaves the dashboard running. This is not a tray-only/background mode:
closing the dashboard exits the process and removes the icon.

- A dedicated tray thread owns a hidden window, its Windows message loop, and all
  icon resources. Use `Shell_NotifyIconW` to add, update, and delete the icon; handle
  Explorer's `TaskbarCreated` message to restore it after Explorer restarts.
- Reuse the existing sampler, never a second provider connection or sampling timer.
  The sampler publishes the latest immutable reading, source, and timestamp to a
  bounded latest-value handoff for the tray, independently of the TUI channel. Slow
  shell updates must not block sampling or accumulate snapshots. Notify the tray
  window with a posted message; do not poll at the TUI frame rate.
- Render a compact numeric wattage icon, for example `12.4`, at the notification
  area's current DPI. Round to fit; show the exact signed reading, unit, provider,
  charging/discharging state, and measurement meaning in the tooltip. Update on new
  samples at `--rate`, not on every dashboard frame.
- Unknown readings show `—`, never zero. If the latest sample is older than twice
  `--rate`, mark it stale and replace the numeric icon with `—`. A tray-thread timer
  checks freshness even if the sampler stops publishing. Pausing the dashboard must
  not leave an apparently live, frozen tray number.
- Battery readings describe battery discharge/charge power, **not total computer
  consumption**. AC/full `0 W` means no battery flow, not zero system draw. Only a
  provider measuring system power may label the value as system consumption.
- The context menu offers **Show dashboard** (restore/focus the existing console
  window where supported) and **Exit**. Exit, `q`, and `Ctrl+C` share coordinated
  shutdown: stop sampling, flush logs, delete the icon, release GDI/window resources,
  and restore the terminal. No duplicate dashboard or sampler is spawned.
- Icon initialization/update failures must not terminate the dashboard; report a
  degraded tray status there. Windows controls icon placement and may initially hide
  it in overflow; users can pin it to the visible notification area. No fixed-width
  taskbar text or forced placement beside the clock is promised.
- One-shot, streaming, diagnostic, and implicit non-TTY modes never create a tray
  icon, even with `--tray=true`; their existing output and exit contracts take priority.

---

## 5. Data model

```rust
pub enum State { Charging, Discharging, Idle, AcFull, Unknown }

pub struct Sample {
    pub at: Instant,          // monotonic; never wall clock
    pub wall: SystemTime,     // for CSV/JSON timestamps only
    pub watts: Option<f64>,   // signed; None = unknown, never a guess
    pub volts: Option<f64>,
    pub amps: Option<f64>,
    pub pct: Option<f64>,
    pub eta: Option<Duration>,
    pub state: State,
    pub gap: bool,            // dt exceeded 2*rate
}
```

### 5.1 Field provenance

| Field | Primary | Fallback | Missing semantics |
|---|---|---|---|
| `watts` | `root\wmi` `BatteryStatus.DischargeRate` / `ChargeRate` (mW ÷ 1000, negated when discharging) | PDH `\Power Meter(*)\Power`; then capacity-delta integration | `None` unless AC/full is confirmed, where `0.0` is a real measurement |
| `volts` | `BatteryStatus.Voltage` (mV ÷ 1000) | — | `volts == 0` → `None`, and `amps` becomes `None` |
| `amps` | `watts / volts` | — | `None` when `volts` is `None` or `≤ 0` |
| `pct` | `GetSystemPowerStatus.BatteryLifePercent` (255 = unknown) | `RemainingCapacity / FullChargedCapacity`; then `Win32_Battery.EstimatedChargeRemaining` | `None` |
| `eta` | `BatteryStatus.EstimatedRuntime` | `GetSystemPowerStatus.BatteryLifeTime` (`u32::MAX` = unknown) | `None` |
| `health` | `BatteryFullChargedCapacity.FullChargedCapacity ÷ BatteryStaticData.DesignedCapacity` | — | `None` |
| `cycles` | `BatteryCycleCount.CycleCount` | — | `None` |

Zero and unknown are strictly distinct (`design-spec.md` P2). `watts == Some(0.0)`
requires positive evidence of AC/full; a failed query yields `None`.

### 5.2 Sign convention

Negative = discharging, positive = charging. This is an intentional carry-over from the
original PowerShell prototype (`$watts = -$b.DischargeRate / 1000`), and it is what makes
the chart's zero baseline meaningful.

---

## 6. Probe chain and selection

At startup the sampler tries providers in cost order and keeps the first that produces a
plausible value.

```
1. wmi    root\wmi BatteryStatus            (laptop default; watts + volts + ETA)
2. pdh    \Power Meter(*)\Power             (desktop with energy meter)
3. k32    GetSystemPowerStatus only         (always available; pct + ETA, no watts)
4. none   no battery and no counter set     (degraded layout, exit 0 unless --require-battery)
```

PDH requires **two** `PdhCollectQueryData` cycles before its first value is meaningful,
so a PDH-only probe returns `None` on tick 0 and is only accepted into the chain once it
yields a finite value inside `(0, 500)` W. Values outside that band are driver faults and
are discarded, not plotted. Provider selection is reported in band A and in `--self-test`.

---

## 7. Statistics

Two scopes, both displayed:

| Scope | State | Cost | Reset |
|---|---|---|---|
| `session` | O(1) incremental sum/count/min/max | constant | `r` key |
| `window` | over the retained ring (≤ ~1200 samples) | recomputed each tick, negligible | automatic |

Rules:

- Default scope is `Discharging` only; charging and AC/full samples are excluded so
  `max` means peak draw. `--all-states` includes everything.
- Energy: trapezoidal integration of `watts` over measured `dt`, skipping any interval
  where `dt > 2 × rate` (gap) or where either endpoint is `None`.
- Drain rate: least-squares slope of `pct` vs time over the window, expressed as %/hr;
  requires ≥2 valid percentage samples spanning ≥10 s, otherwise `None`.
- Windowed extrema are single-pass folds, not sorted collections.

---

## 8. Output contracts

### 8.1 CSV (`--log <path>`)

Byte-compatible with the original PowerShell prototype so existing spreadsheets keep
working:

```csv
Time,State,Watts,Volts,Amps,BatteryPercent
2025-01-14T12:04:38.221,Discharging,-12.40,15.62,0.794,81
```

- One `BufWriter<File>` held open for the process lifetime; the original script reopened
  and appended per tick, which fails when Excel holds the file.
- Header written only when the file is created or is empty.
- Flushed every 10 samples, on pause, on export, and on exit (including `Ctrl+C`).
- A write failure is surfaced in band A (`log: error`) exactly once, and sampling
  continues — logging is never allowed to kill the dashboard.

### 8.2 JSON / NDJSON

`--json` prints one object and exits. `--ndjson` streams one object per line. Both are
the scriptable contract and must never launch a TUI.

```json
{"ts":"2025-01-14T12:04:38.221Z","state":"Discharging","watts":-12.4,"volts":15.62,
 "amps":0.794,"pct":81,"eta_s":8040,"source":"wmi","gap":false,"available":true}
```

No-battery machine:

```json
{"ts":"2025-01-14T12:04:38.221Z","available":false,"reason":"no_battery","probes":
 {"wmi":false,"pdh":false,"k32":true}}
```

**This exits 0.** winget's automated validation invokes the command, and a non-zero exit
on a battery-less validation VM would fail the package for reasons entirely outside the
user's control.

### 8.3 Non-TTY behaviour

If stdout is not a TTY, absent an explicit mode flag, `wpm` behaves as `--once` and exits
0. Launching the dashboard into a pipe would block a validation harness indefinitely.

---

## 9. Configuration

`%APPDATA%\wpm\config.toml`:

```toml
rate = 2.0          # seconds between samples
x_length = 300      # seconds of history on the x-axis
theme = "default"
tray = true         # interactive dashboard also shows a notification-area icon
all_states = false
log = ""            # empty = logging off
```

Precedence: **CLI flag > environment variable (`WPM_RATE`, `WPM_X_LENGTH`) > config file
> built-in default.** A malformed config file is a warning in band A, not a launch
failure — a broken theme key must not prevent someone from seeing their battery.

---

## 10. Exit codes

| Code | Meaning |
|---|---|
| 0 | normal exit, including "no battery" without `--require-battery` |
| 1 | unexpected internal error |
| 2 | usage error (bad flags, terminal too small, invalid config that cannot be defaulted) |
| 3 | required capability missing (`--require-battery` with no battery) |
| 4 | log path unwritable at startup |

---

## 11. Functional requirements

| ID | Requirement | Acceptance |
|---|---|---|
| FR-01 | Launch `wpm` in PowerShell with no args → full dashboard plus numeric notification-area icon | manual, on a laptop |
| FR-02 | `--rate <secs>` sets the sampling period | measured period within ±5% over 60 s |
| FR-03 | `--x-length <secs>` sets the history span independent of `--rate` | at rate 5, span is exactly 300 s / 60 points |
| FR-04 | `--rate ≥ --x-length` rejected with exit 2 and a message naming the sample count | `--rate 300 --x-length 300` → exit 2 |
| FR-05 | Sample count estimates > 500k rejected with the computed number in the message | `--rate 0.25 --x-length 86400` → exit 2 |
| FR-06 | Ring evicts by age, so stalls shorten the span | suspend/resume test shows gap + shortened span |
| FR-07 | Gaps (`dt > 2×rate`) break the line and are excluded from Wh | unit test on synthetic samples |
| FR-08 | `session` and `window` stats both displayed, both state-scoped to discharging | compare against a hand-computed fixture |
| FR-09 | `--log` writes the exact prototype CSV schema | golden-file test in `tests/csv_compat.rs` |
| FR-10 | `--json` / `--ndjson` never launch the TUI and exit 0 with no battery | CI job on `windows-latest` |
| FR-11 | Non-TTY stdout defaults to one-shot JSON | `wpm | cat` in CI |
| FR-12 | `--self-test` prints provider results, sample, and versions for bug reports | manual |
| FR-13 | `q` and `Ctrl+C` restore the terminal and flush the log | manual + panic-hook test |
| FR-14 | Degraded no-battery layout with stated reason and probe results | CI runner (no battery) |
| FR-15 | Responsive breakpoints shed chrome in the documented order | `TestBackend` snapshot tests at 120/100/80/60 × 32/24/14 |
| FR-16 | `--tray` defaults to true; `--tray=false` disables only the icon; config `tray` follows existing precedence | CLI/config tests and manual launch with both values |
| FR-17 | Tray icon shows latest wattage at the sampling interval; tooltip identifies exact value, source, state, and battery versus system power | synthetic readings plus manual Windows 10/11 DPI checks |
| FR-18 | Unknown/stale tray readings show `—`, not zero or a frozen live number | synthetic missing samples and sampler stall beyond `2 × rate` |
| FR-19 | Tray reuses one sampler; Show dashboard restores/focuses where supported; Exit removes icon and flushes logs | manual menu, shutdown, and Explorer restart checks |
| FR-20 | Noninteractive modes never create a tray icon; tray failure never kills the dashboard | mode-dispatch tests including explicit `--tray=true`, plus simulated shell failure |

---

## 12. Non-functional requirements

| ID | Requirement | Budget |
|---|---|---|
| NFR-01 | Measurement fidelity: `wpm` must not materially change what it measures | ≤ 0.2% CPU at `--rate 2`; warn in help below `--rate 1` |
| NFR-02 | Flat memory over arbitrarily long runs | ring bounded by `x_length/rate`; session stats O(1) |
| NFR-03 | Startup to first frame | < 250 ms including WMI connect |
| NFR-04 | Binary size | < 6 MB stripped, single file, no DLLs |
| NFR-05 | Sampling cost per tick | one WMI query + one kernel32 call (the prototype used two full WQL class enumerations) |
| NFR-06 | Never fabricate data | unknown renders as `—`/gap; enforced by `Option` in the type system |
| NFR-07 | No panics on any provider failure path | `#![deny(clippy::unwrap_used)]` outside tests |
| NFR-08 | Terminal always restored, including on panic | `panic::set_hook` → `ratatui::restore()` |

---

## 13. Testing strategy

| Layer | Method |
|---|---|
| Stats math | unit tests with synthetic `Sample` vectors: trapezoid energy, gap exclusion, single-signed extrema, charge/discharge scoping |
| CSV compatibility | golden file diffed byte-for-byte against a fixture produced by the original PowerShell script |
| CLI validation | table-driven tests for FR-04/FR-05 and exit codes |
| Rendering | `TestBackend` snapshots at the breakpoint matrix, asserting band presence/absence rather than exact glyphs |
| Windows integration | CI job on `windows-latest`: `--json` twice, `--ndjson` for 5 s, `--once`, `--self-test`; all must exit 0 on a battery-less runner |
| Tray | unit tests for numeric formatting, missing/stale readings, mode dispatch and config precedence; manual Windows 10/11 checks for DPI, overflow pinning, tooltip, menu, Explorer restart, shutdown cleanup and tray failure |
| Manual | suspend/resume gap handling, Excel file-lock behaviour, Windows Terminal vs conhost; CPU/memory budgets include the default-enabled tray |

The CI matrix deliberately includes the *no-battery* case, because that is exactly the
environment winget validation uses.

---

## 14. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| `DischargeRate` absent or bogus on some drivers | no watts | capacity-delta fallback; PDH chain; honest `—` |
| PDH `Power Meter` counter set missing on most desktops | no desktop support | documented limitation; probe chain reports it explicitly |
| WMI query latency on slow drivers | sampling jitter | deadline scheduler; `dt` from `Instant`; warn below 1 s rate |
| Raw mode swallows `Ctrl+C` | orphaned process / broken terminal | explicit key handler + panic hook + `ctrlc` in stream modes |
| CSV locked by Excel | silent log loss | single writer, surfaced error, sampling continues |
| `--rate`/`--x-length` confusion (seconds vs points) | wrong window silently | `--x-length` documented as seconds; point count shown in band A; `--help` states the derivation |

---

## 15. Implementation order

1. `source/*` + `--json`/`--ndjson` — validate the data layer against the existing
   PowerShell script before any TUI work.
2. `ring`, `stats`, `export` + golden CSV test.
3. `ui/*` skeleton with `TestBackend` snapshots for the breakpoint matrix.
4. `app.rs` event loop, pause/reset/log toggle.
5. Release pipeline, signing, winget first submission.
