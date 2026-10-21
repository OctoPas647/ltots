package com.ltots.app.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ltots.app.audio.AudioEngine
import com.ltots.app.audio.BiquadCoefs
import com.ltots.app.audio.EqDefaults
import com.ltots.app.audio.EqMath
import com.ltots.app.audio.EqSmooth
import com.ltots.app.audio.EqState
import com.ltots.app.audio.EqTone
import com.ltots.app.data.EqProfiles
import com.ltots.app.data.ThemeConfig
import com.ltots.app.data.Users
import com.ltots.app.profiles.ProfileTransfer
import com.ltots.app.profiles.UserSwitch
import com.ltots.app.ui.bottomChrome
import com.ltots.app.ui.components.DeleteDialog
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.RenameDialog
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.components.SwitchRow
import com.ltots.app.ui.components.formatDb
import com.ltots.app.ui.components.formatFreq
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.ThemeState
import com.ltots.app.ui.theme.toColor
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.pow

/**
 * Profile: who is using the app, their Look and EQ profiles (with what each holds), and the
 * settings of the app itself. The Look and EQ tabs edit the profile in use; this tab manages the
 * profiles: switch, add, rename, delete, export and import.
 */
@Composable
fun ProfileScreen() {
    val pal = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val users by Users.users.collectAsState()
    val activeId by Users.active.collectAsState()
    val me = users.firstOrNull { it.id == activeId }

    // One "save as" and one "open" for every export and import on this page.
    var exportText by remember { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = exportText
        exportText = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = ProfileTransfer.write(context, uri, text)
            Toast.makeText(context, if (ok) "Exported." else "Couldn't save the file there.", Toast.LENGTH_SHORT).show()
        }
    }
    fun export(fileName: String, text: () -> String) {
        exportText = runCatching(text).getOrNull() ?: return
        runCatching { exporter.launch(fileName) }.onFailure {
            Toast.makeText(context, "No app to save files with.", Toast.LENGTH_SHORT).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = ProfileTransfer.read(context, uri)
            val msg = if (text == null) "Couldn't read that file." else ProfileTransfer.message(ProfileTransfer.import(context, text))
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }
    fun import() {
        runCatching { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }.onFailure {
            Toast.makeText(context, "No app to open files with.", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Profile", style = MaterialTheme.typography.displaySmall, color = pal.text)
                Text(
                    (me?.name ?: "") + " · looks, EQs and app settings",
                    color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Avatar(me?.name ?: "?", active = true, size = 44)
        }
        Spacer(Modifier.height(14.dp))

        UsersCard(onExport = { id, name -> export(ProfileTransfer.fileName("user", name)) { ProfileTransfer.userJson(context, id) } }, onImport = ::import)
        Spacer(Modifier.height(12.dp))
        LookProfilesCard(
            onExport = { name, look -> export(ProfileTransfer.fileName("look", name)) { ProfileTransfer.lookJson(context, name, look) } },
            onImport = ::import,
        )
        Spacer(Modifier.height(12.dp))
        EqProfilesCard(
            onExport = { name, eq -> export(ProfileTransfer.fileName("EQ", name)) { ProfileTransfer.eqJson(name, eq) } },
            onImport = ::import,
        )
        Spacer(Modifier.height(12.dp))
        AppSettingsCard()

        Spacer(Modifier.height(12.dp))
        Text(
            "LToTS ${appVersion(context)}. Exported profiles are small files you can keep, send or import on another phone: " +
                "a look profile, an EQ profile, or a whole user with all of theirs. Any Import button takes any of them.",
            color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(bottomChrome()))
    }
}

// ---- users ---------------------------------------------------------------------------------

@Composable
private fun UsersCard(onExport: (id: String, name: String) -> Unit, onImport: () -> Unit) {
    val pal = LocalPalette.current
    val users by Users.users.collectAsState()
    val activeId by Users.active.collectAsState()
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(16.dp)) {
            CardHeader("Users", "${users.size} of ${Users.MAX}", onImport)
            Text(
                "Everyone has their own looks, EQs, settings and each song's own sound. Tap someone to switch.",
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
            )
            users.forEach { u ->
                val on = u.id == activeId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(if (on) pal.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable(enabled = !on) { UserSwitch.switchTo(u.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(u.name, active = on, size = 36)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(u.name, color = if (on) pal.accent else pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (on) "In use" else "Tap to switch", color = pal.textFaint, style = MaterialTheme.typography.bodySmall)
                    }
                    SmallAction(Icons.Rounded.Edit, "Rename ${u.name}") { renaming = u.id }
                    SmallAction(Icons.Rounded.FileUpload, "Export ${u.name}") { onExport(u.id, u.name) }
                    if (users.size > 1) SmallAction(Icons.Rounded.Delete, "Delete ${u.name}") { deleting = u.id }
                }
            }
            if (users.size < Users.MAX) AddRow("Add user") { Users.add() }
        }
    }
    renaming?.let { id ->
        RenameDialog("Rename user", users.firstOrNull { it.id == id }?.name ?: "", onDismiss = { renaming = null }) { Users.rename(id, it) }
    }
    deleting?.let { id ->
        val name = users.firstOrNull { it.id == id }?.name ?: ""
        DeleteDialog(
            "Delete \"$name\"?",
            "Their looks, EQs, settings and every song's own sound are removed. Export them first to keep a copy. Can't be undone.",
            onDismiss = { deleting = null },
        ) { UserSwitch.delete(id) }
    }
}

/** A round badge with the first letter of a name. */
@Composable
private fun Avatar(name: String, active: Boolean, size: Int) {
    val pal = LocalPalette.current
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(if (active) pal.accent else pal.text.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            color = if (active) pal.onAccent else pal.text, fontWeight = FontWeight.Bold,
            style = if (size >= 40) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
        )
    }
}

