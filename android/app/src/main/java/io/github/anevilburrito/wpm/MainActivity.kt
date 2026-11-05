package io.github.anevilburrito.wpm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.anevilburrito.wpm.model.AppTheme
import io.github.anevilburrito.wpm.monitoring.MonitoringRepository
import io.github.anevilburrito.wpm.monitoring.MonitoringService
import io.github.anevilburrito.wpm.ui.WpmDashboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Android host only: permission, service intents and user-selected export destination. */
class MainActivity : ComponentActivity() {
    private val repository by lazy { MonitoringRepository.get(applicationContext) }
    private val hostError = MutableStateFlow<String?>(null)

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Permission denial does not prevent foreground recording. It is not a
        // recording failure and must not become a persistent dashboard warning.
        startRecording()
    }

    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val output = contentResolver.openOutputStream(uri, "wt")
                            ?: error("Cannot open the selected file")
                        output.use { repository.writeCsv(it) }
                    }
                    Toast.makeText(this@MainActivity, "CSV exported", Toast.LENGTH_SHORT).show()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    hostError.value = "Export failed: ${failure.message ?: "file unavailable"}"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { repository.load() }
        setContent {
            val state = repository.state.collectAsStateWithLifecycle().value
            val settings = repository.settings.collectAsStateWithLifecycle().value
            LaunchedEffect(settings.theme) {
                // Follow the selected app palette, not just the phone's system
                // theme, so Latte keeps legible status/navigation bar icons.
                val dark = settings.theme != AppTheme.LATTE
                val bars = SystemBarStyle.auto(0, 0) { dark }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            val held = repository.wakeLockHeld.collectAsStateWithLifecycle().value
            val error = hostError.collectAsStateWithLifecycle().value
            WpmDashboard(
                state = state.copy(error = state.error ?: error, wakeLockHeld = held),
                settings = settings,
                onStart = {
                    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else startRecording()
                },
                onStop = {
                    hostError.value = null
                    try {
                        // Normal Stop flushes the pending database tail before stopSelf.
                        startService(Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP))
                    } catch (failure: RuntimeException) {
                        hostError.value = "Could not stop recording: ${failure.message}"
                    }
                },
                onSettingsChange = repository::saveSettings,
                // The calibration is evidence, not a setting: resetting it clears the
                // published and persisted value, and the running sampler adopts the
                // cleared state on its next poll instead of keeping stale evidence.
                onResetCalibration = {
                    lifecycleScope.launch {
                        try {
                            repository.resetCurrentCalibration()
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            hostError.value = "Could not reset calibration: ${failure.message ?: "unavailable"}"
                        }
                    }
                },
                onExport = {
                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    exportDocument.launch("wpm-$stamp.csv")
                },
            )
        }
    }

    private fun startRecording() {
        hostError.value = null
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_START),
            )
        } catch (failure: RuntimeException) {
            hostError.value = "Android could not start recording: ${failure.message ?: "service unavailable"}"
        }
    }
}
