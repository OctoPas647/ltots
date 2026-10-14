package com.ltots.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ltots.app.audio.BiquadCoefs
import com.ltots.app.audio.EqBand
import com.ltots.app.audio.EqLimits
import com.ltots.app.audio.EqMath
import com.ltots.app.audio.EqState
import com.ltots.app.ui.components.formatDb
import com.ltots.app.ui.components.formatFreq
import com.ltots.app.ui.theme.LocalPalette
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

/** Visible gain range of the graph (±dB). Bands can go further via the editor. */
const val GRAPH_RANGE_DB = 18f
private const val FS = 48000.0

/** Maps between graph pixels and frequency / gain. Log frequency 20 Hz – 20 kHz, same as the spectrum. */
class GraphGeometry(val w: Float, val h: Float, val pad: Float) {
    fun x(f: Float): Float = (w * ln(f / 20f) / ln(1000f)).coerceIn(0f, w)
    fun f(x: Float): Float = (20f * 1000f.pow((x / w).coerceIn(0f, 1f))).coerceIn(EqLimits.MIN_FREQ, EqLimits.MAX_FREQ)
    fun y(db: Float): Float = h / 2 - db.coerceIn(-GRAPH_RANGE_DB, GRAPH_RANGE_DB) / GRAPH_RANGE_DB * (h / 2 - pad)
    fun db(y: Float): Float = ((h / 2 - y) / (h / 2 - pad) * GRAPH_RANGE_DB).coerceIn(-GRAPH_RANGE_DB, GRAPH_RANGE_DB)
}

/**
 * Spotify-style EQ curve: drag a dot left/right for frequency and up/down for gain; tap to
 * select; double-tap empty space to add a band there.
 */