// ---- look profiles ---------------------------------------------------------------------------

@Composable
private fun LookProfilesCard(onExport: (String, ThemeConfig) -> Unit, onImport: () -> Unit) {
    val pal = LocalPalette.current
    val profiles by ThemeState.profiles.collectAsState()
    val active by ThemeState.activeProfile.collectAsState()
    val cfg by ThemeState.config.collectAsState()
    var expanded by rememberSaveable { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf<Int?>(null) }
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(16.dp)) {
            CardHeader("Look profiles", "${profiles.size} of ${ThemeState.MAX_PROFILES}", onImport)
            Text(
                "Change a look on the Look tab; it saves into the one in use. Tap one here to see what it holds.",
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
            )
            profiles.forEachIndexed { i, p ->
                val inUse = i == active
                val look = if (inUse) cfg else p.look
                ProfileCardRow(
                    name = p.name,
                    status = (if (inUse) "In use · " else "") + ThemeState.themeName(look),
                    inUse = inUse,
                    open = expanded == i,
                    onToggle = { expanded = if (expanded == i) null else i },
                    leading = { LookDot(look) },
                ) {
                    LookValues(look)
                    FlowPills {
                        if (!inUse) Pill("Use", true, { ThemeState.selectProfile(i) })
                        Pill("Rename", false, { renaming = i })
                        Pill("Export", false, { onExport(p.name, look) })
                        if (profiles.size > 1) Pill("Delete", false, { deleting = i })
                    }
                }
            }
            if (profiles.size < ThemeState.MAX_PROFILES) AddRow("New look profile (a copy of the one in use)") { ThemeState.addProfile() }
        }
    }
    renaming?.let { i ->
        RenameDialog("Rename look profile", profiles.getOrNull(i)?.name ?: "", onDismiss = { renaming = null }) { ThemeState.renameProfile(i, it) }
    }
    deleting?.let { i ->
        val name = profiles.getOrNull(i)?.name ?: ""
        DeleteDialog("Delete \"$name\"?", "This look profile and its settings are removed. Can't be undone.", onDismiss = { deleting = null }) {
            ThemeState.deleteProfile(i)
            expanded = null
        }
    }
}

/** What a look holds, in a few short lines. */
@Composable
private fun LookValues(t: ThemeConfig) {
    val visual = when {
        t.iconMode -> "Icon"
        t.spectrumAroundCover -> "Around the cover · " + t.ringLayout.label
        else -> "Behind the cover · " + t.behindLayout.label
    }
    Column(Modifier.padding(bottom = 8.dp)) {
        ValueRow("Theme", ThemeState.themeName(t))
        ValueRow("Accent", hex(t.accent), swatch = t.accent.toColor())
        if (!t.pureBlack) ValueRow("Glow", hex(t.glow), swatch = t.glow.toColor())
        ValueRow("Glass", "blur ${t.blurDp.toInt()} dp · refraction ${pct(t.refraction)} · frost ${pct(t.glassTint / 0.7f)} · rim ${pct(t.rimLight)}")
        ValueRow("Shape", "corners ${t.cornerDp.toInt()} dp · cover ${pct(t.coverOpacity)}")
        if (!t.pureBlack) ValueRow(
            "Background",
            (if (t.darkMode) "dark" else "light") + " · " + (if (t.animateBackground) "moving ${"%.1f".format(Locale.ROOT, t.animationSpeed)}×" else "still") +
                (if (t.artworkBackground) " · album art glow" else ""),
        )
        ValueRow("Visualizer", visual)
        ValueRow(
            "Style",
            t.spectrumStyle.label + " · strength ${pct(t.spectrumOpacity)} · " + (if (t.visualizerFps == 0) "off" else "${t.visualizerFps} fps") +
                " · ${t.analyzerBands} bars",
        )
        ValueRow(
            "Extras",
            listOfNotNull(
                "before-EQ outline".takeIf { t.showPreEqGhost },
                "freezes when music stops".takeIf { t.freezeVisualizer },
                "Gradient flow".takeIf { t.gradientFlow },
            ).joinToString(" · ").ifEmpty { "none" },
        )
    }
}

