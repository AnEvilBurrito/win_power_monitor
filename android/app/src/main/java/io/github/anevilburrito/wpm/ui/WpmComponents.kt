package io.github.anevilburrito.wpm.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Selected/unselected control, always at least 48dp tall.
 *
 * The selected underline is drawn with [drawBehind] instead of a child `Box` with
 * `fillMaxWidth()`: a wrap-content Box measures its children with the *incoming*
 * maximum width, so that child made the whole chip as wide as its parent row and
 * pushed the remaining chips off-screen (the reported full-width "5m" chip). Drawing
 * keeps the chip intrinsic while still marking the selection.
 */
@Composable
fun WpmChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minWidth: Dp = 48.dp,
) {
    val colors = LocalWpmColors.current
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .widthIn(min = minWidth)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.surface else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .then(
                if (selected) {
                    Modifier.drawBehind {
                        val stroke = 2.dp.toPx()
                        drawLine(
                            color = colors.trace,
                            start = Offset(0f, size.height - stroke / 2f),
                            end = Offset(size.width, size.height - stroke / 2f),
                            strokeWidth = stroke,
                        )
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = when {
                !enabled -> colors.rule
                selected -> colors.text
                else -> colors.muted
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            textAlign = TextAlign.Center,
            // A chip is intrinsic width: its label never wraps or stacks per character.
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun WpmPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalWpmColors.current
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(9.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.trace,
            contentColor = colors.background,
            disabledContainerColor = colors.surface,
            disabledContentColor = colors.rule,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp), textAlign = TextAlign.Center)
    }
}

@Composable
fun WpmSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: WpmIcon? = null,
) {
    val colors = LocalWpmColors.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(9.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = colors.text,
            disabledContentColor = colors.rule,
        ),
    ) {
        if (icon != null) {
            WpmIconView(icon, Modifier.size(18.dp), tint = if (enabled) colors.text else colors.rule)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp), textAlign = TextAlign.Center)
    }
}

/**
 * Label/value line used by diagnostics, session detail and settings.
 * [valueMinLines] reserves room for changing multi-line values without clipping
 * longer text at large accessibility font scales.
 */
@Composable
fun WpmDataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueMinLines: Int = 1,
) {
    val colors = LocalWpmColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = captionText(),
            color = colors.muted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = telemetry(11.sp),
            color = colors.text,
            modifier = Modifier.weight(1.15f),
            textAlign = TextAlign.End,
            minLines = valueMinLines,
        )
    }
    HorizontalDivider(color = colors.rule.copy(alpha = 0.45f), thickness = 1.dp)
}

@Composable
fun WpmSectionHeading(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalWpmColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Static recording indicator; deliberately not animated or blinking. */
@Composable
fun WpmStatusDot(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(7.dp)) { drawCircle(color) }
}

/** Compact 34x18dp switch track used by the chart scale control. */
@Composable
fun WpmMiniSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalWpmColors.current
    Canvas(modifier.size(width = 34.dp, height = 18.dp)) {
        val radius = size.height / 2f
        drawRoundRect(
            color = if (checked) colors.trace else colors.rule,
            cornerRadius = CornerRadius(radius, radius),
            size = size,
        )
        val thumbRadius = (radius - 2.dp.toPx()).coerceAtLeast(1f)
        val centerX = if (checked) size.width - radius else radius
        drawCircle(
            color = colors.background,
            radius = thumbRadius,
            center = Offset(centerX, size.height / 2f),
        )
    }
}

@Composable
fun WpmModeNote(policy: PolicyView, modifier: Modifier = Modifier) {
    val colors = LocalWpmColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text("\u25cc", style = telemetry(16.sp), color = colors.muted)
        Column(Modifier.weight(1f)) {
            Text(policy.title, style = bodyText(), color = colors.text)
            Text(policy.detail, style = captionText(), color = colors.muted)
        }
    }
}

@Composable
fun WpmToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalWpmColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(9.dp))
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = bodyText(), color = if (enabled) colors.text else colors.rule)
            Text(description, style = captionText(), color = colors.muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = colors.trace,
                checkedBorderColor = colors.trace,
                uncheckedThumbColor = colors.muted,
                uncheckedTrackColor = colors.surface,
                uncheckedBorderColor = colors.rule,
            ),
        )
    }
}

/** Bordered selectable card used for recording-mode choices. */
@Composable
fun WpmOptionCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalWpmColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) colors.surface else Color.Transparent)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(12.dp),
    ) {
        content()
    }
}
