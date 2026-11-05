package io.github.anevilburrito.wpm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.anevilburrito.wpm.model.AppTheme
import io.github.anevilburrito.wpm.model.CurrentPolarityOverride
import io.github.anevilburrito.wpm.model.CurrentUnitOverride
import io.github.anevilburrito.wpm.model.MonitorSettings
import io.github.anevilburrito.wpm.model.MonitoringMode
import io.github.anevilburrito.wpm.model.NotificationUpdateRate

/** Normal user setting, independent of the sampling interval and developer mode. */
internal fun notificationUpdateRateLabel(rate: NotificationUpdateRate): String = when (rate) {
    NotificationUpdateRate.INSTANT -> "Instant"
    NotificationUpdateRate.FIFTEEN_SECONDS -> "15s"
    NotificationUpdateRate.SIXTY_SECONDS -> "60s"
}

/** Chip label for a current scale choice. */
internal fun currentUnitLabel(unit: CurrentUnitOverride): String = when (unit) {
    CurrentUnitOverride.AUTO -> "Auto"
    CurrentUnitOverride.MICROAMPS -> "\u00b5A"
    CurrentUnitOverride.MILLIAMPS -> "mA"
}

/** Chip label for a polarity choice. */
internal fun currentPolarityLabel(polarity: CurrentPolarityOverride): String = when (polarity) {
    CurrentPolarityOverride.AUTO -> "Auto"
    CurrentPolarityOverride.STANDARD -> "Standard"
    CurrentPolarityOverride.INVERTED -> "Reversed"
}

/**
 * One plain sentence for the manual current-scale override.
 *
 * The symptom is 1000x-too-small power, not a unit lecture: a user who sees
 * `1.26 mW` instead of `1.26 W` needs to know Auto is the default and when to
 * change it, nothing about which platform property was mis-scaled.
 */
private const val CURRENT_SCALE_DESCRIPTION =
    "Some phones report the wrong current scale, which makes power look 1000x too small. " +
        "Auto uses the scale the app learned while recording. Leave this on Auto unless the " +
        "reading is clearly wrong."

/** Scales are advanced corrections; a manual choice always wins over Auto. */
private const val CURRENT_POLARITY_DESCRIPTION =
    "Some phones report charging as discharging. Auto uses the polarity the app learned " +
        "while recording. Choose Standard or Reversed only if the direction is clearly wrong."

/**
 * Why the sampling interval and the recording mode are two different settings.
 *
 * The interval is the reading cadence and stays a normal setting. The mode decides
 * whether Android may sleep between readings, which is why the same interval can
 * still produce gaps in low impact and none in high resolution. Developer mode is
 * neither: it only reveals diagnostics and the manual measurement corrections, so
 * this note says so instead of leaving the reader to guess which section controls
 * what.
 */
fun samplingIntervalNote(settings: MonitorSettings): String = buildString {
    append(intervalDescription(settings.intervalSeconds))
    append(' ')
    append(
        when (settings.mode) {
            MonitoringMode.LOW_IMPACT ->
                "Low impact lets the phone sleep between readings, so those delays are the " +
                    "normal case and the gaps are marked in history."
            MonitoringMode.HIGH_RESOLUTION ->
                "High resolution keeps the processor awake, so those delays do not apply and " +
                    "the interval holds with the screen off."
        },
    )
    append(" The interval is a normal setting; developer mode only adds diagnostics and manual corrections.")
}

/** Matches the range the settings model accepts; the field never clamps a value. */
const val DESIGN_CAPACITY_MIN_MAH = 100.0
const val DESIGN_CAPACITY_MAX_MAH = 30_000.0

/**
 * Result of reading the design-capacity field.
 *
 * [value] is the capacity to save, or `null` when nothing should be saved: an
 * empty field asks to clear it, and [invalid] says the text cannot be used as-is.
 * Out-of-range input is never rounded into the accepted range.
 */
data class DesignCapacityInput(val value: Double?, val invalid: Boolean)

/**
 * Parses a user-typed design capacity in mAh.
 *
 * Grouping spaces and commas are ignored because the app's own formatting writes
 * `4,500 mAh`; the comma is therefore not treated as a decimal separator. Any
 * value outside [DESIGN_CAPACITY_MIN_MAH]..[DESIGN_CAPACITY_MAX_MAH] is reported
 * as invalid instead of being clamped, and an empty field means "unset".
 */
