# Version 0.1.4 - theme and diagnostics readability fixes for 0.1.3

- Apply the selected theme background to the chart canvas rather than falling
	back to black inside the plot.
- Restore yellow discharge text for default and high-contrast terminal themes;
	named theme palettes and monochrome behavior are unchanged.
- Restore the battery bar with 16 cells on roomy views and six at 80 columns.
	Hide it on narrow views or stale/unknown percentages. Low-battery red and the
	`low` label at or below 20% are preserved.

# Version 0.1.3 - themes, diagnostics, and platform support

## Dashboard and themes

- Refine the chart-first layout with neutral current watts, small flow accents,
	a stable chart colour, window-first statistics, and separate electrical detail
	on roomy terminals. Compact layouts preserve chart space.
- Add Tokyo Night, Tokyo Night Storm, Catppuccin Latte / Frappe / Macchiato /
	Mocha, and Nord alongside default, high-contrast, and mono. Select at startup
	with `--theme`, `WPM_THEME`, or TOML `theme`, in that precedence order.
- Known battery levels at or below 20% show a red accent and `low` label;
	mono retains the label. Stale/transition values do not trigger warnings.

## Diagnostics

- Put telemetry first in the `?` overlay, with arrows, PageUp/PageDown, and
	Home/End scrolling for long diagnostics and multi-battery details.
- Show per-battery design capacity in Wh where supported and capacity-based
	health (full-charge / design capacity * 100). Unknown metadata stays unavailable;
	relative units are not labeled Wh. Valid health above 100% is not clamped.
	Health describes retained capacity, not state of charge or a safety diagnosis.
- Add an experimental idle-power proxy: find the unique mode of consumption
	rounded to whole watts, then average original readings within +/-1 W of that
	mode. Ties and estimates at or above the overall arithmetic mean are unavailable.
- Add average-consumption ETA from remaining Wh and time-weighted discharge
	power, separate from the existing ETA. Missing capacity stays unavailable.
- Add idle percentage as the fraction of eligible readings strictly below their
	arithmetic mean. Neither idle metric measures actual system inactivity.
- Derived analytics require at least 10 valid readings and 60 seconds in the
	latest contiguous eligible history segment. Charging/mixed battery flow is
	excluded; confirmed system-input meters support idle proxies, not battery ETA.
- Cache analytics and refresh at most every 30 seconds, only while diagnostics
	is visible and history has changed. No extra hardware polls. Pause freezes
	displayed results; reset clears them. Existing ETA and export schemas remain
	unchanged. Real-workload estimator validation is still pending.

## Linux support

- Support laptop batteries through `/sys/class/power_supply`, reusing shared
	multi-battery collection, signed flow, capacity display, and diagnostics.
	Linux hardware capacity reporting is developer-confirmed working.
- Provide Linux x64 tarball packaging and verification tooling. No system tray
	or whole-system desktop power meter; no elevation or monitoring service needed
	for readable sysfs telemetry. Distribution/libc compatibility is not universal.

## Experimental macOS support

- Acquire AppleSmartBattery IORegistry plist data through `ioreg`, including
	signed current/voltage conversion and charge-capacity percentage ratios.
- Remaining/full/design energy capacity and capacity-based health stay
	unavailable; average-consumption ETA consequently cannot use remaining Wh.
	Missing or conflicting telemetry remains unavailable. Session Wh integration
	continues to use measured battery flow.
- Add portable and macOS CLI fixtures, native CI, and Apple Silicon / Intel
	tarball packaging. Real-hardware validation remains pending. No menu-bar
	integration or whole-system power measurement; builds are not Developer ID
	signed or notarized. Windows binaries also remain unsigned.

# Version 0.1.2 — capacity and elapsed metrics

- Dedicated per-battery remaining / full-charge line: `bat 1: 18.0 / 23.0 Wh; bat 2: 30.0 / 45.0 Wh`; single batteries supported. One decimal, absolute Wh only. Unknown sentinel/full zero is a dash; remaining above known positive full clears both values. Known independent values remain usable when the other is unknown.
- Additive JSON `batteries[].index`, `remaining_wh`, `full_charged_wh`. CSV unchanged. Invalid/transition collections clear capacity fields; stale dashboard readings show dashes. Combined percentage/watts and no aggregate voltage policy are unchanged.
- Case-insensitive device IDs receive monotonic per-session ordinals, retained across detach/reattach and tag changes. New identity means a new ordinal; tags still invalidate collection as before. No slot/display/base inference or cross-run persistence.
- Final metrics row: live monotonic Program elapsed (days + HH:MM:SS), unaffected by reset or pause; stats duration follows the displayed statistics since launch/reset, frozen during pause. Wh excludes gaps and is battery flow, not wall consumption. Compact layouts omit the optional Energy row and two separators to preserve chart height; long capacity lines truncate with an ellipsis.
- Synthetic collector/JSON/actual production TestBackend coverage and inspectable synthetic previews; no new real-hardware validation claim. No health/cycle feature. Windows x64 binaries are unsigned; local security policies still apply.

# Version 0.1.1 — checkpoint

**Experimental multi-battery aggregation — user-confirmed working on a Surface Book with v0.1.1 (2026-10-21).**

- Combined signed native battery flow with explicit Mixed state and per-battery diagnostics.
- Validated membership/device tags, bounded retries and unavailable transition samples; no partial totals or native/WMI mixing.
- Capacity-weighted percentage when metadata is complete; multi-battery voltage/current and mixed ETA remain unavailable.
- Topology epochs reset estimation and separate chart/energy intervals while preserving historical totals. Energy is net flow, not gross per-battery throughput.
- Additive JSON metadata; unchanged CSV columns with new Mixed state. Review device identifiers before sharing exports.
- Default five-second polling, frozen display behavior and desktop meter selection are preserved.