private fun hex(argb: Long) = "#%06X".format(Locale.ROOT, argb and 0xFFFFFF)
private fun pct(v: Float) = "%.0f%%".format(Locale.ROOT, v * 100)

// ---- EQ profiles -----------------------------------------------------------------------------

@Composable
private fun EqProfilesCard(onExport: (String, EqState) -> Unit, onImport: () -> Unit) {
    val pal = LocalPalette.current
    val items by EqProfiles.items.collectAsState()
    val active by EqProfiles.active.collectAsState()
    val eq by AudioEngine.effectiveEq.collectAsState()
    var expanded by rememberSaveable { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf<Int?>(null) }
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(16.dp)) {
            CardHeader("EQ profiles", "${items.size} of ${EqProfiles.MAX}", onImport)
            Text(
                "Saved EQs to load on the EQ tab. Loading one puts it into the EQ you're editing there (the default, or this song's).",
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
            )
            if (items.isEmpty()) {
                Text("None yet.", color = pal.textDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp))
            }
            items.forEachIndexed { i, p ->
                val inUse = i == active && EqTone.sameShape(p.eq, eq)
                ProfileCardRow(
                    name = p.name,
                    status = (if (inUse) "In use · " else "") + eqSummary(p.eq),
                    inUse = inUse,
                    open = expanded == i,
                    onToggle = { expanded = if (expanded == i) null else i },
                    leading = { EqThumb(p.eq) },
                ) {
                    EqValues(p.eq)
                    FlowPills {
                        if (!inUse) Pill("Load", true, {
                            EqProfiles.pick(i)?.let { e ->
                                AudioEngine.updateEq { e.copy(enabled = true, bands = e.bands.map { it.copy(id = EqDefaults.newId()) }) }
                            }
                        })
                        Pill("Rename", false, { renaming = i })
                        Pill("Export", false, { onExport(p.name, p.eq) })
                        Pill("Delete", false, { deleting = i })
                    }
                }
            }
            if (items.size < EqProfiles.MAX) AddRow("Save the current EQ as a new profile") { EqProfiles.addCurrent(eq) }
        }
    }
    renaming?.let { i ->
        RenameDialog("Rename EQ profile", items.getOrNull(i)?.name ?: "", onDismiss = { renaming = null }) { EqProfiles.rename(i, it) }
    }
    deleting?.let { i ->
        val name = items.getOrNull(i)?.name ?: ""
        DeleteDialog("Delete \"$name\"?", "This EQ profile is removed. The EQ you're using now stays as it is.", onDismiss = { deleting = null }) {
            EqProfiles.delete(i)
            expanded = null
        }
    }
}

private fun eqSummary(e: EqState): String =
    (if (e.enabled) "" else "off · ") + "${e.bands.size} band${if (e.bands.size == 1) "" else "s"}" +
        (if (e.preampDb != 0f) " · preamp ${formatDb(e.preampDb)}" else "")

