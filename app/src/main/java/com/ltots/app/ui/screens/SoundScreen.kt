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
                PathRow("Decoded", if (info.sampleRate > 0) "${"%.1f".format(Locale.ROOT, info.sampleRate / 1000.0)} kHz · ${info.channels} ch" else "—")
                PathRow("EQ & limiter", "64-bit floating point")
                PathRow("Pitch & speed", "Sonic time-stretch (only when changed)")
                PathRow("Sent to Android", info.encodingName(info.outputEncoding))
                PathRow("Output", device.kindLabel)
            }
        }

        Spacer(Modifier.height(12.dp))
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
            Column(Modifier.padding(16.dp)) {
                SectionTitle("Quality")
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
            }
        }

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
