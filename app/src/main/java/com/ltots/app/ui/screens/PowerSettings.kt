package com.ltots.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import com.ltots.app.audio.AudioEngine
import com.ltots.app.audio.PowerStats
import com.ltots.app.audio.PreRender
import com.ltots.app.data.ScreenOffEq
import com.ltots.app.data.ScreenOffPitch
import com.ltots.app.playback.SleepTimer
import com.ltots.app.ui.components.LabeledSlider
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.components.SegmentedToggle
import com.ltots.app.ui.components.SwitchRow
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.theme.LocalPalette
import kotlin.math.roundToInt

// Screen-off listening, the sleep timer and the battery card (Profile > App settings).

/** Profile > App settings · screen on: what the app stops drawing when nobody is touching it. */
@UnstableApi
@Composable
internal fun ScreenOnSettings() {
    val pal = LocalPalette.current
    val settings by AudioEngine.settings.collectAsState()
    SectionTitle("App settings · screen on")
    Text(
        "With the screen on, most of the battery goes into redrawing the moving glass. These decide how much of that " +
            "happens while you're only looking. Touching the screen brings everything back at once.",
        color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
    )

    val restChoices = listOf(0, 5, 10, 30, 60)
    Text("Rest when untouched for", color = pal.text, style = MaterialTheme.typography.bodyMedium)
    SegmentedToggle(
        restChoices.map { if (it == 0) "Never" else "$it s" }, restChoices.indexOf(settings.restAfterSeconds).coerceAtLeast(0), Modifier.padding(vertical = 8.dp),
    ) { i -> AudioEngine.updateSettings { it.copy(restAfterSeconds = restChoices[i]) } }
    val rests = settings.restAfterSeconds > 0
    SwitchRow(
        "Resting: background stands still", settings.restBackground && rests,
        { on -> AudioEngine.updateSettings { it.copy(restBackground = on) } },
        sub = "The drifting colours stop where they are until you touch the screen. With nothing moving, the glass isn't redrawn at all.",
        enabled = rests,
    )
    val visChoices = listOf(0, 60, 30, 15)
    Text("Resting: visualizer", color = if (rests) pal.text else pal.textFaint, style = MaterialTheme.typography.bodyMedium)
    SegmentedToggle(
        visChoices.map { if (it == 0) "Same" else "$it fps" }, visChoices.indexOf(settings.restVisualizerFps).coerceAtLeast(0), Modifier.padding(vertical = 8.dp),
    ) { i -> if (rests) AudioEngine.updateSettings { it.copy(restVisualizerFps = visChoices[i]) } }

    val bgChoices = listOf(0, 60, 30)
    Text("Background motion", color = pal.text, style = MaterialTheme.typography.bodyMedium)
    SegmentedToggle(
        bgChoices.map { if (it == 0) "Every frame" else "$it fps" }, bgChoices.indexOf(settings.backgroundFps).coerceAtLeast(0), Modifier.padding(vertical = 8.dp),
    ) { i -> AudioEngine.updateSettings { it.copy(backgroundFps = bgChoices[i]) } }
    Text(
        "How often the drifting colours move. They drift slowly, so 30 looks the same as every frame and is about a quarter of the work on a 120 Hz screen. " +
            "(Smoothness on the Look tab can lower this further.)",
        color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp),
    )

    SectionTitle("App settings · motion")
    val feels = com.ltots.app.data.MotionFeel.entries
    SegmentedToggle(feels.map { it.label }, settings.motion.ordinal, Modifier.padding(vertical = 8.dp)) { i ->
        AudioEngine.updateSettings { it.copy(motion = feels[i]) }
    }
    Text(
        "How firmly pages, bars and buttons move. Every movement can be caught or turned round mid-way and carries the speed of your finger; " +
            "Firm arrives sooner than the old feel (Relaxed), Snappy sooner still.",
        color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp),
    )
}

