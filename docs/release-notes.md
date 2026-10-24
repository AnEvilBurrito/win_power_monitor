# Next release - planned UI/UX and diagnostics

Implementation checkpoint (not a published release): named theme palettes,
semantic colours, scrollable diagnostics, per-battery design/health display,
and experimental cached analytics are now implemented. The broader dashboard
layout refinement, estimator tuning, manual review, and native platform validation
remain pending. Synthetic UI review: `target/ui-review/themes.html`, generated
by `cargo test --test dashboard theme_review_artifact`.

The selected chart-first A layout is now implemented with neutral watts, small
flow accents, stable trace colour, and telemetry-first diagnostics. Known battery
levels at or below 20% use red and a `low` label; mono retains the label without
colour. Stale/transition values do not produce low-battery warnings.

These items are planned, not implemented release changes. Version and final
scope are not yet assigned.

Implementation sequencing, code ownership, proposed calculation rules, and
verification are in the [implementation plan](ui-diagnostics-implementation-plan.md).

- Improve the UI/UX layout and information hierarchy.
- Improve text colour usage for readability and consistent visual meaning.
- Add selectable themes, including Tokyo Night / Storm, Catppuccin variants,
	and Nord. The final theme set and selection mechanism are to be decided.
- Add the following monitoring metrics to diagnostics in the `?` help overlay:

| Planned metric | Proposed definition | Decisions still needed |
| --- | --- | --- |
| Estimated idle power draw | Statistical mode of power readings through temporal aggregation. | Aggregation method, observation window, and mode estimation / binning method. |
| Estimated time remaining by average consumption | Remaining battery energy divided by average discharge power. | Averaging window, energy source when capacity is unavailable, and handling of charging, mixed flow, and non-positive consumption. |
| Idle percentage | Percentage of valid power readings below average power. | Averaging window, sample-count versus time weighting, and treatment of signed flow. This measures below-average activity, not confirmed system idle time. |
| Battery design capacity | Per-battery manufacturer-reported design energy in Wh where absolute units are available. | Audit platform metadata and unit validity; missing or relative-only energy stays unavailable. |
| Battery health | Per-battery full-charge capacity / design capacity * 100, using compatible units. | Validate metadata and unavailable states; retain valid ratios above 100% without implying a safety diagnosis. |

Before implementation, define how these estimates handle insufficient history,
stale or missing readings, sampling gaps, resets, and battery topology changes.
Keep the proposed average-consumption estimate distinct from the existing ETA.

# Unreleased - experimental macOS battery flow

- Add AppleSmartBattery IORegistry plist acquisition and signed current/voltage
	conversion, reusing the shared battery collector. No menu-bar or system power.
- Percentage uses charge-capacity ratios; remaining/full/design Wh remain unknown.
	Missing or conflicting readings stay unavailable. Integrated session Wh is unchanged.
- Add portable synthetic fixtures, macOS CLI fixtures, native CI and host-architecture
	tarball packaging. Real Intel/Apple Silicon battery validation is still pending.

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