@Composable
private fun EqValues(e: EqState) {
    val pal = LocalPalette.current
    Column(Modifier.padding(bottom = 8.dp)) {
        ValueRow("EQ", if (e.enabled) "on" else "off")
        ValueRow("Level", "preamp ${formatDb(e.preampDb)} · automatic headroom ${if (e.autoHeadroom) "on" else "off"} (${formatDb(-EqMath.peakBoostDb(e.copy(enabled = true)).toFloat())})")
        Spacer(Modifier.height(4.dp))
        FlowPills {
            e.bands.sortedBy { it.freqHz }.forEach { b ->
                Text(
                    formatFreq(b.freqHz) + " " + if (b.type.usesGain) formatDb(b.gainDb) else b.type.label,
                    color = if (b.enabled) pal.text else pal.textFaint, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(pal.text.copy(alpha = 0.07f)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** A tiny drawing of an EQ's curve, as it sounds. */
@Composable
private fun EqThumb(e: EqState) {
    val pal = LocalPalette.current
    val pts = remember(e) {
        val coefs = EqSmooth.filters(e).map { BiquadCoefs.design(it, 48000.0) }
        FloatArray(48) { i -> EqMath.responseDb(coefs, 20.0 * 1000.0.pow(i / 47.0), 48000.0).toFloat() }
    }
    val color = if (e.enabled) pal.accent else pal.textFaint
    Canvas(
        Modifier.size(44.dp, 28.dp).clip(RoundedCornerShape(8.dp)).background(pal.text.copy(alpha = 0.07f))
    ) {
        val mid = size.height / 2
        drawLine(pal.text.copy(alpha = 0.15f), Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1f)
        val path = Path()
        pts.forEachIndexed { i, db ->
            val x = size.width * i / (pts.size - 1f)
            val y = mid - (db.coerceIn(-12f, 12f) / 12f) * (mid - 3f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

// ---- app settings ----------------------------------------------------------------------------

/** Settings of the app itself (for the user in use): the screen, and sound quality & battery. */
@Composable
private fun AppSettingsCard() {
    val pal = LocalPalette.current
    val cfg by ThemeState.config.collectAsState()
    val settings by AudioEngine.settings.collectAsState()
    val direct by AudioEngine.direct.collectAsState()
    val batteryEq by AudioEngine.batteryEqActive.collectAsState()
    val phoneEq by AudioEngine.phoneEq.status.collectAsState()
    val bitPerfect by AudioEngine.output.bitPerfectStatus.collectAsState()
    fun set(t: (ThemeConfig) -> ThemeConfig) = ThemeState.update(t)

    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("App settings · screen")
            SwitchRow(
                "Highest refresh rate", cfg.maxRefresh, { on -> set { it.copy(maxRefresh = on) } },
                sub = "Runs LToTS at your screen's top refresh rate (120 Hz) so scrolling and animations are as smooth as they can be. " +
                    "Off lets the phone choose, which may be 60 Hz to save a little battery.",
            )
            SmoothnessSetting(cfg.effects) { m -> set { it.copy(effects = m) } }
            SwitchRow(
                "Hide navigation bar", cfg.hideNavBar, { on -> set { it.copy(hideNavBar = on) } },
                sub = "Swipe up from the bottom edge to bring it back for a moment.",
            )
            SwitchRow(
                "Hide status bar", cfg.hideStatusBar, { on -> set { it.copy(hideStatusBar = on) } },
                sub = "Clock and battery at the top. Swipe down from the top edge to see them.",
            )
            Text(
                "These stay the same whichever look profile is in use.",
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp),
            )

            SectionTitle("App settings · quality & battery")
            SwitchRow(
                "Power-saving playback", settings.directPlayback,
                { on -> AudioEngine.updateSettings { it.copy(directPlayback = on) } },
                sub = "When the EQ is off or flat and pitch and speed are original, the phone's audio chip plays " +
                    "the music by itself and the processor sleeps — the biggest battery saving there is. " +
                    "Switches back instantly when you change the EQ or pitch. " +
                    if (direct) "Active now." else "Not active right now (something needs processing, or the chip can't play this file's format).",
            )
            BatteryEqRow(settings, direct, batteryEq, phoneEq)
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
                sub = "Uses the lighter Rubber Band engine: about half the processing (and battery) while pitch or speed is changed, with slightly less polished sound. Nothing runs at original pitch and speed either way." +
                    if (settings.efficientPitch && settings.preserveFormants) " Not used while Natural voices is on: only the full engine keeps voices natural." else "",
            )
        }
    }
}

// ---- small parts ---------------------------------------------------------------------------

@Composable
private fun CardHeader(title: String, count: String, onImport: () -> Unit) {
    val pal = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(title, Modifier.weight(1f))
        Text(count, color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp, bottom = 4.dp))
        Row(
            Modifier.clip(RoundedCornerShape(50)).border(1.dp, pal.line, RoundedCornerShape(50))
                .clickable(onClick = onImport).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.FileDownload, null, tint = pal.text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Import", color = pal.text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A profile in a list: tap to open what it holds and what you can do with it. */
@Composable
private fun ProfileCardRow(
    name: String,
    status: String,
    inUse: Boolean,
    open: Boolean,
    onToggle: () -> Unit,
    leading: @Composable () -> Unit,
    details: @Composable () -> Unit,
) {
    val pal = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp))
            .background(if (inUse) pal.accent.copy(alpha = 0.12f) else if (open) pal.text.copy(alpha = 0.05f) else Color.Transparent)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = if (inUse) pal.accent else pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, color = pal.textFaint, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Close $name" else "Open $name", tint = pal.textDim)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) { details() }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String, swatch: Color? = null) {
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp))
        if (swatch != null) {
            Box(Modifier.padding(top = 2.dp, end = 6.dp).size(12.dp).clip(CircleShape).background(swatch))
        }
        Text(value, color = pal.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).border(1.5.dp, pal.accent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Add, null, tint = pal.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, color = pal.accent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    androidx.compose.material3.IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, description, tint = pal.textDim, modifier = Modifier.size(20.dp))
    }
}
