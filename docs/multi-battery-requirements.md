# Multi-battery support — next-release requirements

Status: implemented with synthetic coverage and user-confirmed operation on a Surface Book (v0.1.1, 2026-10-21). Target: Windows detachable systems such as Surface Book with batteries in the display and keyboard base.

## Scope

Extend the existing native battery IOCTL backend to provide combined battery flow and safe attachment/detachment handling. Preserve single-battery behavior and the five-second default sampling period. Do not require administrator elevation or a PowerShell refresh process.

First increment: combined watts, per-battery diagnostics, explicit mixed state, topology-change detection, and safe statistics. Combined percentage/remaining-time estimates are conditional on valid capacity metadata; health/cycles remain out of scope.

Combined battery flow is **not total system or wall power**. No device model or physical battery location may be inferred from enumeration order.

## Functional requirements

| ID | Requirement |
|---|---|
| MB-01 | Enumerate eligible system batteries, excluding short-term/UPS interfaces as today. Identify each by a stable device identifier plus its current battery tag. Deduplicate equivalent interfaces; never count a battery twice. |
| MB-02 | Query every participating battery on each sampling cycle. Record collection start/end and individual read outcomes. Battery tags are scoped to devices, not globally unique identities. |
| MB-03 | Sum valid signed rates in watts: positive charge, negative discharge. Validate each reading and its direction flags before aggregation; reject unknown sentinels, conflicts, overflow/non-finite values, or unsupported physical units. |
| MB-04 | A numeric combined total requires a complete, validated collection of the current battery set. Never sum only successful reads, substitute zero for missing readings, or reuse a missing battery's cached rate. |
| MB-05 | Expose per-battery count, identifiers/tags, states, availability, and reason codes in diagnostics. Keep the main dashboard and tray focused on the combined value. Review identifier privacy before sharing reports. |
| MB-06 | Distinguish mixed charging/discharging from idle. Preserve the aggregate signed flow and individual directions; equal opposing rates may yield zero net flow but must remain labelled mixed. Do not infer charging state solely from aggregate sign. |
| MB-07 | Aggregate remaining energy only if all participating capacities are known and expressed in compatible absolute units. Relative-capacity batteries must not be treated as mWh. A capacity failure may make energy/ETA unavailable independently of watts when valid physical rate units are established. |
| MB-08 | Derive combined percentage as `100 × sum(remaining capacity) / sum(full-charge capacity)` only for a complete compatible set with valid positive full-charge capacities. Never average individual percentages or mix a per-battery percentage with aggregate energy. Do not silently clamp invalid capacity metadata into a plausible result. |
| MB-09 | Do not sum or average voltage, or divide combined watts by an arbitrary battery's voltage. For multiple batteries, combined volts/amps display unavailable; retain valid per-battery values in diagnostics. |
| MB-10 | Define topology/availability metadata in the sample model and structured exports. Add a mixed state without changing existing state meanings. Preserve signed export values and existing CSV columns; document additive JSON fields/new state values and resulting consumer compatibility considerations. |
| MB-11 | Never combine native and WMI readings for the same collection. Do not use a single-battery fallback to mask an incomplete multi-battery collection. |

## Transition and consistency requirements

Sequential IOCTL calls are not an atomic snapshot. Software can detect inconsistencies, but must not promise a perfectly simultaneous hardware sample.

| ID | Requirement |
|---|---|
| MT-01 | Revalidate device membership and tags around collection. A changed set/tag, device removal, or failed member read invalidates that collection; publish an unavailable sample with a specific reason. Bound retries so unstable hardware cannot create an unbounded retry loop. |
| MT-02 | After invalidation, rediscover and require a complete collection with matching before/after membership and tags before accepting the new set. A clean transition detected between cycles must still mark the first new-set sample as a discontinuity. |
| MT-03 | Handle display/base detach and reattach, charger unplug/replug, suspend/resume, device disappearance, tag replacement, and repeated rapid transitions. Enumeration order changes alone must not count as a topology change. |
| MT-04 | For charger changes, validate direction/AC observations across the collection. If observations conflict or change mid-collection, report a transition rather than pretending a uniform state. Different batteries may legitimately charge/discharge simultaneously; stable mixed operation is not automatically an error. Between complete, internally consistent collections with unchanged membership/tags, compare combined AC/direction flags rather than individual pack flags: idle/active handoffs must preserve valid flow, chart continuity and estimators when the combined operating state is unchanged. |
| MT-05 | A battery-set/tag change breaks the chart connection and energy integration interval. Preserve accumulated session totals, but do not integrate across the discontinuity. Existing missing-data and excessive-time-gap rules continue to apply. |
| MT-06 | Do not integrate across invalidated collections. Define energy scoping for mixed operation: combined totals represent net battery flow, not gross per-battery charging/discharging throughput. Keep system-power energy separate. |
| MT-07 | Reset percentage-slope and ETA estimator histories when battery membership/tag changes, so added capacity cannot appear as rapid charging and removed capacity cannot appear as rapid drain. Require fresh qualifying data before showing estimates. |
| MT-08 | Invalidate direction-dependent estimators on charger/direction transitions and resume only after their prerequisites are met. Do not carry a discharge ETA into charging/mixed state. Leave mixed-state ETA unavailable unless a separately specified, tested model is adopted. |
| MT-09 | Old window samples may remain visible, separated by gaps, but estimators must not regress across topology epochs. Clearly distinguish historical statistics from the current combined battery set. Reset remains an explicit user action. |
| MT-10 | Tray and dashboard must promptly show unavailable/transition status, not the previous complete total as a live value. Existing stale-data timers continue to apply. A transition is not an application failure and must not terminate logging or the UI. |
| MT-11 | Close device/enumeration resources on success, failure, removal and retry. Preserve single-thread ownership and standard-user operation; document any remaining driver-call cancellation limitation. |

