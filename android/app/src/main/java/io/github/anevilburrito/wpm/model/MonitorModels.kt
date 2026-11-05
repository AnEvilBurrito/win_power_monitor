package io.github.anevilburrito.wpm.model

/** Platform-neutral contracts shared by the service, persistence, analytics and UI. */
enum class FlowState { CHARGING, DISCHARGING, IDLE, UNKNOWN }
enum class MonitoringMode { LOW_IMPACT, HIGH_RESOLUTION }

/** Minimum time between numeric notification updates; important transitions bypass it. */
enum class NotificationUpdateRate(val minimumIntervalMillis: Long) {
    /** No extra throttle: update with each sampled reading, without extra polls. */
    INSTANT(0L),
    FIFTEEN_SECONDS(15_000L),
    SIXTY_SECONDS(60_000L),
}
enum class AppTheme { STORM, MOCHA, LATTE, MONO }

/** Which platform value produced the stored current/energy for a sample. */
enum class CurrentSource {
    /** No usable current source, so watts stay unavailable. */
    NONE,

    /** Historical source: a charge-counter average, not an instantaneous reading. */
    CHARGE_COUNTER,

    /** `BATTERY_PROPERTY_CURRENT_AVERAGE` (already a platform average). */
    AVERAGE,

    /** `BATTERY_PROPERTY_CURRENT_NOW`, an instantaneous hardware reading. */
    INSTANT,

    /** CURRENT_NOW interpreted by the five-poll heuristic, not counter-confirmed. */
    INSTANT_ESTIMATED,
}

/** Manual override for OEMs that report battery current in the wrong scale. */
enum class CurrentUnitOverride {
    /** Automatic startup inference, superseded by counter-confirmed interpretation. */
    AUTO,
    MICROAMPS,
    MILLIAMPS,
}

enum class CurrentPolarityOverride { AUTO, STANDARD, INVERTED }

/** Unit/sign pair; zero evidence windows can be a tentative startup interpretation. */
data class CurrentCalibration(
    val unit: CurrentUnitOverride = CurrentUnitOverride.AUTO,
    val inverted: Boolean? = null,
    val evidenceWindows: Int = 0,
) {
    /** Complete pair, not an accuracy/confidence claim; automatic saving needs counter evidence. */
    val calibrated: Boolean get() = unit != CurrentUnitOverride.AUTO && inverted != null
}

data class MonitorSettings(
    /** Polling cadence while awake; querying does not force a hardware refresh. */
    val intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
    val mode: MonitoringMode = MonitoringMode.LOW_IMPACT,
    val theme: AppTheme = AppTheme.STORM,
    val absolute: Boolean = true,
    /** Explicit opt-in for technical diagnostics and measurement overrides. */
    val developerMode: Boolean = false,
    /** Manual corrections take precedence over the saved automatic calibration. */
    val currentUnit: CurrentUnitOverride = CurrentUnitOverride.AUTO,
    val currentPolarity: CurrentPolarityOverride = CurrentPolarityOverride.AUTO,
    /** User-provided rated capacity; never inferred from runtime or telemetry. */
    val designCapacityMilliampHours: Double? = null,
    /** Preserve the previous one-minute throttle until the user changes it. */
    val notificationUpdateRate: NotificationUpdateRate = NotificationUpdateRate.SIXTY_SECONDS,
) {
    fun normalized(): MonitorSettings = copy(
        intervalSeconds = intervalSeconds.takeIf { it in ALLOWED_INTERVALS } ?: DEFAULT_INTERVAL_SECONDS,
        designCapacityMilliampHours = designCapacityMilliampHours?.takeIf {
            it.isFinite() && it in 100.0..30_000.0
        },
    )

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 2
        val ALLOWED_INTERVALS = listOf(1, 2, 5)
    }
}

data class BatterySample(
    val timestampMillis: Long,
    val elapsedRealtimeMillis: Long,
    val watts: Double? = null,
    val volts: Double? = null,
    val amps: Double? = null,
    val percentage: Int? = null,
    val temperatureC: Double? = null,
    val condition: String? = null,
    val plugged: Boolean? = null,
    val flow: FlowState = FlowState.UNKNOWN,
    /** Normalised signed microamps actually used for [amps] and [watts]. */
    val currentMicroamps: Long? = null,
    val voltageMillivolts: Int? = null,
    /** Exactly what `CURRENT_NOW` returned, before any unit normalisation. */
    val rawCurrentMicroamps: Long? = null,
    /** Unmodified platform-average property, retained for comparison exports only. */
    val rawAverageMicroamps: Long? = null,
    /** Whether the instant reading used an established or fully manual interpretation. */
    val currentCalibrated: Boolean = false,
    val currentPolarityAdjusted: Boolean = false,
    /** `CHARGE_COUNTER` in microamp-hours, when the platform exposes it. */
    val chargeCounterMicroampHours: Long? = null,
    /** Battery cycle count reported by the platform (API 34+), else null. */
    val cycleCount: Int? = null,
    /** Current derived from charge-counter deltas over [derivedWindowMillis]. */
    val derivedAmps: Double? = null,
    val derivedWindowMillis: Long? = null,
    /** True when the reported property had to be rescaled (mA reported as µA). */
    val currentUnitAdjusted: Boolean = false,
    val currentSource: CurrentSource = CurrentSource.NONE,
    val gapBefore: Boolean = false,
    val reason: String? = null,
    /** Live-only startup feedback; not measurement evidence or durable history. */
    val startupPollCount: Int = 0,
    val startupCheckComplete: Boolean = false,
)

data class SessionSummary(
    val id: Long = 0,
    val startedAtMillis: Long = 0,
    val endedAtMillis: Long? = null,
    val elapsedSeconds: Double = 0.0,
    val observedSeconds: Double = 0.0,
    val outWh: Double = 0.0,
    val inWh: Double = 0.0,
    val signedAverageWatts: Double? = null,
    val magnitudeAverageWatts: Double? = null,
    val minimumSignedWatts: Double? = null,
    val maximumSignedWatts: Double? = null,
    val minimumMagnitudeWatts: Double? = null,
    val maximumMagnitudeWatts: Double? = null,
    val sampleCount: Int = 0,
    val gapCount: Int = 0,
)

data class MonitorUiState(
    val running: Boolean = false,
    val samples: List<BatterySample> = emptyList(),
    val summary: SessionSummary = SessionSummary(),
    val error: String? = null,
    val historyTruncated: Boolean = false,
    val loaded: Boolean = false,
    val wakeLockHeld: Boolean = false,
    /** Observed charging-session capacity, separate from a single-point gauge estimate. */
    val capacity: CapacityEstimate = CapacityEstimate(),
    val calibration: CurrentCalibration = CurrentCalibration(),
    /** Explicit resets advance this even when the saved calibration is already empty. */
    val calibrationRevision: Long = 0L,
) {
    val latest: BatterySample? get() = samples.lastOrNull()
}
