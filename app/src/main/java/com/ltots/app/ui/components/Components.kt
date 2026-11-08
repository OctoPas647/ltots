package com.ltots.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import kotlinx.coroutines.launch
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
    /** False shows the slider grayed out: it's there, but can't be changed right now. */
    enabled: Boolean = true,
    /**
     * Shows a small reset button next to the value (faded while the value is already at its
     * default, [isDefault]); tapping it calls this.
     */
    onReset: (() -> Unit)? = null,
    isDefault: Boolean = false,
    /** Called when a drag ends (so a whole drag can count as one step, e.g. for Undo). */
    onChangeFinished: (() -> Unit)? = null,
    /** Extra controls at the end of the label row (after the value). */
    trailing: (@Composable () -> Unit)? = null,
) {
    val pal = LocalPalette.current
    Column(modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = pal.textDim, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(valueText, color = pal.text, fontWeight = FontWeight.SemiBold, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            if (onReset != null) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(pal.text.copy(alpha = if (isDefault) 0.04f else 0.10f))
                        .clickable(enabled = enabled && !isDefault, onClick = onReset)
                        .alpha(if (isDefault) 0.4f else 1f),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Rounded.RestartAlt, "Reset $label",
                        tint = pal.text, modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        val throttleMs = LocalSliderThrottle.current
        val throttled = rememberThrottled(throttleMs, onChange)
        LSlider(
            value = (if (throttleMs > 0) throttled.drag ?: value else value).coerceIn(range.start, range.endInclusive),
            onValueChange = { if (throttleMs > 0) throttled.change(it) else onChange(it) },
            onValueChangeFinished = { if (throttleMs > 0) throttled.finish(); onChangeFinished?.invoke() },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = pal.accent,
                activeTrackColor = pal.accent,
                inactiveTrackColor = pal.text.copy(alpha = 0.16f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
                // Grayed out: the whole row is already faded, so keep the same colours underneath.
                disabledThumbColor = pal.textDim,
                disabledActiveTrackColor = pal.textDim,
                disabledInactiveTrackColor = pal.text.copy(alpha = 0.16f),
                disabledActiveTickColor = Color.Transparent,
                disabledInactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/**
 * How often (ms) a held slider tells the app its new value; 0: every move. On the Look page a new
 * value redraws the whole app, so there the thumb follows the finger by itself and the look
 * catches up a few times a second, instead of every frame of the drag waiting for that redraw.
 */
val LocalSliderThrottle = androidx.compose.runtime.compositionLocalOf { 0L }

@Stable
internal class ThrottledSlider(private val scope: kotlinx.coroutines.CoroutineScope, private val everyMs: Long) {
    /** Where the thumb is while it's held. */
    var drag by mutableStateOf<Float?>(null)
    var send: (Float) -> Unit = {}
    private var job: kotlinx.coroutines.Job? = null
    private var lastAt = 0L
    private var pending: Float? = null

    fun change(v: Float) {
        drag = v
        val now = android.os.SystemClock.uptimeMillis()
        val wait = everyMs - (now - lastAt)
        if (wait <= 0) {
            job?.cancel(); job = null; pending = null; lastAt = now
            send(v)
        } else {
            pending = v
            if (job == null) job = scope.launch {
                kotlinx.coroutines.delay(wait)
                job = null
                pending?.let { pending = null; lastAt = android.os.SystemClock.uptimeMillis(); send(it) }
            }
        }
    }

    fun finish() {
        job?.cancel(); job = null
        pending?.let { pending = null; send(it) }
        drag = null
    }
}

@Composable
internal fun rememberThrottled(everyMs: Long, onChange: (Float) -> Unit): ThrottledSlider {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val t = remember(everyMs) { ThrottledSlider(scope, everyMs) }
    t.send = onChange
    return t
}

/** The app's slider: with Outlines on (Look > Colours) it has an outline, so it shows at any colour. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    colors: androidx.compose.material3.SliderColors = SliderDefaults.colors(),
) {
    if (!LocalThemeConfig.current.sliderOutlines) {
        Slider(
            value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
            onValueChangeFinished = onValueChangeFinished, valueRange = valueRange, steps = steps, colors = colors,
        )
        return
    }
    val pal = LocalPalette.current
    val edge = pal.text.copy(alpha = 0.8f)
    val pill = RoundedCornerShape(50)
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    Slider(
        value = value, onValueChange = onValueChange, modifier = modifier, enabled = enabled,
        onValueChangeFinished = onValueChangeFinished, colors = colors,
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        steps = steps,
        thumb = {
            Box(Modifier.size(22.dp).clip(CircleShape).background(pal.accent).border(1.5.dp, edge, CircleShape))
        },
        track = {
            Box(Modifier.fillMaxWidth().height(10.dp).clip(pill).background(pal.text.copy(alpha = 0.08f)).border(1.dp, edge, pill)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(pal.accent))
            }
        },
        valueRange = valueRange,
    )
}

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    sub: String? = null,
    modifier: Modifier = Modifier,
    /** Off: shown grayed out and can't be changed (it doesn't apply right now). */
    enabled: Boolean = true,
) {
    val pal = LocalPalette.current
    Row(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f).clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = pal.text, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            if (sub != null) Text(sub, color = pal.textFaint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        LSwitch(checked, onChange, enabled = enabled)
    }
}

/**
 * The app's on/off switch, in the style of Nothing's phones: flat and monochrome. On: a solid
 * white track with the red dot. Off: a dark track with a thin outline and a grey dot. The dot is
 * the same size either way; only its place and colour change.
 */
@Composable
fun LSwitch(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val pal = LocalPalette.current
    val pos by androidx.compose.animation.core.animateFloatAsState(
        if (checked) 1f else 0f, androidx.compose.animation.core.tween(140), label = "switch",
    )
    val onTrack = if (pal.dark) Color.White else Color(0xFF111111)
    val track = androidx.compose.ui.graphics.lerp(pal.text.copy(alpha = 0.07f), onTrack, pos)
    val thumb = androidx.compose.ui.graphics.lerp(pal.text.copy(alpha = 0.5f), NothingRed, pos)
    val border = pal.text.copy(alpha = 0.42f * (1f - pos))
    Box(
        modifier
            .size(width = SwitchW, height = SwitchH)
            .clip(RoundedCornerShape(50))
            .background(track)
            .border(1.dp, border, RoundedCornerShape(50))
            .toggleable(
                value = checked,
                enabled = enabled,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = androidx.compose.material3.ripple(bounded = false, radius = 22.dp),
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = onChange,
            ),
    ) {
        val travel = SwitchW - SwitchH
        Box(
            Modifier
                .padding(SwitchPad)
                .offset(x = travel * pos)
                .size(SwitchH - SwitchPad * 2)
                .clip(CircleShape)
                .background(thumb)
        )
    }
}

private val NothingRed = Color(0xFFD71921)
private val SwitchW = 52.dp
private val SwitchH = 30.dp
private val SwitchPad = 7.dp

/** Small pill-shaped choice chip. */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .then(if (enabled) Modifier else Modifier.alpha(0.4f))
            .clip(shape)
            .background(if (selected) pal.accent else pal.text.copy(alpha = 0.08f))
            .border(1.dp, if (selected) Color.Transparent else pal.text.copy(alpha = 0.12f), shape)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
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

/**
 * Two or more options in one rounded track, the chosen one filled with the accent (like the
 * Songs / All switch at the top of the Library).
 */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    background: Color? = null,
    onSelect: (Int) -> Unit,
) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(background ?: pal.text.copy(alpha = 0.08f))
            .border(1.dp, pal.text.copy(alpha = 0.10f), shape).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.clip(shape)
                    .background(if (on) pal.accent else Color.Transparent)
                    .clickable(enabled = !on) { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    label, color = if (on) pal.onAccent else pal.textDim, maxLines = 1,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