fun parseDesignCapacityMilliampHours(input: String): DesignCapacityInput {
    val cleaned = input.trim().replace(" ", "").replace(",", "")
    if (cleaned.isEmpty()) return DesignCapacityInput(value = null, invalid = false)
    val value = cleaned.toDoubleOrNull()
    if (value == null || !value.isFinite()) return DesignCapacityInput(value = null, invalid = true)
    if (value < DESIGN_CAPACITY_MIN_MAH || value > DESIGN_CAPACITY_MAX_MAH) {
        return DesignCapacityInput(value = null, invalid = true)
    }
    return DesignCapacityInput(value = value, invalid = false)
}

/** Plain editable text for a saved capacity: no grouping separators to retype. */
internal fun designCapacityDraft(value: Double): String =
    if (value.isFinite() && value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** Monitoring settings: interval, policy, palette, absolute scale. */
@Composable
internal fun SettingsDialog(
    settings: MonitorSettings,
    onDismiss: () -> Unit,
    onSettingsChange: (MonitorSettings) -> Unit,
    onRequestHighResolution: () -> Unit,
) {
    val colors = LocalWpmColors.current
    Dialog(onDismissRequest = onDismiss) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val maxDialogHeight = maxHeight.coerceAtMost(720.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxDialogHeight),
                shape = RoundedCornerShape(22.dp),
                color = colors.background,
                contentColor = colors.text,
                border = BorderStroke(1.dp, colors.rule),
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "RECORDING OPTIONS",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = colors.muted,
                        )
                        Text(
                            text = "Monitoring settings",
                            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 23.sp),
                            color = colors.text,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        WpmIconView(WpmIcon.CLOSE, Modifier.size(22.dp), tint = colors.muted)
                    }
                }
                Text(
                    text = "Recording continues when you close this screen.",
                    style = bodyText(),
                    color = colors.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                WpmToggleRow(
                    title = "Absolute scale",
                    description = "Show power and current as positive values. Turn off for negative discharge and positive charge.",
                    checked = settings.absolute,
                    onCheckedChange = { enabled ->
                        onSettingsChange(settings.copy(absolute = enabled))
                    },
                    modifier = Modifier.padding(top = 10.dp),
                )
                SettingsLabel("Recording mode")
                val highResolution = settings.mode == MonitoringMode.HIGH_RESOLUTION
                ModeOption(
                    selected = !highResolution,
                    title = "Low impact",
                    badge = "default",
                    description = "Let your phone sleep between readings. Missing intervals are marked in history.",
                    onClick = {
                        if (highResolution) {
                            onSettingsChange(settings.copy(mode = MonitoringMode.LOW_IMPACT))
                        }
                    },
                )
                ModeOption(
                    selected = highResolution,
                    title = "High-resolution session",
                    badge = null,
                    description = "Keep the processor awake with the screen off. Uses more battery and may increase the idle drain being measured.",
                    onClick = {
                        if (!highResolution) onRequestHighResolution()
                    },
                )
                SettingsLabel("Sampling interval")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // One source of truth for the choices and the default copy.
                    MonitorSettings.ALLOWED_INTERVALS.forEach { seconds ->
                        WpmChip(
                            text = "${seconds}s",
                            selected = settings.intervalSeconds == seconds,
                            onClick = {
                                if (settings.intervalSeconds != seconds) {
                                    onSettingsChange(settings.copy(intervalSeconds = seconds))
                                }
                            },
                            minWidth = 56.dp,
                        )
                    }
                }
                Text(
                    text = samplingIntervalNote(settings),
                    style = captionText(),
                    color = colors.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                SettingsLabel("Notification update rate")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    NotificationUpdateRate.entries.forEach { rate ->
                        WpmChip(
                            text = notificationUpdateRateLabel(rate),
                            selected = settings.notificationUpdateRate == rate,
                            onClick = {
                                if (settings.notificationUpdateRate != rate) {
                                    onSettingsChange(settings.copy(notificationUpdateRate = rate))
                                }
                            },
                            minWidth = 56.dp,
                        )
                    }
                }
                Text(
                    text = "Instant follows each poll. Timed rates refresh on the next reading " +
                        "after that interval. Faster updates may use more battery.",
                    style = captionText(),
                    color = colors.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                SettingsLabel("Design capacity")
                DesignCapacityField(settings, onSettingsChange)
                SettingsLabel("Palette")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ThemeChip("Storm", AppTheme.STORM, settings, onSettingsChange)
                    ThemeChip("Mocha", AppTheme.MOCHA, settings, onSettingsChange)
                    ThemeChip("Latte", AppTheme.LATTE, settings, onSettingsChange)
                    ThemeChip("Mono", AppTheme.MONO, settings, onSettingsChange)
                }
                Text(
                    text = "Tokyo Night Storm is the default. History stays on this device; export a copy to keep or share it.",
                    style = captionText(),
                    color = colors.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                WpmToggleRow(
                    title = "Developer mode",
                    description = "Show raw telemetry, manual measurement corrections and technical diagnostics.",
                    checked = settings.developerMode,
                    onCheckedChange = { enabled ->
                        // Only the visibility flag changes: the saved corrections stay
                        // stored and keep applying when developer mode is turned off.
                        onSettingsChange(settings.copy(developerMode = enabled))
                    },
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (settings.developerMode) {
                    DeveloperSettings(settings, onSettingsChange)
                } else {
                    Text(
                        text = "Off by default. Any manual scale or polarity correction you saved " +
                            "before stays stored and keeps applying.",
                        style = captionText(),
                        color = colors.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                WpmSecondaryButton(
                    text = "Done",
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                )
                }
            }
        }
    }
}

