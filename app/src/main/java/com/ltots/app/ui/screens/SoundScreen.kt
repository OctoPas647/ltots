package com.ltots.app.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ltots.app.audio.AudioEngine
import com.ltots.app.audio.OutputDevice
import com.ltots.app.ui.BottomChrome
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.components.SwitchRow
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.theme.LocalPalette
import java.util.Locale

@Composable
fun SoundScreen() {
    val pal = LocalPalette.current
    val context = LocalContext.current
    val device by AudioEngine.output.device.collectAsState()
    val bitPerfect by AudioEngine.output.bitPerfectStatus.collectAsState()
    val info by AudioEngine.tap.info.collectAsState()
    val settings by AudioEngine.settings.collectAsState()
    val direct by AudioEngine.direct.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Text("Sound", style = MaterialTheme.typography.displaySmall, color = pal.text, modifier = Modifier.padding(top = 12.dp))
        Text("Output, quality and the signal path.", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(14.dp))

        // Output device
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Playing through")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(deviceIcon(device.kind), null, tint = pal.accent, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(device.name, color = pal.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        Text(device.kindLabel, color = if (device.kind == OutputDevice.Kind.BLUETOOTH_LE) Color(0xFFFFC14D) else pal.textDim,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (device.tip.isNotBlank()) {
                    Text(device.tip, color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                }
                if (device.kind == OutputDevice.Kind.BLUETOOTH_CLASSIC || device.kind == OutputDevice.Kind.BLUETOOTH_LE) {
                    Spacer(Modifier.height(10.dp))
                    Pill("Open Developer options", false, {
                        runCatching {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    })
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // Signal path
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Signal path")
                if (direct) {
                    PathRow("Played by", "Phone's audio chip")
                    Text(
                        "Nothing to process right now (EQ flat, original pitch and speed), so the audio chip " +
                            "decodes and plays the file exactly as it is while the processor sleeps. " +
                            "The live spectrum rests until the EQ or pitch is changed.",
                        color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                    )
                }
                PathRow("Decoded", if (info.sampleRate > 0) "${"%.1f".format(Locale.ROOT, info.sampleRate / 1000.0)} kHz · ${info.channels} ch" else "—")
                PathRow("EQ & limiter", "64-bit floating point")
                PathRow("Pitch & speed", (if (settings.efficientPitch) "Rubber Band R2" else "Rubber Band R3") + " (only when changed)")
                PathRow("Limiter", "Soft, above −1 dBFS only")
                PathRow("Sent to Android", if (settings.bitPerfectUsb) "16-bit, dithered" else "32-bit float")
                PathRow("Output", device.kindLabel)
            }
        }

        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Quality & battery")
                SwitchRow(
                    "Power-saving playback", settings.directPlayback,
                    { on -> AudioEngine.updateSettings { it.copy(directPlayback = on) } },
                    sub = "When the EQ is off or flat and pitch and speed are original, the phone's audio chip plays " +
                        "the music by itself and the processor sleeps — the biggest battery saving there is. " +
                        "Switches back instantly when you change the EQ or pitch. " +
                        if (direct) "Active now." else "Not active right now (something needs processing, or the chip can't play this file's format).",
                )
                SwitchRow(
                    "Bit-perfect USB output", settings.bitPerfectUsb, { on -> AudioEngine.updateSettings { it.copy(bitPerfectUsb = on) } },
                    sub = if (Build.VERSION.SDK_INT >= 34) "For a USB DAC (e.g. HD 599 via a dongle): Android's mixer is bypassed. Use the DAC's or phone's volume as usual. Status: $bitPerfect"
                    else "Needs Android 14 or newer.",
                )
                SwitchRow(
                    "Remember pitch & speed per song", settings.perSongPitchSpeed,
                    { on -> AudioEngine.updateSettings { it.copy(perSongPitchSpeed = on) } },
                    sub = "Off: pitch and speed stay the same across songs.",
                )
                SwitchRow(
                    "Natural voices when shifting pitch", settings.preserveFormants,
                    { on -> AudioEngine.updateSettings { it.copy(preserveFormants = on) } },
                    sub = "Keeps singers sounding like themselves instead of chipmunk/giant. Best for vocal tracks; off is a classic key change that suits full songs and instruments.",
                )
                SwitchRow(
                    "Battery-saving pitch & speed", settings.efficientPitch,
                    { on -> AudioEngine.updateSettings { it.copy(efficientPitch = on) } },
                    sub = "Uses the lighter Rubber Band engine: about half the processing (and battery) while pitch or speed is changed, with slightly less polished sound. Nothing runs at original pitch and speed either way.",
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        LibraryPrepCard()

        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Visualizer detail")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(48, 96, 160).forEach { n ->
                        Pill("$n bands", settings.analyzerBands == n, { AudioEngine.updateSettings { it.copy(analyzerBands = n) } })
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "LToTS ${appVersion(context)} · phase 1 (local music). YouTube Music sync, downloads and lyrics search are coming in the next phases.",
            color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(BottomChrome.calculateBottomPadding() - 80.dp))
    }
}

/** Waveforms and lyrics prepared for the whole library, like an index. */
@Composable
private fun LibraryPrepCard() {
    val pal = LocalPalette.current
    val context = LocalContext.current
    val running by remember { com.ltots.app.prep.LibraryPrep.running(context) }.collectAsState(false)
    var status by remember { androidx.compose.runtime.mutableStateOf<Triple<Int, Int, Int>?>(null) }
    // Counted when the screen opens and every few seconds while a pass is running.
    com.ltots.app.ui.WhileOnScreen(running) {
        while (true) {
            status = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (!com.ltots.app.prep.LibraryPrep.hasAudioPermission(context)) return@withContext null
                val tracks = com.ltots.app.data.LocalLibrary.load(context)
                Triple(
                    tracks.size,
                    com.ltots.app.audio.WaveIndex.countReady(tracks.map { it.uri }),
                    com.ltots.app.lyrics.Lyrics.countFound(tracks.map { it.profileKey }),
                )
            }
            if (!running) break
            kotlinx.coroutines.delay(4000)
        }
    }
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Library preparation")
            val st = status
            if (st != null) {
                PathRow("Waveforms ready", "${st.second} of ${st.first}")
                PathRow("Lyrics found", "${st.third} of ${st.first}")
            }
            Text(
                "Every song's waveform is worked out once, so the EQ wave is drawn from it in perfect sync instead of " +
                    "being analysed live. This runs by itself while the phone is charging; lyrics are looked up whenever " +
                    "you're online. The song you're playing is always done on the spot." +
                    if (running) " Preparing now…" else "",
                color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )
            Pill("Prepare now", false, { com.ltots.app.prep.LibraryPrep.runNow(context) })
        }
    }
}

@Composable
private fun PathRow(label: String, value: String) {
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = pal.textDim, modifier = Modifier.weight(1f))
        Text(value, color = pal.text, fontWeight = FontWeight.SemiBold)
    }
}

private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
