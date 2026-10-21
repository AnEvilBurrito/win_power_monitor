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