/**
 * Developer-only settings: manual current corrections and the measurement method.
 *
 * These are advanced corrections, so they live behind the opt-in developer toggle.
 * They remain normal persisted settings, and hiding them never clears them.
 */
@Composable
private fun DeveloperSettings(
    settings: MonitorSettings,
    onSettingsChange: (MonitorSettings) -> Unit,
) {
    val colors = LocalWpmColors.current
    Text(
        text = "TECHNICAL",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
        color = colors.muted,
        modifier = Modifier.padding(top = 20.dp),
    )
    Text(
        text = "Only change these if a reading is clearly wrong. The app calibrates " +
            "itself while recording; nothing here needs a full charge or deep discharge. " +
            "The sampling interval and the recording mode are normal settings above; " +
            "this section never changes them.",
        style = captionText(),
        color = colors.muted,
        modifier = Modifier.padding(top = 4.dp),
    )
    SettingsLabel("Current scale")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CurrentUnitOverride.entries.forEach { unit ->
            WpmChip(
                text = currentUnitLabel(unit),
                selected = settings.currentUnit == unit,
                onClick = {
                    if (settings.currentUnit != unit) {
                        onSettingsChange(settings.copy(currentUnit = unit))
                    }
                },
                minWidth = 56.dp,
            )
        }
    }
    Text(
        text = CURRENT_SCALE_DESCRIPTION,
        style = captionText(),
        color = colors.muted,
        modifier = Modifier.padding(top = 6.dp),
    )
    SettingsLabel("Current polarity")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CurrentPolarityOverride.entries.forEach { polarity ->
            WpmChip(
                text = currentPolarityLabel(polarity),
                selected = settings.currentPolarity == polarity,
                onClick = {
                    if (settings.currentPolarity != polarity) {
                        onSettingsChange(settings.copy(currentPolarity = polarity))
                    }
                },
                minWidth = 56.dp,
            )
        }
    }
    Text(
        text = CURRENT_POLARITY_DESCRIPTION,
        style = captionText(),
        color = colors.muted,
        modifier = Modifier.padding(top = 6.dp),
    )
    SettingsLabel("Power method")
    Text(
        text = "The main reading is the latest calibrated instant current times the reported " +
            "voltage. The charge-counter average is kept separately for the estimated runtime " +
            "and developer comparison.",
        style = captionText(),
        color = colors.muted,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * Optional, validated design capacity.
 *
 * Nothing is saved while the text is unusable, an out-of-range value is reported
 * instead of quietly clamped, and Clear removes the value. The field is a plain
 * text entry because Android exposes no design capacity to prefill it with.
 */
@Composable
private fun DesignCapacityField(
    settings: MonitorSettings,
    onSettingsChange: (MonitorSettings) -> Unit,
) {
    val colors = LocalWpmColors.current
    val stored = settings.designCapacityMilliampHours
    var text by remember(stored) { mutableStateOf(stored?.let(::designCapacityDraft) ?: "") }
    val parsed = parseDesignCapacityMilliampHours(text)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        singleLine = true,
        isError = parsed.invalid,
        label = { Text("Capacity in mAh") },
        placeholder = { Text("For example 4500") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        textStyle = telemetry(13.sp),
    )
    if (parsed.invalid) {
        Text(
            text = "Enter a number from 100 to 30,000 mAh, or clear the field.",
            style = captionText(),
            color = colors.warning,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WpmPrimaryButton(
            text = "Save capacity",
            onClick = {
                parsed.value?.let { value ->
                    onSettingsChange(settings.copy(designCapacityMilliampHours = value))
                }
            },
            enabled = parsed.value != null && parsed.value != stored,
            modifier = Modifier.weight(1f),
        )
        WpmSecondaryButton(
            text = "Clear",
            onClick = {
                text = ""
                if (stored != null) onSettingsChange(settings.copy(designCapacityMilliampHours = null))
            },
            enabled = stored != null || text.isNotEmpty(),
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        text = "The rated capacity printed on the battery. Used only for estimated health; " +
            "it never changes the reported percentage, current or runtime.",
        style = captionText(),
        color = colors.muted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
        color = LocalWpmColors.current.muted,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun ThemeChip(
    label: String,
    theme: AppTheme,
    settings: MonitorSettings,
    onSettingsChange: (MonitorSettings) -> Unit,
) {
    WpmChip(
        text = label,
        selected = settings.theme == theme,
        onClick = {
            if (settings.theme != theme) onSettingsChange(settings.copy(theme = theme))
        },
        minWidth = 48.dp,
    )
}

@Composable
private fun ModeOption(
    selected: Boolean,
    title: String,
    badge: String?,
    description: String,
    onClick: () -> Unit,
) {
    val colors = LocalWpmColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) colors.surface else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.trace,
                unselectedColor = colors.rule,
            ),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = bodyText(), color = colors.text)
                if (badge != null) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = colors.muted,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Text(
                text = description,
                style = captionText(),
                color = colors.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Explicit confirmation: a held wake lock costs battery and can change the reading. */
@Composable
internal fun HighResolutionDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalWpmColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        titleContentColor = colors.text,
        textContentColor = colors.muted,
        title = {
            Text(
                text = "Keep the processor awake?",
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 21.sp),
            )
        },
        text = {
            Text(
                text = "High resolution keeps the processor awake while recording, so readings " +
                    "keep arriving with the screen off. It uses extra battery and may increase " +
                    "the idle drain being measured. You can stop recording at any time.",
                style = bodyText(),
            )
        },
        confirmButton = {
            WpmPrimaryButton(text = "Use high resolution", onClick = onConfirm)
        },
        dismissButton = {
            WpmSecondaryButton(text = "Cancel", onClick = onDismiss)
        },
    )
}

/** In-app stop confirmation; the notification action intentionally stops directly. */
@Composable
internal fun StopRecordingDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalWpmColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        titleContentColor = colors.text,
        textContentColor = colors.muted,
        title = {
            Text(
                text = "Stop recording?",
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 21.sp),
            )
        },
        text = {
            Text(
                text = "The background session ends. Existing history stays available.",
                style = bodyText(),
            )
        },
        confirmButton = {
            WpmPrimaryButton(text = "Stop recording", onClick = onConfirm)
        },
        dismissButton = {
            WpmSecondaryButton(text = "Cancel", onClick = onDismiss)
        },
    )
}

/**
 * Confirmation for discarding the saved automatic current calibration.
 *
 * The reset is intentionally explicit: it also throws away evidence the calibrator
 * has gathered but not published yet, which is how a user restarts calibration
 * without waiting for the current evidence to finish. Manual corrections in
 * Settings are a separate mechanism and are not touched.
 */
@Composable
internal fun ResetCalibrationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalWpmColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        titleContentColor = colors.text,
        textContentColor = colors.muted,
        title = {
            Text(
                text = "Reset calibration?",
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 21.sp),
            )
        },
        text = {
            Text(
                text = "Removes the saved automatic calibration and any evidence gathered " +
                    "so far. Recording rebuilds it; manual corrections in settings are separate. " +
                    "Recorded sessions and capacity history are not affected.",
                style = bodyText(),
            )
        },
        confirmButton = {
            WpmPrimaryButton(text = "Reset", onClick = onConfirm)
        },
        dismissButton = {
            WpmSecondaryButton(text = "Cancel", onClick = onDismiss)
        },
    )
}
