package io.github.anevilburrito.wpm.monitoring

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Narrow process-local running flag mirrored from [MonitoringRepository.state].
 * NOT persisted measurement history: the durable record lives in the repository
 * and its SQLite store, and the service owns the sampling loop.
 */
internal object MonitoringSession {
    private val mutableRunning = MutableStateFlow(false)
    val running = mutableRunning.asStateFlow()

    fun setRunning(running: Boolean) {
        mutableRunning.value = running
    }
}
