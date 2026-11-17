package com.ltots.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.ltots.app.rooms.Rooms
import com.ltots.app.ui.bottomChrome
import com.ltots.app.ui.components.LabeledSlider
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.components.SegmentedToggle
import com.ltots.app.ui.components.SwitchRow
import com.ltots.app.ui.glass.GlassCircleButton
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.topInset
import java.util.Locale

/**
 * Rooms: your phones playing as one (see [Rooms]). Opened from Sound (the output chip on the
 * Playing tab) and from the Profile tab.
 */
@UnstableApi
@Composable
fun RoomsScreen(onBack: () -> Unit) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { Rooms.init(context) }
    val ui by Rooms.ui.collectAsState()

    // What a room needs from Android, asked for when you start or join one.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.invoke()
        pending = null
    }
    fun withRoomPermissions(then: () -> Unit) {
        val need = listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.POST_NOTIFICATIONS)
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) then() else {
            pending = then
            perms.launch(need.toTypedArray())
        }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) Rooms.calibrate() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topInset()).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassCircleButton(onBack, Modifier.size(40.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = pal.text, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Rooms", style = MaterialTheme.typography.displaySmall, color = pal.text)
                Text("Your phones playing as one.", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(14.dp))

        if (ui.role == Rooms.Role.OFF) {
            OffCard(
                available = ui.available,
                onStart = { withRoomPermissions { Rooms.startHere(context) } },
                onAvailable = { on -> if (on) withRoomPermissions { Rooms.setAvailable(context, true) } else Rooms.setAvailable(context, false) },
            )
        } else {
            if (ui.status.isNotBlank()) {
                Text(ui.status, color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            }
            if (ui.members.isNotEmpty()) NowPlayingCard(ui)
            ModeCard(ui)
            Spacer(Modifier.height(12.dp))
            if (ui.mode == Rooms.Mode.EARBUDS) EarbudsCard(ui) else SpeakersCard(ui) {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) Rooms.calibrate()
                else mic.launch(Manifest.permission.RECORD_AUDIO)
            }
            Spacer(Modifier.height(12.dp))
            PhonesCard(ui)
            Spacer(Modifier.height(12.dp))
            Pill(if (ui.role == Rooms.Role.MAIN) "End the room" else "Leave the room", false, { Rooms.leave() })
        }

        if (ui.nearby.isNotEmpty() && ui.role != Rooms.Role.OFF) {
            Spacer(Modifier.height(12.dp))
            NearbyCard(ui)
        }
        if (ui.role != Rooms.Role.OFF) {
            Spacer(Modifier.height(12.dp))
            AddressCard(ui)
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "Every phone needs LToTS open once with Rooms on (Start a room here, or Join rooms on this Wi-Fi), and all " +
                "of them on the same Wi-Fi. The first time two phones meet they show the same 4-digit code: confirm it on both.",
            color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(bottomChrome()))
    }
}

@Composable
private fun OffCard(available: Boolean, onStart: () -> Unit, onAvailable: (Boolean) -> Unit) {
    val pal = LocalPalette.current
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Start")
            Text(
                "One phone is the main: it plays your queue with your EQ, and sends the sound to the others over the Wi-Fi.\n\n" +
                    "Earbuds: your earbuds stay connected to two phones (multipoint). As you walk round, the phone with the " +
                    "better link plays to them and the other takes over when it weakens, at the same point in the song.\n\n" +
                    "Speakers: every phone plays to its own speaker, all in sync, like JBL's own speaker linking but across " +
                    "speaker generations.",
                color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp),
            )
            Pill("Start a room here", true, onStart)
            Spacer(Modifier.height(8.dp))
            SwitchRow(
                "Join rooms on this Wi-Fi", available, onAvailable,
                sub = "This phone waits in the background and joins your room by itself (it relays the sound).",
            )
        }
    }
}

