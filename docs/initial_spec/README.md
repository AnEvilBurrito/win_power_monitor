# win_power_monitor

Specification for **`wpm`** — a Windows terminal dashboard for live power consumption,
battery life, and energy statistics, distributed via winget.

Nothing is implemented yet; this folder holds the agreed design.

## Documents

| File | Covers |
|---|---|
| [`design-spec.md`](design-spec.md) | TUI shape, bands, state views, rendering rules, responsive breakpoints, rejected patterns |
| [`architecture-and-requirements.md`](architecture-and-requirements.md) | repo layout, modules, concurrency, data model, probe chain, FR/NFR tables, testing |
| [`install-and-publishing.md`](install-and-publishing.md) | install paths, CLI reference, output contracts, winget manifests and submission runbook |

## Locked decisions

These were settled during design and should not be re-litigated without reason.

1. **Binary and winget command are `wpm`.** Crate may be `wpm-cli`; the shim is the contract.
2. **Rust + ratatui**, single static `.exe`, MSVC target, no runtime dependency.
3. **winget portable**, `InstallerType: portable`, single exe. No MSIX (sandbox blocks PDH
   and `root\wmi`); MSI only if ARP visibility is ever demanded.
4. **`--rate` = seconds per sample** (default 2). **`--x-length` = seconds of history on
   the x-axis** (default 300) — *not* a point count. Samples are derived:
   `ceil(x_length / rate)`. The `--window` flag was dropped as redundant.
5. **Signed watts**: negative = discharging, positive = charging. Carried over from the
   original PowerShell prototype; makes the chart's zero baseline meaningful.
6. **One time-series only** — the chart. No sparklines, no second plot, ever.
7. **Never fabricate a value.** Unknown is `None`/`—`/a gap, never `0.0`.
8. **`--x-length` is a time span, so the ring evicts by age**, not by count.
9. **Non-TTY stdout falls back to one-shot JSON**; the no-battery path exits 0 — this is
   what keeps winget's validation pipeline from hanging or failing.
10. **Stats are state-scoped to discharging** by default, in two scopes (`session`, `window`).

## Next step

Per `architecture-and-requirements.md` §15: build `src/source/*` plus `--json`/`--ndjson`
first and validate the numbers against the original PowerShell script before any TUI work.