/** Profile > App settings · screen off: what the EQ and pitch do with the screen off, and pre-render. */
@UnstableApi
@Composable
internal fun ScreenOffSettings() {
    val pal = LocalPalette.current
    val context = LocalContext.current
    val settings by AudioEngine.settings.collectAsState()
    val active by AudioEngine.screenOffModes.collectAsState()
    SectionTitle("App settings · screen off")
    Text(
        "With the screen off, LToTS can trade a little sound detail for battery. Changes happen between songs, " +
            "so nothing cuts out mid-song, and everything is back to full quality as soon as you open LToTS.",
        color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
    )

    Text("EQ with the screen off", color = pal.text, style = MaterialTheme.typography.bodyMedium)
    SegmentedToggle(
        ScreenOffEq.entries.map { it.label }, settings.screenOffEq.ordinal, Modifier.padding(vertical = 8.dp),
    ) { i -> AudioEngine.updateSettings { it.copy(screenOffEq = ScreenOffEq.entries[i]) } }
    Text(
        when (settings.screenOffEq) {
            ScreenOffEq.SAME -> "Your exact EQ. LToTS processes it (or pre-renders it, below), so the processor does more work."
            ScreenOffEq.FIVE_BAND -> "The phone's own EQ plays a 5-band version of your curve, so the audio chip plays by itself and the " +
                "processor sleeps. Broad shapes (more bass, brighter, a scooped middle) carry over; narrow cuts don't. " +
                "If the phone's EQ can't be used, your exact EQ plays instead."
            ScreenOffEq.OFF -> "No EQ while the screen is off: the audio chip plays the songs untouched."
        },
        color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp),
    )

    if (settings.screenOffEq == ScreenOffEq.FIVE_BAND || settings.batteryEq) {
        val blocked = AudioEngine.bassBoostBlocked
        SwitchRow(
            "Use the phone's bass boost too", settings.bassBoostHelp && !blocked,
            { on -> AudioEngine.updateSettings { it.copy(bassBoostHelp = on) } },
            sub = if (blocked) "Not used: on this phone the bass boost stops the audio chip playing by itself, which would cost more battery than it saves."
            else "Adds the phone's own bass boost for the deepest bass, so bass-heavy curves come much closer to your real EQ. " +
                "Only used when it makes the curve clearly closer. If it turns out to stop the audio chip playing by itself, LToTS leaves it out from then on.",
            enabled = !blocked,
        )
    }

    Text("Pitch & speed with the screen off", color = pal.text, style = MaterialTheme.typography.bodyMedium)
    SegmentedToggle(
        ScreenOffPitch.entries.map { it.label }, settings.screenOffPitch.ordinal, Modifier.padding(vertical = 8.dp),
    ) { i -> AudioEngine.updateSettings { it.copy(screenOffPitch = ScreenOffPitch.entries[i]) } }
    Text(
        when (settings.screenOffPitch) {
            ScreenOffPitch.SAME -> "Pitch and speed exactly as set, with the engine you chose."
            ScreenOffPitch.LIGHTER -> "The lighter Rubber Band engine: about half the processing, slightly less polished. " +
                "(Natural voices always uses the full engine.)"
            ScreenOffPitch.LIGHTEST -> "Lighter, and speed changes at original pitch use Sonic, Android's own simple speed changer: " +
                "the least processing of all, can sound a little choppy on music."
            ScreenOffPitch.OFF -> "Original pitch and speed while the screen is off."
        },
        color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp),
    )

    val format = remember {
        val attrs = AudioEngine.attributes.platformAudioAttributes
        (PreRender.codecFor(context, 48000, attrs) ?: PreRender.codecFor(context, 44100, attrs))?.name
    }
    SwitchRow(
        "Pre-render", settings.preRender, { on -> AudioEngine.updateSettings { it.copy(preRender = on) } },
        sub = "While a song plays with the screen off or while you're in another app, the next one is worked out ahead with its exact EQ and pitch " +
            "(a few seconds of work) into a high-quality file the audio chip plays by itself, so the processor sleeps " +
            "through the song. Uses up to 300 MB of cache, cleared automatically. A changed speed is never baked in: " +
            (if (AudioEngine.chipSpeed) "the audio chip changes it itself. " else "those songs stay live. ") +
            if (format != null) "This phone's audio chip takes $format." else "This phone's audio chip doesn't take any format LToTS can make here, so this has no effect.",
        enabled = settings.directPlayback,
    )
    Text(
        (if (AudioEngine.chipSpeed) "This phone's audio chip changes speed by itself, so a speed change at original pitch needs no processing. "
        else "This phone's audio chip can't change speed by itself, so speed changes are processed by LToTS. ") +
            (if (active) "Screen-off modes are in effect right now." else if (!settings.directPlayback) "Pre-render needs Power-saving playback (below) to be on." else ""),
        color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp),
    )
}

