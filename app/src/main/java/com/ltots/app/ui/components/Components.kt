package com.ltots.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.LocalThemeConfig
import com.ltots.app.ui.theme.toColor
import kotlin.math.PI
import kotlin.math.sin

/** The LToTS logo: a sine wave over the theme gradient. */
@Composable
fun Logo(size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val cfg = LocalThemeConfig.current
    val a = cfg.accent.toColor()
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension * 0.28f
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFF6A3DFF), a, Color(0xFF00C8FF)), Offset.Zero, Offset(this.size.width, this.size.height)),
            cornerRadius = CornerRadius(r, r),
        )
        val path = Path()
        val w = this.size.width; val h = this.size.height
        val x0 = w * 0.2f; val x1 = w * 0.8f; val amp = h * 0.14f
        for (i in 0..48) {
            val x = x0 + (x1 - x0) * i / 48f
            val y = h / 2 - amp * sin(2 * PI * 1.5 * i / 48.0).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color.White, style = Stroke(width = w * 0.065f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    Text(
        text.uppercase(), modifier.padding(start = 4.dp, bottom = 8.dp, top = 4.dp),
        style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = pal.textFaint,
    )
}

/** Labelled slider row with a live value readout. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    val pal = LocalPalette.current
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = pal.textDim, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(valueText, color = pal.text, fontWeight = FontWeight.SemiBold, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = pal.accent,
                activeTrackColor = pal.accent,
                inactiveTrackColor = pal.text.copy(alpha = 0.16f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, sub: String? = null, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = pal.text, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            if (sub != null) Text(sub, color = pal.textFaint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = pal.accent, checkedThumbColor = pal.onAccent,
                uncheckedTrackColor = pal.text.copy(alpha = 0.12f), uncheckedThumbColor = pal.text.copy(alpha = 0.7f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** Small pill-shaped choice chip. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) pal.accent else pal.text.copy(alpha = 0.08f))
            .border(1.dp, if (selected) Color.Transparent else pal.text.copy(alpha = 0.12f), shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text, color = if (selected) pal.onAccent else pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
        )
    }
}

/** A row of evenly spaced pills for picking one value. */
@Composable
fun <T> PillRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) -> Pill(label, v == selected, { onSelect(v) }) }
    }
}

/** A coloured dot swatch. */
@Composable
fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit, size: Dp = 34.dp) {
    val pal = LocalPalette.current
    Box(
        Modifier.size(size).clip(CircleShape).background(color)
            .border(if (selected) 3.dp else 1.dp, if (selected) pal.text else pal.text.copy(alpha = 0.2f), CircleShape)
            .clickable(onClick = onClick),
    )
}

fun formatFreq(hz: Float): String {
    fun strip(s: String) = if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    return when {
        hz >= 10000f -> strip("%.1f".format(java.util.Locale.ROOT, hz / 1000f)) + "k"
        hz >= 1000f -> strip("%.2f".format(java.util.Locale.ROOT, hz / 1000f)) + "k"
        hz >= 100f -> "%.0f".format(java.util.Locale.ROOT, hz)
        else -> strip("%.1f".format(java.util.Locale.ROOT, hz))
    }
}

fun formatDb(db: Float): String = (if (db > 0.05f) "+" else "") + "%.1f".format(java.util.Locale.ROOT, db) + " dB"

fun formatTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
