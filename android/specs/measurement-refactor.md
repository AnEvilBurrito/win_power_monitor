# Measurement refactor contract

Historical contract for 0.4.0-dev. The 0.5.0-dev average-first display, bounded 1s
startup sampling and developer-mode changes in [average-first-ui.md](average-first-ui.md)
supersede its instant-primary/no-faster-poll/UI ownership requirements below.

Implement current calibration, distinct capacity concepts, and layout-stable user-facing information. Preserve desktop code and existing recording lifecycle, wake-lock policy, sample batching and retention. No release/commit/upload. Existing Android changes are unverified and must be reviewed, not discarded wholesale.

## Shared model (parent-owned MonitorModels.kt)

- `CurrentCalibration(unit: CurrentUnitOverride = AUTO, inverted: Boolean? = null, evidenceWindows: Int = 0)`; `.calibrated` means unit != AUTO and inverted != null.
- `CurrentPolarityOverride { AUTO, STANDARD, INVERTED }`.
- MonitorSettings gains `currentPolarity` and nullable `designCapacityMilliampHours`; retains currentUnit. Manual unit/polarity override automatic calibration independently. Normalized design capacity is finite 100..30000 mAh or null; never clamped.
- BatterySample gains `rawAverageMicroamps: Long?`, `currentCalibrated: Boolean`, `currentPolarityAdjusted: Boolean` (defaults null/false).
- MonitorUiState gains `calibration: CurrentCalibration`. Existing `.capacity` becomes OBSERVED charging-session capacity, not a single-point gauge estimate. CurrentSource legacy enum values retained for historical storage.

## Current pipeline (agent A owns)

Files: monitoring/BatteryTelemetryDecoder.kt, BatteryTelemetryProvider.kt, CurrentEstimator.kt, NEW monitoring/CurrentCalibrator.kt; corresponding monitoring tests. Do NOT edit MonitoringService, repository/store, settings, CSV, model, UI, build files.

- `CurrentCalibrator(initial: CurrentCalibration = CurrentCalibration())` with `update(raw: RawBatteryReading): CurrentCalibration` and `reset(initial: CurrentCalibration = CurrentCalibration())`. Return stable established calibration until explicitly reset. No Android imports.
- Infer unit AND polarity from multiple independent non-overlapping windows of integrated raw current versus charge-counter change. Minimum 3 consistent windows, each >=15s, <=5min, meaningful nonzero counter change. Reject missing/gapped/reset/unstable/conflicting evidence; do not infer scale from one instantaneous-to-minute-average ratio. Do not assume plugging in means charging. Document evidence/tolerance rules and test noise, opposite sign, mA/µA, interruptions and no-evidence behavior. If counter unsupported calibration may remain unconfirmed; manual corrections are available. No claim of equivalence with AccuBattery's private implementation.
- `BatteryTelemetryDecoder.decode(raw, derivedAmps = null, derivedWindowMillis = null, currentUnit = AUTO, currentPolarity = AUTO, calibration = CurrentCalibration())` (keep defaults).
- amps/watts/currentMicroamps/currentSource describe CURRENT_NOW ONLY (INSTANT or NONE). Never silently substitute averaged current for instant. Missing instant stays null even when average/counter exists. Keep derivedAmps/window separate for runtime. Retain raw CURRENT_AVERAGE for exports only, no unproven unit reinterpretation of this property.
- Interpret raw instant with manual settings first, saved calibration second, documented µA/non-inverted fallback last. `currentCalibrated` true only when both unit and polarity have established/manual evidence. Preserve existing protection against obviously inconsistent polarity, but corrected inverted readings should work. No zero fabrication or clamping. Safeguard multiplication/implausible corrected values.
- Update misleading comments asserting counters are immune to OEM errors or universally more trustworthy. CurrentEstimator remains a counter-derived trailing average, NOT instant; inspect gap/zero/reversal behavior.

## Capacity, storage and service integration (agent B owns)

Files: model/CapacityEstimator.kt + model/CapacityEstimatorTest.kt; all monitoring files/tests EXCEPT agent A's above. Do not edit MonitorModels, UI, docs/build.