/** Profile > App settings · sleep timer. */
@OptIn(ExperimentalLayoutApi::class)
@UnstableApi
@Composable
internal fun SleepTimerSettings() {
    val pal = LocalPalette.current
    val context = LocalContext.current
    val settings by AudioEngine.settings.collectAsState()
    SectionTitle("App settings · sleep timer")
    Text("Timer from the notification", color = pal.text, style = MaterialTheme.typography.bodyMedium)
    Text(
        "The moon in the notification always starts this length: ${minutesLabel(settings.sleepMinutes)}. Tap it again to stop. " +
            "The moon on the Playing tab lets you pick each time.",
        color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp),
    )
    FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        settings.sleepPresets.forEach { m ->
            Pill(minutesLabel(m), m == settings.sleepMinutes, { AudioEngine.updateSettings { it.copy(sleepMinutes = m) } })
        }
    }
    var editing by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Custom length", false, { custom = true })
        Pill("Edit lengths", false, { editing = true })
    }
    if (custom) CustomMinutesDialog(onDismiss = { custom = false }) { m ->
        AudioEngine.updateSettings { s -> s.copy(sleepPresets = (s.sleepPresets + m).distinct().sorted().takeLast(8).let { l -> if (m in l) l else (l.drop(1) + m).sorted() }, sleepMinutes = m) }
        custom = false
    }
    if (editing) EditPresetsDialog(settings.sleepPresets, onDismiss = { editing = false }) { list ->
        AudioEngine.updateSettings { s -> s.copy(sleepPresets = list, sleepMinutes = if (s.sleepMinutes in list) s.sleepMinutes else list.first()) }
        editing = false
    }
    SwitchRow(
        "Fade out", settings.sleepFade, { on -> AudioEngine.updateSettings { it.copy(sleepFade = on) } },
        sub = "The music gets gently quieter at the end instead of stopping dead.",
    )
    if (settings.sleepFade) {
        LabeledSlider(
            "Fade length", settings.sleepFadeSeconds.toFloat(),
            { v -> AudioEngine.updateSettings { it.copy(sleepFadeSeconds = v.roundToInt()) } },
            5f..120f, "${settings.sleepFadeSeconds} s",
        )
    }
    SwitchRow(
        "Finish the song", settings.sleepFinishSong, { on -> AudioEngine.updateSettings { it.copy(sleepFinishSong = on) } },
        sub = "When the time is up, the song that's playing plays to its end first.",
    )
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var precise by remember { mutableStateOf(SleepTimer.preciseAllowed(context)) }
    // Re-checked when you come back from Android's settings.
    LaunchedEffect(lifecycle) {
        lifecycle.currentStateFlow.collect { if (it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) precise = SleepTimer.preciseAllowed(context) }
    }
    SwitchRow(
        "Precise timing", precise, {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        sub = if (precise) "Allowed: the timer stops the music on the dot, even while the processor sleeps."
        else "Android asks you to allow this. Without it, the timer can run a few minutes late while the audio chip " +
            "plays by itself (the processor is asleep then). Tap to open the setting.",
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun EditPresetsDialog(current: List<Int>, onDismiss: () -> Unit, onSave: (List<Int>) -> Unit) {
    var text by remember { mutableStateOf(current.joinToString(", ")) }
    val parsed = text.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..600 }.distinct().sorted().take(8)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer lengths") },
        text = {
            Column {
                Text("Minutes, separated by commas (up to 8).", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    text, { text = it }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton({ onSave(parsed) }, enabled = parsed.isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CustomMinutesDialog(onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val minutes = text.trim().toIntOrNull()?.takeIf { it in 1..600 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Timer length") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                text, { text = it }, singleLine = true, label = { Text("Minutes (1–600)") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            )
        },
        confirmButton = { TextButton({ minutes?.let(onSave) }, enabled = minutes != null) { Text("Use") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** Profile > App settings · fading from one song into the next. */
@UnstableApi
@Composable
internal fun CrossfadeSettings() {
    val settings by AudioEngine.settings.collectAsState()
    SectionTitle("App settings · between songs")
    SwitchRow(
        "Fade into the next song", settings.crossfade, { on -> AudioEngine.updateSettings { it.copy(crossfade = on) } },
        sub = "The song fades out while the next one fades in over it. Power-saving playback and Bit-perfect USB can't overlap two songs: " +
            "Power-saving pauses while this is on, and with Bit-perfect USB songs don't fade.",
    )
    if (settings.crossfade) LabeledSlider(
        "Fade length", settings.crossfadeSeconds.toFloat(),
        { v -> AudioEngine.updateSettings { it.copy(crossfadeSeconds = v.roundToInt()) } },
        1f..12f, "${settings.crossfadeSeconds} s", steps = 10,
    )
}

private fun minutesLabel(m: Int): String = when {
    m % 60 == 0 -> "${m / 60} h"
    m > 60 -> "${m / 60} h ${m % 60}"
    else -> "$m min"
}

private fun leftLabel(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The moon on the Playing tab: opens the sleep timer; filled (in the accent colour) while it runs. */
@UnstableApi
@Composable
internal fun SleepTimerButton(size: Dp, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val state by SleepTimer.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    com.ltots.app.ui.glass.GlassCircleButton({ open = true }, modifier.size(size), highlighted = state != null) {
        Icon(
            if (state != null) Icons.Rounded.Bedtime else Icons.Outlined.Bedtime, "Sleep timer",
            tint = if (state != null) pal.accent else pal.text, modifier = Modifier.size(size * 0.52f),
        )
    }
    if (open) SleepTimerDialog { open = false }
}

@OptIn(ExperimentalLayoutApi::class)
@UnstableApi
@Composable
private fun SleepTimerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings by AudioEngine.settings.collectAsState()
    val state by SleepTimer.state.collectAsState()
    // The time left, counted only while this is open.
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    com.ltots.app.ui.WhileOnScreen(state) {
        while (state != null) {
            now = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(1000)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Bedtime, null) },
        title = { Text("Sleep timer") },
        text = {
            Column {
                val s = state
                if (s != null) {
                    Text(
                        when {
                            s.fading -> "Fading out…"
                            s.finishing -> "Stopping at the end of this song (${leftLabel(s.endsAt - now)})"
                            else -> "Stops in ${leftLabel(s.endsAt - now)}"
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Start again for:", style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    settings.sleepPresets.forEach { m ->
                        Pill(minutesLabel(m), false, { SleepTimer.start(context, m); onDismiss() })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    listOfNotNull(
                        if (settings.sleepFade) "Fades out over ${settings.sleepFadeSeconds} s" else "Stops without fading",
                        if (settings.sleepFinishSong) "lets the song finish" else null,
                    ).joinToString(", ") + ". Change this in Profile > App settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
        dismissButton = if (state != null) ({ TextButton({ SleepTimer.cancel(); onDismiss() }) { Text("Turn off") } }) else null,
    )
}

/** Profile: how efficiently the last listening session played (Profile > App settings > Battery card). */
@UnstableApi
@Composable
internal fun BatteryStatsCard() {
    val pal = LocalPalette.current
    val settings by AudioEngine.settings.collectAsState()
    if (!settings.batteryStats) return
    val session by PowerStats.session.collectAsState()
    val s = session ?: return
    if (s.playedMs < 60_000L) return
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Last listening · battery")
            Text(
                "${durationLabel(s.playedMs)} played, ${pct(s.screenOffMs.toFloat() / s.playedMs)} of it with the screen off.",
                color = pal.text, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(10.dp))
            ShareBar(s.directShare, s.renderedShare, s.processedShare)
            Spacer(Modifier.height(8.dp))
            Legend(pal.accent, "Audio chip by itself", s.directShare)
            Legend(pal.accent.copy(alpha = 0.55f), "Pre-rendered (chip by itself)", s.renderedShare)
            Legend(pal.text.copy(alpha = 0.35f), "Processed by LToTS", s.processedShare)
            Spacer(Modifier.height(8.dp))
            val rate = s.mAhPerHour
            Text(
                when {
                    s.charged -> "The phone charged during this session, so there's no battery figure."
                    rate != null -> "The whole phone used about ${rate.roundToInt()} mAh per hour of listening" +
                        (if (s.batteryStart >= 0 && s.batteryNow >= 0) " (${s.batteryStart}% → ${s.batteryNow}%)." else ".")
                    else -> "Battery use shows after a few more minutes of listening off the charger."
                } + if (s.renders > 0) " ${s.renders} song${if (s.renders == 1) "" else "s"} pre-rendered in ${"%.1f".format(s.renderMs / 1000f)} s of processing." else "",
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ShareBar(a: Float, b: Float, c: Float) {
    val pal = LocalPalette.current
    val total = (a + b + c).takeIf { it > 0f } ?: 1f
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))) {
        if (a > 0f) Box(Modifier.weight(a / total).height(10.dp).background(pal.accent))
        if (b > 0f) Box(Modifier.weight(b / total).height(10.dp).background(pal.accent.copy(alpha = 0.55f)))
        if (c > 0f) Box(Modifier.weight(c / total).height(10.dp).background(pal.text.copy(alpha = 0.35f)))
    }
}


@Composable
private fun Legend(color: Color, label: String, share: Float) {
    val pal = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(pct(share), color = pal.text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun pct(f: Float) = "${(f * 100).roundToInt()}%"

private fun durationLabel(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