## Synthetic release acceptance

Automated validation uses synthetic tests. On 2026-10-21, a user confirmed that v0.1.1 multi-battery support works on a Surface Book. This is a real-device confirmation, not a claim that every model, firmware version, or scenario below has been individually verified.

Use an injectable discovery/query backend, deterministic clocks, and scripted responses. Unit tests must not depend on the developer machine's batteries or firmware. Existing single-battery and desktop-unavailable regressions must continue to pass.

| Test group | Required cases and assertions |
|---|---|
| Aggregation | Two discharging batteries; two charging; one idle; opposite directions; exact cancellation; unknown rate; contradictory flags; relative/absolute units; missing capacity; invalid full-charge capacity. Assert signed totals, mixed state, and independent field availability. |
| Identity | Reordered interfaces; duplicate exposure; same numeric tag on different devices; changed tag on same device; removal and replacement. Assert no double counting and correct topology epochs. |
| Collection races | Remove a battery before/during/after member reads; attach during collection; query failure after another successful read; failed final revalidation. Assert no partial or cached total and bounded retries. |
| Recovery | Confirm transition sample, stable new-set recovery, repeated failures, rapid attach/detach, and zero batteries. Assert diagnostics, resource cleanup, and no process failure. |
| Charger/state | AC connect/disconnect between cycles and during collection; contradictory AC reports; stable mixed transfer. Assert conservative transition handling without treating all mixed operation as corruption. Exercise repeated idle/active pack handoffs and load transfers between cycles with unchanged combined direction; require uninterrupted flow, capacities, energy integration and discharge estimates. The same handoffs during a collection must still invalidate it. |
| Statistics | No trapezoid across membership/tag/invalid-data boundaries; no false percentage slope from capacity jumps; new estimates require fresh qualifying data; correct net energy labels/scoping. Preserve prior accumulated totals. |
| Rendering/export | Combined value, count/mixed labels, unavailable V/A, transition/stale tray handling, signed JSON/CSV, reason codes and additive metadata. Test narrow/large layouts using synthetic terminal buffers. |
| Regressions | Single-battery discharge/charge/idle, unknown units, existing gap/stale rules, five-second default, no-battery unsupported desktop, pause behavior and reset. |

Release gate: all targeted synthetic tests, existing regression suites, formatting, Clippy and release packaging pass. Release notes must say: **“Experimental multi-battery aggregation; user-confirmed working on a Surface Book with v0.1.1.”** Do not state that all Surface Book models are supported or tested.

## Live validation and regression checklist

User-confirmed operation is recorded above. Use this checklist for additional regression testing; individual results have not been supplied:

1. Run standalone with all reference polling scripts stopped; confirm two independently identified batteries and combined flow.
2. Capture attached and detached operation on battery and AC, including base/display batteries at substantially different charge levels.
3. Detach/reattach repeatedly while sampling; vary charger connection before, during and after those transitions where Windows permits detachment. Do not bypass Windows' safe-detach controls.
4. Check mixed states where reproducible, unknown/transition gaps, tray behavior, suspend/resume, estimator reset and energy discontinuities.
5. Compare serialized per-battery and combined readings and inspect diagnostics for partial totals, old tags or reused readings. Record hardware/firmware versions and acceptance results.

Record model/firmware details and scenario-specific results as they become available. User confirmation does not establish coverage of every Surface Book model or all transition edge cases.

## Implementation / release status

The production Windows backend is consumed by the injectable `Collector` orchestrator. Three complete fresh passes validate membership/tags and per-device AC/direction; a failing pass is published without a same-tick retry to a numeric value. All native resources use RAII. Device retries are limited to two attempts; synchronous IOCTL driver calls have no cancellation guarantee. Sequential observations cannot detect a transition that happens and fully reverses between observations.

Synthetic acceptance includes aggregation/units/capacity matrices, reordered and duplicate IDs, device-scoped tag replacement, attachment/removal at each pass, failed final discovery, charger/topology changes, invalidation/recovery, epoch-aware statistics/estimation, JSON/CSV, narrow/large terminal and tray tests. Surface Book multi-battery operation is user-confirmed; the report does not enumerate firmware, suspend/resume, or OS handle scenarios tested. Excessive sampling gaps conservatively reset estimation/integration after resume.

**Experimental multi-battery aggregation; user-confirmed working on a Surface Book with v0.1.1.**

## v0.1.2 capacity and elapsed display

Expose structured per-member `index`, `remaining_wh` and `full_charged_wh`, never parse diagnostics text. Display remaining / full-charge absolute Wh with one decimal, individually unknown values as dashes; relative units and inconsistent remaining > known positive full never produce plausible capacities. Invalid collections clear both structured capacity fields. Stale dashboard values show dashes. Preserve combined power, weighted percentage and voltage semantics.

Allocate device-ID ordinals once per collector session (case-insensitive identity), retain across detach/reattach and tag changes; do not renumber surviving batteries. New IDs receive new ordinals; no physical-location inference or cross-run persistence. Tags retain collection invalidation semantics.

Capacity is a dedicated metrics row, truncated with an explicit ellipsis at narrow widths. The final metrics row shows live monotonic Program elapsed and displayed stats duration since launch/reset; runtime continues during pause and is independent of stats reset. Stats duration freezes with the snapshot. Energy excludes invalid/gap intervals and is not wall consumption. Preserve chart height at compact supported sizes by dropping optional Energy and two separator rows. Synthetic previews use the actual production draw buffer, not fabricated hardware evidence.