@UnstableApi
@Composable
private fun NowPlayingCard(ui: Rooms.Ui) {
    val pal = LocalPalette.current
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    ui.nowTitle.ifBlank { "Nothing playing" }, color = pal.text, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(ui.nowArtist, if (ui.mainName.isNotBlank()) "on ${ui.mainName}" else "").filter { it.isNotBlank() }.joinToString(" · "),
                    color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            GlassCircleButton({ Rooms.transport("prev") }, Modifier.size(40.dp)) {
                Icon(Icons.Rounded.SkipPrevious, "Previous", tint = pal.text, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(6.dp))
            GlassCircleButton({ Rooms.transport("toggle") }, Modifier.size(46.dp), highlighted = true) {
                Icon(if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (ui.playing) "Pause" else "Play", tint = pal.onAccent, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(6.dp))
            GlassCircleButton({ Rooms.transport("next") }, Modifier.size(40.dp)) {
                Icon(Icons.Rounded.SkipNext, "Next", tint = pal.text, modifier = Modifier.size(22.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@UnstableApi
@Composable
private fun ModeCard(ui: Rooms.Ui) {
    val pal = LocalPalette.current
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Mode")
            SegmentedToggle(listOf("Earbuds", "Speakers"), if (ui.mode == Rooms.Mode.EARBUDS) 0 else 1) { i ->
                Rooms.setMode(if (i == 0) Rooms.Mode.EARBUDS else Rooms.Mode.SPEAKERS)
            }
            Text(
                (if (ui.mode == Rooms.Mode.EARBUDS) "One phone at a time sends to your earbuds: the one with the better link."
                else "Every phone plays to its own speaker, in sync.") +
                    (if (ui.delayMs > 0) " The music is sent ${ui.delayMs} ms ahead, enough for the slowest phone's network and Bluetooth; it adjusts by itself." else ""),
                color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@UnstableApi
@Composable
private fun EarbudsCard(ui: Rooms.Ui) {
    val pal = LocalPalette.current
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Earbuds")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Headphones, null, tint = pal.accent, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(ui.earbudsName.ifBlank { "No earbuds chosen yet" }, color = pal.text, fontWeight = FontWeight.SemiBold)
                    val holder = ui.members.firstOrNull { it.active }
                    Text(
                        when {
                            holder != null -> "Playing from ${holder.name}" + if (ui.lastSwitch.isNotBlank()) " · switched: ${ui.lastSwitch}" else ""
                            ui.members.none { it.earbuds } -> "Not connected to any phone in the room"
                            else -> "Waiting for music"
                        },
                        color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (ui.earbudChoices.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Follow these earbuds", color = pal.textFaint, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.earbudChoices.forEach { (name, address) ->
                        Pill(name, address.equals(ui.earbudsAddress, ignoreCase = true), { Rooms.setEarbuds(name, address) })
                    }
                }
            }
            Text(
                "Turn on multipoint (connect to 2 devices) in the Sony app and connect the earbuds to both phones. " +
                    "The phone playing watches its link; when it gets weak or the music starts to stall, the other one takes over.",
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@UnstableApi
@Composable
private fun SpeakersCard(ui: Rooms.Ui, onCalibrate: () -> Unit) {
    val pal = LocalPalette.current
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Sync")
            Text(
                "Bluetooth speakers each add their own delay, and older and newer JBLs differ. Calibrate measures it: hold this " +
                    "phone where you listen, and every speaker gives a short whistle in turn (the music pauses for a few seconds).",
                color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp),
            )
            Pill(if (ui.calibrating) "Calibrating…" else "Calibrate with this phone's mic", false, onCalibrate, enabled = !ui.calibrating)
            if (ui.calibration.isNotBlank()) {
                Text(ui.calibration, color = pal.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

@UnstableApi
@Composable
private fun PhonesCard(ui: Rooms.Ui) {
    val pal = LocalPalette.current
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Phones")
            if (ui.members.isEmpty()) {
                Text("No room yet.", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
            }
            ui.members.forEachIndexed { i, m ->
                if (i > 0) Spacer(Modifier.height(12.dp))
                MemberRow(ui, m)
            }
        }
    }
}

@UnstableApi
@Composable
private fun MemberRow(ui: Rooms.Ui, m: Rooms.Member) {
    val pal = LocalPalette.current
    val good = Color(0xFF6CFFB0)
    val warn = Color(0xFFFFC14D)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (m.isMain) Icons.Rounded.Star else if (ui.mode == Rooms.Mode.EARBUDS) Icons.Rounded.Headphones else Icons.Rounded.Speaker,
                null, tint = if (m.active || (ui.mode == Rooms.Mode.SPEAKERS && m.rendering)) pal.accent else pal.textDim,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    m.name + (if (m.isMe) " (this phone)" else "") + (if (m.isMain) " · main" else ""),
                    color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val parts = ArrayList<String>()
                parts += m.output.ifBlank { "—" }
                if (m.tailscale) parts += "via Tailscale"
                m.latencyMs?.takeIf { it > 0 }?.let { parts += "output ${"%.0f".format(Locale.ROOT, it)} ms" }
                m.marginMs?.let { if (it < 0) parts += "music late by ${"%.0f".format(Locale.ROOT, -it)} ms" }
                parts += when {
                    !m.clockReady -> "syncing clock"
                    m.rendering -> "playing" + (m.syncMs?.let { " · ${"%+.1f".format(Locale.ROOT, it)} ms" } ?: "")
                    else -> "ready"
                }
                Text(parts.joinToString(" · "), color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                if (ui.mode == Rooms.Mode.EARBUDS) {
                    val signal = when {
                        !m.earbuds -> "Earbuds not connected"
                        m.rssi != null -> "Earbuds: ${m.rssi} dBm (${m.rssiSource})" + if (m.stall >= 0.5) " · stalling" else ""
                        else -> "Earbuds connected · signal not measurable here" + if (m.stall >= 0.5) " · stalling" else ""
                    }
                    Text(signal, color = if (!m.earbuds) pal.textFaint else if (m.stall >= 0.5 || (m.rssi ?: 0) <= -84) warn else good,
                        style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.padding(start = 32.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.mode == Rooms.Mode.EARBUDS && m.earbuds && !m.active) Pill("Play to earbuds from here", false, { Rooms.earbudsHere(m.id) })
            if (m.isMe && !m.isMain && ui.role == Rooms.Role.NODE) Pill(if (ui.handingOver) "Taking over…" else "Make this main", false, { Rooms.makeMeMain() }, enabled = !ui.handingOver)
        }
        if (ui.mode == Rooms.Mode.SPEAKERS) {
            Column(Modifier.padding(start = 32.dp)) {
                SwitchRow("Plays", m.plays, { Rooms.setPlays(m.id, it) })
                if (m.plays) OutputsPicker(m)
            }
        }
    }
}

/**
 * Which outputs a phone plays to in Speakers mode: its usual one, or any it has — its speaker,
 * wired or USB, each Bluetooth device — several at once where the phone allows it, each lined
 * up on its own with its own delay slider.
 */
@UnstableApi
@Composable
private fun OutputsPicker(m: Rooms.Member) {
    val pal = LocalPalette.current
    val warn = Color(0xFFFFC14D)
    if (m.outputs.isNotEmpty()) {
        Text("Plays to", color = pal.textFaint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Automatic", m.selected.isEmpty(), { Rooms.setOutputs(m.id, emptyList()) })
            m.outputs.forEach { o ->
                val on = o.key in m.selected
                Pill(o.name, on, {
                    Rooms.setOutputs(m.id, if (on) m.selected - o.key else m.selected + o.key)
                })
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    m.sinks.forEach { k ->
        Column(Modifier.padding(top = 6.dp)) {
            Text(
                k.name + if (k.rendering) " · ${"%.0f".format(Locale.ROOT, k.latencyMs)} ms output · ${"%+.1f".format(Locale.ROOT, k.syncMs)} ms" else " · ready",
                color = pal.textDim, style = MaterialTheme.typography.bodySmall,
            )
            if (!k.routedOk) {
                Text(
                    "This phone can't play to ${k.name} together with its other outputs" +
                        (if (k.routedTo.isNotBlank()) " (Android sent it to ${k.routedTo})" else "") + ". Pick fewer outputs on this phone.",
                    color = warn, style = MaterialTheme.typography.bodySmall,
                )
            }
            var adj by remember(m.id, k.key, k.adjustMs) { mutableFloatStateOf(k.adjustMs.toFloat()) }
            LabeledSlider(
                "Delay", adj, { adj = it }, -300f..300f,
                "${"%+.0f".format(Locale.ROOT, adj)} ms",
                onReset = { adj = 0f; Rooms.setAdjust(m.id, 0.0, k.key) }, isDefault = kotlin.math.abs(adj) < 0.5f,
                onChangeFinished = { Rooms.setAdjust(m.id, adj.toDouble(), k.key) },
            )
        }
    }
}

@UnstableApi
@Composable
private fun NearbyCard(ui: Rooms.Ui) {
    val pal = LocalPalette.current
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("On this Wi-Fi")
            ui.nearby.forEachIndexed { i, n ->
                if (i > 0) Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n.name, color = pal.text, fontWeight = FontWeight.SemiBold)
                        Text(
                            (if (n.isMain) "Has a room" else "Ready to join") + if (n.trusted) "" else " · not paired yet",
                            color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val canJoin = (ui.role == Rooms.Role.NODE && n.isMain) || (ui.role == Rooms.Role.MAIN && !n.isMain)
                    if (canJoin) Pill(if (ui.role == Rooms.Role.MAIN) "Add" else "Join", false, { Rooms.connectTo(n.id) })
                }
            }
        }
    }
}

/** The 4-digit code when two phones meet for the first time (shown over any page). */
@UnstableApi
@Composable
fun RoomPairingDialog() {
    val prompt by Rooms.pairing.collectAsState()
    val p = prompt ?: return
    val pal = LocalPalette.current
    AlertDialog(
        onDismissRequest = { Rooms.answerPairing(false) },
        title = { Text("Pair with ${p.peerName}?") },
        text = {
            Column {
                Text("Check that ${p.peerName} shows the same code:", color = pal.textDim)
                Spacer(Modifier.height(12.dp))
                Text(p.code, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp, color = pal.text)
            }
        },
        confirmButton = { TextButton({ Rooms.answerPairing(true) }) { Text("They match") } },
        dismissButton = { TextButton({ Rooms.answerPairing(false) }) { Text("Cancel") } },
    )
}

/** Connecting over Tailscale (or any network where phones can't find each other by themselves). */
@UnstableApi
@Composable
private fun AddressCard(ui: Rooms.Ui) {
    val pal = LocalPalette.current
    var text by remember { mutableStateOf("") }
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.06f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Connect by address")
            val mine = ui.myAddresses.joinToString("\n") { (label, addr) ->
                "$label: $addr" + if (ui.port != 0 && ui.port != com.ltots.app.rooms.RoomLink.PORT) ":${ui.port}" else ""
            }
            Text(
                "Over Tailscale the phones can't find each other by themselves: type the other phone's Tailscale address " +
                    "(100.x.y.z or its MagicDNS name) here once. After that they reconnect by themselves." +
                    if (mine.isNotBlank()) "\n\nThis phone:\n$mine" else "",
                color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    text, { text = it }, Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("100.x.y.z", color = pal.textFaint) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = pal.text),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { Rooms.connectByAddress(text) }),
                )
                Spacer(Modifier.width(8.dp))
                Pill("Connect", false, { Rooms.connectByAddress(text) }, enabled = text.isNotBlank())
            }
            if (ui.connectError.isNotBlank()) {
                Text(ui.connectError, color = Color(0xFFFFC14D), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
