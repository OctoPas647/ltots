package com.ltots.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.ltots.app.audio.AudioEngine
import com.ltots.app.ui.topInset
import com.ltots.app.audio.OutputDevice
import com.ltots.app.ui.bottomChrome
import com.ltots.app.ui.PlayerUi
import com.ltots.app.ui.components.CoverSpectrum
import com.ltots.app.ui.components.LiveSpectrum
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.components.formatTime
import com.ltots.app.ui.components.rememberLiveVisual
import com.ltots.app.ui.glass.GlassCircleButton
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.glass.glass
import com.ltots.app.ui.theme.LocalPalette
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun NowPlayingScreen(player: PlayerUi, artwork: Bitmap?, onOpenEq: () -> Unit, onOpenSound: () -> Unit) {
    val pal = LocalPalette.current
    val device by AudioEngine.output.device.collectAsState()
    val visual = rememberLiveVisual(wave = false) // no waveform on this screen

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topInset()).padding(horizontal = PAGE_PAD),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Output chip
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), horizontalArrangement = Arrangement.Center) {
            GlassPanel(shape = RoundedCornerShape(50), strength = 0.7f) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenSound).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(deviceIcon(device.kind), null, tint = pal.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(device.name, color = pal.text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (device.kind == OutputDevice.Kind.BLUETOOTH_LE) {
                        Spacer(Modifier.width(6.dp))
                        Text("LC3", color = Color(0xFFFFC14D), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (!player.hasMedia) {
            Spacer(Modifier.height(80.dp))
            Icon(Icons.Rounded.MusicNote, null, tint = pal.textFaint, modifier = Modifier.size(64.dp))
            Text("Nothing playing yet", color = pal.textDim, modifier = Modifier.padding(top = 12.dp))
            Text("Pick a song in the Library.", color = pal.textFaint, style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        // Artwork with the live spectrum: wrapped around a smaller cover, or rising behind the
        // full-size one (Look tab > Visualizer). Icon mode: an icon's outline in the cover's place.
        val lookCfg = com.ltots.app.ui.theme.LocalThemeConfig.current
        val icon = if (lookCfg.iconMode) com.ltots.app.ui.components.rememberIconLoad(lookCfg) else com.ltots.app.ui.components.IconLoad.None
        when (icon) {
            is com.ltots.app.ui.components.IconLoad.Ready -> com.ltots.app.ui.components.IconSpectrum(
                visual, icon.shape, artwork, playing = player.isPlaying,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            // Still working the icon out (a moment, once): keep its place empty.
            com.ltots.app.ui.components.IconLoad.Loading -> Spacer(Modifier.fillMaxWidth().aspectRatio(1f))
            // No icon (or it can't be read): the cover with the visualizer, as usual.
            com.ltots.app.ui.components.IconLoad.None -> CoverWithSpectrum(visual, artwork)
        }

        Spacer(Modifier.height(18.dp))
        Text(player.title, style = MaterialTheme.typography.headlineSmall, color = pal.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(player.artist, player.album).filter { it.isNotBlank() }.joinToString(" — "),
            color = pal.textDim, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )

        // Seek bar
        var dragging by remember { mutableStateOf<Float?>(null) }
        val dur = player.durationMs.coerceAtLeast(1)
        Slider(
            value = dragging ?: (player.positionMs.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { player.controller?.seekTo((it * dur).toLong()) }; dragging = null },
            colors = SliderDefaults.colors(thumbColor = pal.text, activeTrackColor = pal.accent, inactiveTrackColor = pal.text.copy(alpha = 0.15f)),
            modifier = Modifier.padding(top = 10.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(formatTime(dragging?.let { (it * dur).toLong() } ?: player.positionMs), color = pal.textDim, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            Text(formatTime(player.durationMs), color = pal.textDim, style = MaterialTheme.typography.bodySmall)
        }

        // Transport
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }) {
                Icon(Icons.Rounded.Shuffle, "Shuffle", tint = if (player.shuffle) pal.accent else pal.textDim)
            }
            GlassCircleButton({ player.controller?.seekToPrevious() }, Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, "Previous", tint = pal.text, modifier = Modifier.size(30.dp))
            }
            // Play/pause: a solid button in the live accent, so with cover colours it takes the song's
            // colour. The colour is an ordinary fill on top of the glass (rim and shadow stay), so it
            // switches in the same frame as the new cover.
            Box(
                Modifier.size(78.dp)
                    .glass(CircleShape, strength = 0.7f)
                    .background(pal.accent, CircleShape)
                    .clip(CircleShape)
                    .clickable(
                        remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = androidx.compose.material3.ripple(),
                    ) { player.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/pause", tint = pal.onAccent, modifier = Modifier.size(42.dp))
            }
            GlassCircleButton({ player.controller?.seekToNext() }, Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipNext, "Next", tint = pal.text, modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = {
                player.controller?.let {
                    it.repeatMode = when (it.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }
            }) {
                Icon(
                    if (player.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat",
                    tint = if (player.repeatMode != Player.REPEAT_MODE_OFF) pal.accent else pal.textDim,
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        LyricsCard(player)
        Spacer(Modifier.height(12.dp))
        PitchSpeedCard()
        Spacer(Modifier.height(12.dp))
        EqShortcut(onOpenEq)
        Spacer(Modifier.height(bottomChrome()))
    }
}

/**
 * The song's cover with the live spectrum: wrapped around a smaller cover, or rising behind the
 * full-size one (Look tab > Visualizer).
 */
@Composable
private fun CoverWithSpectrum(visual: com.ltots.app.ui.components.LiveVisual, artwork: Bitmap?) {
    val pal = LocalPalette.current
    val lookCfg = com.ltots.app.ui.theme.LocalThemeConfig.current
    val coverAlpha = lookCfg.coverOpacity
    val wrap = lookCfg.spectrumAroundCover
    val coverFraction = if (wrap) COVER_WRAPPED else COVER_FULL
    val coverCorner = if (wrap) 24.dp else 28.dp
    // Move with the beat (Look tab): the cover (with its ring, and its Gradient flow light) pumps
    // on bass hits, hardest on drops. Read while drawing, so only these layers update.
    val beatOn = lookCfg.coverBeat && visual.fps > 0
    // Up to +30 % at full strength (+15 % at the default): clearly visible, like remix videos.
    val beatAmount = 0.30f * lookCfg.beatStrength
    androidx.compose.runtime.SideEffect { visual.beats.source = lookCfg.beatSource; visual.wantsBeat = beatOn }
    androidx.compose.runtime.DisposableEffect(visual) { onDispose { visual.wantsBeat = false } }
    // Turned on while a song plays: prepare its beats (and the next two) right away.
    val beatContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(beatOn) {
        if (beatOn) com.ltots.app.audio.BeatIndex.request(beatContext, com.ltots.app.audio.AudioEngine.upcomingUris(3))
    }
    val beatScale = if (!beatOn) Modifier else Modifier.graphicsLayer {
        @Suppress("UNUSED_VARIABLE") val t = visual.tick
        // A gentle curve lifts small hits so they show too; full hits still reach the maximum.
        val s = 1f + beatAmount * kotlin.math.sqrt(visual.beat)
        scaleX = s; scaleY = s
    }
    // Cover opacity with bars (Look tab): where the bars behind the cover pass, the cover has
    // exactly this opacity; everywhere else exactly Cover opacity.
    val barsAlpha = lookCfg.coverBarsOpacity ?: coverAlpha
    val maskBars = !wrap && visual.fps > 0 && kotlin.math.abs(barsAlpha - coverAlpha) > 0.004f
    val padTop = if (lookCfg.behindLayout == com.ltots.app.data.BehindLayout.BELOW) 40.dp else 0.dp
    val padBottom = if (lookCfg.behindLayout == com.ltots.app.data.BehindLayout.ABOVE ||
        lookCfg.behindLayout == com.ltots.app.data.BehindLayout.ABOVE_FLIPPED) 40.dp else 0.dp
    // Top & bottom: the spectrum reaches over the page's margins, so it spans the whole width
    // of the screen (the cover stays where it is).
    val wide = if (!wrap && lookCfg.behindLayout.both) PAGE_PAD else 0.dp
    Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        // Gradient flow (Look tab): light round the cover in its edges' colours, like a TV's
        // backlight. On AMOLED it melts into your own colour, elsewhere it fades out.
        if (lookCfg.gradientFlow && artwork != null) {
            val into = if (lookCfg.pureBlack && !lookCfg.dynamicColors) pal.accent.toArgb() else null
            com.ltots.app.ui.components.GradientFlowLight(artwork, coverFraction, into, Modifier.matchParentSize().then(beatScale))
        }
        if (wrap) CoverSpectrum(visual, coverFraction, coverCorner, Modifier.fillMaxSize().then(beatScale), intensity = 0.9f)
        else LiveSpectrum(
            visual,
            // A little space on the side the spectrum grows towards (below: at the top; above: at
            // the bottom). Top & bottom use the whole height, half each.
            Modifier.fillMaxSize()
                .layout { m, c ->
                    val e = wide.roundToPx()
                    val p = m.measure(c.copy(minWidth = c.maxWidth + 2 * e, maxWidth = c.maxWidth + 2 * e))
                    layout(c.maxWidth, p.height) { p.place(-e, 0) }
                }
                .padding(top = padTop, bottom = padBottom),
            showWave = false, intensity = 0.8f, from = lookCfg.behindLayout,
        )
        val style = lookCfg.spectrumStyle
        val from = lookCfg.behindLayout
        Box(
            Modifier.fillMaxWidth(coverFraction).aspectRatio(1f)
                .then(beatScale)
                // Cover opacity (Look tab): lower lets the live spectrum behind show through.
                .graphicsLayer {
                    if (maskBars) {
                        // Its own layer, so the opacity can be set part by part (drawn below).
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    } else alpha = coverAlpha
                }
                .shadow(28.dp * (if (maskBars) coverAlpha else 1f), RoundedCornerShape(coverCorner), ambientColor = pal.accent, spotColor = pal.accent)
                .clip(RoundedCornerShape(coverCorner)).background(pal.text.copy(alpha = 0.08f))
                .then(
                    if (!maskBars) Modifier else Modifier.drawWithContent {
                        drawContent()
                        @Suppress("UNUSED_VARIABLE") val t = visual.tick // follow every frame
                        // The spectrum's area, in the cover's own coordinates.
                        val whole = size.width / coverFraction
                        val edge = (whole - size.width) / 2 + wide.toPx()
                        val top = padTop.toPx(); val bottom = padBottom.toPx()
                        with(com.ltots.app.ui.components.SpectrumMask) {
                            drawOpacity(
                                visual.frame.post, style, from,
                                left = -edge, top = top - edge + wide.toPx(), w = whole + 2 * wide.toPx(), h = whole - top - bottom,
                                cover = coverAlpha, underBars = barsAlpha,
                            )
                        }
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                Image(remember(artwork) { artwork.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = pal.textFaint, modifier = Modifier.size(72.dp))
            }
        }
    }
}

fun deviceIcon(kind: OutputDevice.Kind) = when (kind) {
    OutputDevice.Kind.BLUETOOTH_CLASSIC, OutputDevice.Kind.BLUETOOTH_LE -> Icons.Rounded.Bluetooth
    OutputDevice.Kind.USB -> Icons.Rounded.Usb
    OutputDevice.Kind.WIRED -> Icons.Rounded.Headphones
    else -> Icons.Rounded.Speaker
}

@Composable
private fun PitchSpeedCard() {
    val pal = LocalPalette.current
    val cents by AudioEngine.pitchCents.collectAsState()
    val speed by AudioEngine.speed.collectAsState()
    val tape by AudioEngine.tape.collectAsState()
    val settings by AudioEngine.settings.collectAsState()
    val sliderColors = SliderDefaults.colors(
        thumbColor = pal.accent, activeTrackColor = pal.accent, inactiveTrackColor = pal.text.copy(alpha = 0.15f),
        activeTickColor = androidx.compose.ui.graphics.Color.Transparent, inactiveTickColor = androidx.compose.ui.graphics.Color.Transparent,
    )

    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Pitch & speed", Modifier.weight(1f))
                Pill("Tape", tape, { AudioEngine.setTape(!tape) })
            }

            // Pitch — heard live while dragging (in tape mode it's applied on release).
            // Two sliders: whole semitones, and a fine one for the cents in between (1 cent steps).
            var typing by remember { mutableStateOf(false) }
            // While the fine slider is dragged, its semitone stays put (so passing ±50 ct doesn't
            // make the slider jump to the other end).
            var fineBase by remember { mutableStateOf<Int?>(null) }
            val semis = fineBase ?: (cents / 100f).roundToInt()
            val fine = cents - semis * 100
            fun setCents(c: Int) {
                AudioEngine.setPitch(c)
                if (tape) AudioEngine.commit()
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pitch", color = pal.textDim, modifier = Modifier.weight(1f))
                // Tap the value to type an exact pitch.
                Text(
                    pitchLabel(cents), color = pal.text, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { typing = true }
                        .background(pal.text.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(8.dp))
                Pill("Reset", false, { setCents(0) })
            }
            Slider(
                value = semis.toFloat().coerceIn(-12f, 12f),
                // Whole semitones; the fine slider below keeps its cents.
                onValueChange = { v -> AudioEngine.setPitch((v.roundToInt() * 100 + fine).coerceIn(-1200, 1200)) },
                steps = 23,
                onValueChangeFinished = { if (tape) AudioEngine.commit() },
                valueRange = -12f..12f,
                colors = sliderColors,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fine", color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(36.dp))
                Slider(
                    value = fine.toFloat().coerceIn(-50f, 50f),
                    onValueChange = { v ->
                        val base = fineBase ?: semis.also { fineBase = it }
                        AudioEngine.setPitch((base * 100 + v.roundToInt()).coerceIn(-1200, 1200))
                    },
                    onValueChangeFinished = { fineBase = null; if (tape) AudioEngine.commit() },
                    valueRange = -50f..50f,
                    colors = sliderColors,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    (if (fine > 0) "+" else if (fine < 0) "−" else "") + "${abs(fine)} ct",
                    color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End, modifier = Modifier.width(48.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-100 to "−1 st", -1 to "−1 ct", 1 to "+1 ct", 100 to "+1 st").forEach { (d, l) ->
                    Pill(l, false, { setCents((cents + d).coerceIn(-1200, 1200)) })
                }
            }
            if (typing) ExactPitchDialog(cents, onDismiss = { typing = false }, onSet = { setCents(it); typing = false })

            Spacer(Modifier.height(14.dp))
            // Stem pitch — early version. Mids moves the overall pitch for now; independent
            // Bass / Treble bands arrive with the three-band engine.
            var stemsOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Stem pitch", Modifier.weight(1f))
                Pill(if (stemsOpen) "Hide" else "AI / normal", false, { stemsOpen = !stemsOpen })
            }
            if (stemsOpen) {
                Text(
                    "Early version: Mids moves the overall pitch. Independent Bass and Treble arrive with the three-band engine.",
                    color = pal.textFaint, style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mids", color = pal.textDim, modifier = Modifier.weight(1f))
                    Text(pitchLabel(cents), color = pal.text, fontWeight = FontWeight.SemiBold)
                }
                Slider(
                    value = (cents / 100f).coerceIn(-12f, 12f),
                    onValueChange = { v -> AudioEngine.setPitch(v.roundToInt() * 100) },
                    steps = 23,
                    valueRange = -12f..12f,
                    colors = sliderColors,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Vocal lift", false, { AudioEngine.setPitch(200) })
                    Pill("Reset", false, { AudioEngine.setPitch(0) })
                }
            }

            Spacer(Modifier.height(14.dp))
            // Speed — previewed while dragging, applied on release (no stutter while you drag).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speed", color = pal.textDim, modifier = Modifier.weight(1f))
                Text("%.2f×".format(Locale.ROOT, speed), color = pal.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Pill("Reset", false, { AudioEngine.previewSpeed(1f); AudioEngine.commit() })
            }
            Slider(
                value = speed.coerceIn(0.5f, 2f),
                onValueChange = { AudioEngine.previewSpeed((it * 100).roundToInt() / 100f) },
                onValueChangeFinished = { AudioEngine.commit() },
                valueRange = 0.5f..2f,
                colors = sliderColors,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-0.05f to "−0.05", -0.01f to "−0.01", 0.01f to "+0.01", 0.05f to "+0.05").forEach { (d, l) ->
                    Pill(l, false, {
                        AudioEngine.previewSpeed(((speed + d) * 100).roundToInt() / 100f)
                        AudioEngine.commit()
                    })
                }
            }
            Text(
                buildString {
                    append(if (tape) "Tape mode: speed and pitch move together, like a record." else "Pitch and speed are independent.")
                    append(if (settings.perSongPitchSpeed) " Saved for this song." else " Applies to every song.")
                },
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * Type an exact pitch: semitones and cents, or tune to a reference — the note A at a given
 * frequency (A = 432 Hz is about −32 cents from the standard 440 Hz).
 */
@Composable
internal fun ExactPitchDialog(cents: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    val pal = LocalPalette.current
    val st0 = cents / 100
    var semiText by remember { mutableStateOf(if (st0 == 0) "" else st0.toString()) }
    var centText by remember { mutableStateOf((cents - st0 * 100).let { if (it == 0) "" else it.toString() }) }
    var hzText by remember { mutableStateOf("") }
    fun fromHz(): Int? = hzText.replace(',', '.').toDoubleOrNull()?.takeIf { it in 220.0..880.0 }
        ?.let { (1200 * kotlin.math.ln(it / 440.0) / kotlin.math.ln(2.0)).roundToInt() }
    fun fromFields(): Int? {
        val s = semiText.trim().replace('−', '-').ifEmpty { "0" }.toIntOrNull() ?: return null
        val c = centText.trim().replace('−', '-').ifEmpty { "0" }.toIntOrNull() ?: return null
        return s * 100 + c
    }
    val result = (if (hzText.isNotBlank()) fromHz() else fromFields())?.takeIf { it in -1200..1200 }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exact pitch") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumberField(semiText, { semiText = it; hzText = "" }, "Semitones", Modifier.weight(1f))
                    NumberField(centText, { centText = it; hzText = "" }, "Cents", Modifier.weight(1f))
                    // Number keyboards often have no minus key: this flips higher / lower.
                    Pill("±", false, {
                        fromFields()?.let { v -> val n = -v; semiText = (n / 100).toString(); centText = (n - n / 100 * 100).toString() }
                        hzText = ""
                    })
                }
                Text("or tune the note A to", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumberField(hzText, { hzText = it }, "440", Modifier.weight(1f))
                    Text("  Hz", color = pal.textDim)
                }
                Text(
                    when {
                        result == null -> "Up to ±12 semitones (±1200 cents); A between 220 and 880 Hz."
                        else -> "= " + pitchLabel(result) + if (hzText.isBlank()) "" else " (A = ${hzText.trim()} Hz)"
                    },
                    color = if (result == null) Color(0xFFFFC14D) else pal.textFaint, style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { result?.let(onSet) }, enabled = result != null) { Text("Set") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(pal.text.copy(alpha = 0.08f)).padding(12.dp)) {
        if (value.isEmpty()) Text(hint, color = pal.textFaint)
        androidx.compose.foundation.text.BasicTextField(
            value, { v -> onChange(v.filter { it.isDigit() || it == '-' || it == '−' || it == '.' || it == ',' }.take(7)) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = pal.text, fontSize = 16.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(pal.accent),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun pitchLabel(cents: Int): String {
    if (cents == 0) return "Original"
    val sign = if (cents > 0) "+" else "−"
    val a = abs(cents)
    val st = a / 100
    val ct = a % 100
    return buildString {
        append(sign)
        if (st > 0) append("$st st")
        if (ct > 0) { if (st > 0) append(" "); append("$ct ct") }
    }
}

@Composable
private fun EqShortcut(onOpenEq: () -> Unit) {
    val pal = LocalPalette.current
    val eq by AudioEngine.effectiveEq.collectAsState()
    val profile by AudioEngine.songProfile.collectAsState()
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenEq).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.GraphicEq, null, tint = pal.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Equalizer", color = pal.text, fontWeight = FontWeight.SemiBold)
                Text(
                    (if (!eq.enabled) "Off" else "${eq.bands.size} bands") + " · " +
                        (if (profile?.eq != null) "This song's own EQ" else "Default EQ"),
                    color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = pal.textDim)
        }
    }
}

/** The cover's width on the Playing tab: full size, or smaller with the visualizer around it. */
private const val COVER_FULL = 0.82f
/** The Playing tab's margin at the left and right. */
private val PAGE_PAD = 18.dp
private const val COVER_WRAPPED = 0.62f