- Wire CurrentCalibrator into service; initialize from repository.state.value.calibration; compare external reset and reset in-memory calibrator when needed. Persist only established calibration once/when changed, not each poll. `repository.resetCurrentCalibration()` suspend resets published + persisted calibration; `repository.saveCurrentCalibration(value)` suspend saves published + persisted calibration. SettingsStore can provide load/save calibration; preserve all normal settings. No circular locking.
- SettingsStore persists manual polarity and design capacity; design never changes current units, runtime's remaining counter or observed capacity.
- Existing poll + conflated battery-change trigger may stay; no higher cadence/wake-lock expansion. Inspect partial debounce code: genuinely missing individual properties aren't exceptions. Avoid per-poll debug logging by default; provenance belongs in explicit CSV export.
- CapacityEstimate/CapacityAggregate retain their existing field/API shapes for consumers. Rewrite CapacityEstimator as a stateful class: `add(sample): CapacityMeasurement?`, `reset()`; observed capacity from positive charge-counter changes over >=60 percentage points in a monotonic charging window. Reset on recording boundary, discontinuity, missing values, reverse percentage/counter, implausible counter rate. Do not bridge >5min gaps or overlap completed windows. Reject nonsense; do not clamp. Document minimum as accuracy policy, not a mathematical prerequisite. Counter changes are gauge observations, not a laboratory independent measurement.
- `CapacityMath.gaugeFullCapacityMilliampHours(sample: BatterySample?): Double?` returns single-point charge counter / fraction, validated; UI labels it gauge estimate. Do not persist it into observed capacity.
- Keep CapacityMath.runtimeSeconds/chargePercent compatibility; runtime UI uses derivedAmps only (average), no instant substitution. Charging time-to-full remains rough and must be labelled at current rate, not promised completion time.
- Additive DB migration persists new sample provenance fields; invalidate ONLY previous capacity_state aggregate when upgrading because its estimator semantics changed, preserve sessions/history. Schema/version marker must prevent old gauge estimates being presented as observed capacity. Preserve capacity write throttling/end flush/recoverable failure, inspect partial code for correctness.
- CSV append provenance columns, preserve raw values including CURRENT_AVERAGE and calibration flags; distinguish observed-capacity method and manual design capacity/calibration metadata. Document raw names as API-reported (units may be incorrect).
- Tests for revised capacity policy/gauge helper/CSV, settings pure logic if extracted. Build only after parent signals integration ready (avoid concurrent Gradle runs).

## UI (agent C owns)

Files: ui/** and ui tests only. No model/monitoring/docs/build edits.

- Main readout and chart explicitly say Instant power/current (latest hardware reading, not guaranteed hardware refresh). Separate runtime average from instant; runtime uses latest.derivedAmps ONLY. No source-dependent live substitution.
- Settings manual design capacity mAh: optional validated input, clear action, no silent clamp; apply via settings.copy(designCapacityMilliampHours=...). Manual scale Auto/µA/mA and polarity Auto/Standard/Reversed advanced corrections; explain briefly. Calibration section displays saved status, simple measurement-in-progress instruction (record with steady battery use/charging), retry/reset action through ViewModel -> repository.resetCurrentCalibration(). Do not require deep discharge or full charging to calibrate current. Auto calibration occurs while recording; say insufficient evidence when unavailable, never claim success without evidence.
- capacityView adds optional `designCapacityMilliampHours: Double? = null` parameter for compatibility; pass settings value from diagnostics. Show Design capacity (user supplied or Not set), Gauge full-capacity estimate (single-point helper), Observed capacity (state.capacity, count sessions/windows not polls), Estimated health only when observed+design exist, Cycle count, Runtime at recent average rate. No fixed '20% change' copy; observed pending explains a longer charging session improves accuracy (policy >=60 points may be explained in help, not flashing banners). Design capacity must never change reported battery percent/current.
- User-facing diagnostics only useful status + battery + capacity/calibration status. Remove debug property dumps, provenance tokens, plot/window measurements and verbose developer notices. Keep raw evidence in export.
- Flashing: partial edits removed per-sample banner; do not claim actual device root cause proven. Make reading/missing/stale/error transitions layout-stable, no blinking/animation. Separate persistent recording failures from ordinary sample missingness; inspect freshness threshold (avoid toggling at normal cadence). Test what can be tested pure, add UI-test coverage if project supports it. Retain portrait/landscape and 48dp targets. Keep important errors actionable, not hidden.
- Build only after parent signals integration ready. Report any remaining device-only checks.

## Acceptance

Parent reviews subagent code, updates docs/build version, runs testDebugUnitTest + lintDebug + assembleDebug once combined tree is ready. No LSP server for Kotlin assumed. Device checks still required: side-by-side raw current CSV and AccuBattery in stable unplugged/charging conditions; hardware update cadence; inverted/mA calibration; low-impact sleep; warning flicker; real charging-session observed capacity.
