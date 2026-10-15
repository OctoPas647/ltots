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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.ltots.app.audio.AudioEngine
import com.ltots.app.audio.OutputDevice
import com.ltots.app.ui.BottomChrome
import com.ltots.app.ui.PlayerUi
import com.ltots.app.ui.components.LiveSpectrum
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.components.formatTime
import com.ltots.app.ui.components.rememberLiveVisual
import com.ltots.app.ui.glass.GlassCircleButton
import com.ltots.app.ui.glass.GlassPanel
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
    val visual = rememberLiveVisual()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
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

        // Artwork with the live spectrum glowing behind it
        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            LiveSpectrum(visual, Modifier.fillMaxSize().padding(top = 40.dp), showWave = false, intensity = 0.8f)
            Box(
                Modifier.fillMaxWidth(0.82f).aspectRatio(1f)
                    .shadow(28.dp, RoundedCornerShape(28.dp), ambientColor = pal.accent, spotColor = pal.accent)
                    .clip(RoundedCornerShape(28.dp)).background(pal.text.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                if (artwork != null) {
                    Image(remember(artwork) { artwork.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Rounded.MusicNote, null, tint = pal.textFaint, modifier = Modifier.size(72.dp))
                }
            }
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
            GlassCircleButton({ player.togglePlay() }, Modifier.size(78.dp), highlighted = true) {
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
        PitchSpeedCard()
        Spacer(Modifier.height(12.dp))
        EqShortcut(onOpenEq)
        Spacer(Modifier.height(BottomChrome.calculateBottomPadding() - 70.dp))
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
                Spacer(Modifier.width(8.dp))
                Pill("Reset", false, { AudioEngine.resetPitchSpeed() })
            }

            // Pitch — heard live while dragging (in tape mode it's applied on release).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pitch", color = pal.textDim, modifier = Modifier.weight(1f))
                Text(pitchLabel(cents), color = pal.text, fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = (cents / 100f).coerceIn(-12f, 12f),
                // Snaps to whole semitones; the ±10 ct buttons fine-tune.
                onValueChange = { v -> AudioEngine.setPitch(v.roundToInt() * 100 + cents.mod(100).let { if (it > 50) it - 100 else it }) },
                steps = 23,
                onValueChangeFinished = { if (tape) AudioEngine.commit() },
                valueRange = -12f..12f,
                colors = sliderColors,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-100 to "−1 st", -10 to "−10 ct", 10 to "+10 ct", 100 to "+1 st").forEach { (d, l) ->
                    Pill(l, false, {
                        AudioEngine.setPitch(cents + d)
                        if (tape) AudioEngine.commit()
                    })
                }
            }

            Spacer(Modifier.height(14.dp))
            // Speed — previewed while dragging, applied on release (no stutter while you drag).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speed", color = pal.textDim, modifier = Modifier.weight(1f))
                Text("%.2f×".format(Locale.ROOT, speed), color = pal.text, fontWeight = FontWeight.SemiBold)
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