@Composable
fun EqGraph(
    eq: EqState,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onMove: (id: Long, freq: Float, gain: Float) -> Unit,
    onAdd: (freq: Float, gain: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pal = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val curEq by rememberUpdatedState(eq)
    val curSelect by rememberUpdatedState(onSelect)
    val curMove by rememberUpdatedState(onMove)
    val curAdd by rememberUpdatedState(onAdd)
    var dragging by remember { mutableLongStateOf(-1L) }

    // Total response, sampled across the graph (recomputed only when the EQ changes).
    val curve = remember(eq) {
        val coefs = eq.bands.map { BiquadCoefs.design(it, FS) }
        FloatArray(240) { i ->
            val f = 20.0 * 1000.0.pow(i / 239.0)
            EqMath.responseDb(coefs, f, FS).toFloat()
        }
    }
    val selectedCurve = remember(eq, selectedId) {
        val band = eq.bands.firstOrNull { it.id == selectedId } ?: return@remember null
        val c = BiquadCoefs.design(band.copy(enabled = true), FS)
        FloatArray(240) { i -> c.magnitudeDb(20.0 * 1000.0.pow(i / 239.0), FS).toFloat() }
    }

    val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
    val hitPx = with(androidx.compose.ui.platform.LocalDensity.current) { 34.dp.toPx() }

    fun handlePos(g: GraphGeometry, b: EqBand): Offset =
        Offset(g.x(b.freqHz), if (b.type.usesGain) g.y(b.gainDb) else g.y(0f))

    fun nearest(g: GraphGeometry, p: Offset): EqBand? = curEq.bands
        .map { it to handlePos(g, it) }
        .minByOrNull { (_, o) -> hypot(o.x - p.x, o.y - p.y) }
        ?.takeIf { (_, o) -> hypot(o.x - p.x, o.y - p.y) <= hitPx }?.first

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { p ->
                        val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), padPx)
                        curSelect(nearest(g, p)?.id)
                    },
                    onDoubleTap = { p ->
                        val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), padPx)
                        if (nearest(g, p) == null) curAdd(g.f(p.x), g.db(p.y))
                    },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { p ->
                        val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), padPx)
                        val b = nearest(g, p)
                        dragging = b?.id ?: -1L
                        if (b != null) curSelect(b.id)
                    },
                    onDragEnd = { dragging = -1L },
                    onDragCancel = { dragging = -1L },
                    onDrag = { change, _ ->
                        if (dragging >= 0) {
                            change.consume()
                            val g = GraphGeometry(size.width.toFloat(), size.height.toFloat(), padPx)
                            val b = curEq.bands.firstOrNull { it.id == dragging } ?: return@detectDragGestures
                            val f = com.ltots.app.audio.EqDefaults.roundFreq(g.f(change.position.x))
                            val gain = if (b.type.usesGain) (Math.round(g.db(change.position.y) * 10f) / 10f) else b.gainDb
                            curMove(b.id, f, gain)
                        }
                    },
                )
            }
    ) {
        val g = GraphGeometry(size.width, size.height, padPx)
        drawGrid(g, pal.text, measurer)

        val active = eq.enabled
        val lineColor = if (active) pal.accent else pal.text.copy(alpha = 0.35f)

        // Selected band's own shape, faint.
        selectedCurve?.let { sc -> drawResponse(g, sc, pal.text.copy(alpha = 0.35f), fill = false, width = 1.5f) }
        // Total EQ curve.
        drawResponse(g, curve, lineColor, fill = true, width = 3.5f)

        // Handles
        eq.bands.sortedBy { if (it.id == selectedId) 1 else 0 }.forEach { b ->
            val o = handlePos(g, b)
            val sel = b.id == selectedId
            val r = if (sel) 12.dp.toPx() else 8.dp.toPx()
            if (sel) drawCircle(pal.accent.copy(alpha = 0.25f), r * 2f, o)
            if (b.enabled && active) {
                drawCircle(lineColor, r, o)
                drawCircle(Color.White.copy(alpha = if (sel) 1f else 0.85f), r, o, style = Stroke(width = 2.dp.toPx()))
            } else {
                drawCircle(pal.text.copy(alpha = 0.5f), r, o, style = Stroke(width = 2.dp.toPx()))
            }
            if (sel) {
                val label = formatFreq(b.freqHz) + (if (b.type.usesGain) "  " + formatDb(b.gainDb) else "") + "  Q " + "%.2f".format(java.util.Locale.ROOT, b.q)
                val layout = measurer.measure(label, TextStyle(color = pal.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                val tx = (o.x - layout.size.width / 2f).coerceIn(4f, size.width - layout.size.width - 4f)
                val ty = if (o.y - r - layout.size.height - 10 > 0) o.y - r - layout.size.height - 10 else o.y + r + 10
                drawRoundRect(
                    Color.Black.copy(alpha = 0.45f), Offset(tx - 8, ty - 4),
                    androidx.compose.ui.geometry.Size(layout.size.width + 16f, layout.size.height + 8f),
                    androidx.compose.ui.geometry.CornerRadius(10f),
                )
                drawText(layout, topLeft = Offset(tx, ty))
            }
        }
    }
}

private fun DrawScope.drawResponse(g: GraphGeometry, pts: FloatArray, color: Color, fill: Boolean, width: Float) {
    val n = pts.size
    val path = Path()
    for (i in 0 until n) {
        val x = size.width * i / (n - 1f)
        val y = g.y(pts[i])
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    if (fill) {
        val zero = g.y(0f)
        val area = Path().apply { addPath(path); lineTo(size.width, zero); lineTo(0f, zero); close() }
        drawPath(
            area,
            Brush.verticalGradient(
                listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.06f), color.copy(alpha = 0.32f)),
                startY = g.y(GRAPH_RANGE_DB), endY = g.y(-GRAPH_RANGE_DB),
            ),
        )
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
}

private fun DrawScope.drawGrid(g: GraphGeometry, text: Color, measurer: androidx.compose.ui.text.TextMeasurer) {
    val style = TextStyle(color = text.copy(alpha = 0.45f), fontSize = 10.sp)
    for (db in listOf(-18, -12, -6, 0, 6, 12, 18)) {
        val y = g.y(db.toFloat())
        drawLine(text.copy(alpha = if (db == 0) 0.22f else 0.07f), Offset(0f, y), Offset(size.width, y), strokeWidth = if (db == 0) 1.5f else 1f)
        if (db != 0 && db != 18 && db != -18) {
            drawText(measurer, (if (db > 0) "+" else "") + db, Offset(6f, y - 14f), style)
        }
    }
    for (f in listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)) {
        val x = g.x(f)
        drawLine(text.copy(alpha = 0.07f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        drawText(measurer, formatFreq(f), Offset(x + 4f, size.height - 16f), style)
    }
}
