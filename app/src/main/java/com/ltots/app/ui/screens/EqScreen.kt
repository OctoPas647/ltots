package com.ltots.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import com.ltots.app.audio.EqTone
import com.ltots.app.audio.ToneRegion
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ltots.app.audio.AudioEngine
import com.ltots.app.audio.EqBand
import com.ltots.app.audio.EqDefaults
import com.ltots.app.audio.EqLimits
import com.ltots.app.audio.EqMath
import com.ltots.app.audio.EqState
import com.ltots.app.audio.FilterType
import com.ltots.app.data.EqPreset
import com.ltots.app.data.Store
import com.ltots.app.ui.bottomChrome
import com.ltots.app.ui.PlayerUi
import com.ltots.app.ui.components.LabeledSlider
import com.ltots.app.ui.components.LiveSpectrum
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.components.SwitchRow
import com.ltots.app.ui.components.formatDb
import com.ltots.app.ui.components.formatFreq
import com.ltots.app.ui.components.rememberLiveVisual
import com.ltots.app.ui.components.ProfileMenu
import com.ltots.app.ui.components.ProfileRow
import com.ltots.app.ui.components.customName
import com.ltots.app.ui.glass.GlassCircleButton
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.glass.glassShape
import com.ltots.app.ui.theme.LocalPalette
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

@Composable
fun EqScreen(player: PlayerUi) {
    val pal = LocalPalette.current
    val eq by AudioEngine.effectiveEq.collectAsState()
    val profile by AudioEngine.songProfile.collectAsState()
    val songKey by AudioEngine.songKey.collectAsState()
    val visual = rememberLiveVisual()
    var selectedId by remember { mutableStateOf<Long?>(null) }
    val selected = eq.bands.firstOrNull { it.id == selectedId }
    LaunchedEffect(eq.bands.map { it.id }) {
        if (eq.bands.none { it.id == selectedId }) selectedId = null
    }

    // Undo: remembers the EQ before each change (a slider drag counts as one change).
    val history = remember { androidx.compose.runtime.mutableStateListOf<EqState>() }
    var lastPush by remember { mutableStateOf(0L) }
    LaunchedEffect(songKey, profile?.eq != null) { history.clear() }
    fun edit(t: (EqState) -> EqState) {
        val now = System.currentTimeMillis()
        if (now - lastPush > 700) {
            history.add(AudioEngine.effectiveEq.value)
            if (history.size > 40) history.removeAt(0)
        }
        lastPush = now
        AudioEngine.updateEq(t)
    }
    fun undo() {
        val prev = history.removeLastOrNull() ?: return
        lastPush = 0L
        AudioEngine.updateEq { prev }
        selectedId = null
    }
    fun editBand(id: Long, t: (EqBand) -> EqBand) = edit { s -> s.copy(bands = s.bands.map { if (it.id == id) t(it) else it }) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        // Header
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Equalizer", style = MaterialTheme.typography.displaySmall, color = pal.text)
                Text(
                    if (player.hasMedia) player.title else "Plays on every song",
                    color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                )
            }
            com.ltots.app.ui.components.LSwitch(eq.enabled, { on -> edit { it.copy(enabled = on) } })
            Spacer(Modifier.width(10.dp))
            // Top right: your own EQ profiles (Custom 1-5), and reset (every gain back to 0 dB).
            EqProfiles(eq, onLoad = { p ->
                // Fresh ids so the processor rebuilds cleanly; loading a profile also switches the EQ on.
                edit { p.copy(enabled = true, bands = p.bands.map { it.copy(id = EqDefaults.newId()) }) }
                selectedId = null
            })
            Spacer(Modifier.width(8.dp))
            GlassCircleButton({ edit { EqTone.flatten(it) }; selectedId = null }, Modifier.size(40.dp)) {
                Icon(Icons.Rounded.RestartAlt, "Reset EQ: every gain back to 0 dB", tint = pal.text, modifier = Modifier.size(22.dp))
            }
        }

        // Scope: default EQ vs this song's own EQ
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val own = profile?.eq != null
            Pill("Default EQ", !own, { if (own) AudioEngine.useDefaultEqForSong() })
            Pill("This song", own, { if (!own) AudioEngine.makeEqSongSpecific() }.takeIf { songKey != null } ?: {})
            Spacer(Modifier.weight(1f))
            Text(
                if (own) "Saved to this song" else "Edits change the default",
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall,
            )
        }

        // 1. The EQ itself, first: the curve over the live spectrum + waveform. The moving parts
        // sit on top of the glass rather than inside it: the glass (blur, refraction) is then
        // drawn once and left alone, instead of being re-rendered with every frame of the spectrum.
        Spacer(Modifier.height(12.dp))
        val panelShape = glassShape()
        Box(Modifier.fillMaxWidth().height(340.dp)) {
            GlassPanel(Modifier.matchParentSize(), shape = panelShape, tintBoost = 0.02f) {}
            Box(Modifier.matchParentSize().clip(panelShape)) {
                LiveSpectrum(visual, Modifier.fillMaxSize().padding(bottom = 18.dp))
            }
            EqGraph(
                eq = eq,
                selectedId = selectedId,
                onSelect = { selectedId = it },
                onMove = { id, f, g -> editBand(id) { it.copy(freqHz = f, gainDb = g) } },
                onAdd = { f, g ->
                    if (eq.bands.size < EqLimits.MAX_BANDS) {
                        val b = EqBand(EqDefaults.newId(), freqHz = EqDefaults.roundFreq(f), gainDb = Math.round(g * 10f) / 10f, q = 1.2f)
                        edit { it.copy(bands = it.bands + b) }
                        selectedId = b.id
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            "Drag a knob up or down for gain · tap it to set its frequency below · double-tap empty space to add a band",
            color = pal.textFaint, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center,
        )

        // 2. The selected knob's controls, right under the graph: frequency, gain and width sliders.
        Spacer(Modifier.height(12.dp))
        if (selected != null) {
            BandEditor(
                band = selected,
                onChange = { nb -> editBand(selected.id) { nb } },
                onDelete = { edit { s -> s.copy(bands = s.bands.filterNot { it.id == selected.id }) }; selectedId = null },
            )
        } else {
            GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
                Text(
                    "Tap a knob on the graph (or a band below) to set its frequency, gain and width with sliders.",
                    color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
                )
            }
        }

        // 3. Fine-tune: always open, a main part of the page.
        Spacer(Modifier.height(20.dp))
        Text("Fine-tune", style = MaterialTheme.typography.titleLarge, color = pal.text)
        Text(
            "Bands, filter types, exact frequency / gain / width, preamp and headroom",
            color = pal.textDim, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        BandStrip(eq, selectedId, onSelect = { selectedId = it }, onCountChange = { n -> edit { setBandCount(it, n) } }, onRespace = { edit { respace(it) } })
        Spacer(Modifier.height(12.dp))
        LevelCard(eq) { edit(it) }

        // 4. Quick ways to a sound: presets and simple tone controls.
        Spacer(Modifier.height(20.dp))
        Text("Presets & tone", style = MaterialTheme.typography.titleLarge, color = pal.text)
        Spacer(Modifier.height(10.dp))
        PresetsCard(eq, onApply = { p ->
            // Fresh ids so the processor rebuilds cleanly; picking a preset also switches the EQ on.
            edit { cur -> p.eq.copy(enabled = true, bands = p.eq.bands.map { it.copy(id = EqDefaults.newId()) }) }
            selectedId = null
        })
        Spacer(Modifier.height(12.dp))
        QuickToneCard(
            eq,
            onSet = { r, v -> edit { EqTone.set(it, r, v) } },
            canUndo = history.isNotEmpty(),
            onUndo = ::undo,
            onFlat = { edit { EqTone.flatten(it) }; selectedId = null },
        )

        Spacer(Modifier.height(bottomChrome()))
    }
}

@Composable
private fun QuickToneCard(
    eq: EqState,
    onSet: (ToneRegion, Float) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onFlat: () -> Unit,
) {
    val pal = LocalPalette.current
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Quick tone", Modifier.weight(1f))
                Pill("Undo", false, { if (canUndo) onUndo() }, modifier = Modifier.alpha(if (canUndo) 1f else 0.4f))
                Spacer(Modifier.width(8.dp))
                Pill("Flatten", false, onFlat)
            }
            ToneRegion.entries.forEach { r ->
                val level = remember(eq, r) { EqTone.level(eq, r) }
                LabeledSlider(r.label, level, { v -> onSet(r, Math.round(v * 2f) / 2f) }, -EqTone.RANGE..EqTone.RANGE, formatDb(level))
            }
            Text(
                "Moves every band in that range together, on top of whatever preset or shape you have.",
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Add bands where the biggest gaps are, or remove the least important ones. */
private fun setBandCount(s: EqState, n: Int): EqState {
    val target = n.coerceIn(1, EqLimits.MAX_BANDS)
    var bands = s.bands.sortedBy { it.freqHz }
    while (bands.size < target) {
        val edges = listOf(20f) + bands.map { it.freqHz } + listOf(20000f)
        var bestI = 0; var bestGap = 0f
        for (i in 0 until edges.size - 1) {
            val gap = ln(edges[i + 1] / edges[i])
            if (gap > bestGap) { bestGap = gap; bestI = i }
        }
        val f = kotlin.math.sqrt(edges[bestI] * edges[bestI + 1])
        bands = (bands + EqBand(EqDefaults.newId(), freqHz = EqDefaults.roundFreq(f), q = 1.2f)).sortedBy { it.freqHz }
    }
    while (bands.size > target) {
        // Drop the band doing the least (smallest |gain|, disabled first).
        val victim = bands.minBy { (if (it.enabled) 1000f else 0f) + kotlin.math.abs(it.gainDb) }
        bands = bands - victim
    }
    return s.copy(bands = bands)
}

/** Re-space all bands evenly (log scale) and carry the current shape over to them. */
private fun respace(s: EqState): EqState {
    val n = s.bands.size.coerceAtLeast(1)
    val old = s.bands.filter { it.type.usesGain }.sortedBy { it.freqHz }
    val fresh = EqDefaults.spaced(n).map { b ->
        if (old.isEmpty()) return@map b
        val lf = ln(b.freqHz)
        val lo = old.lastOrNull { ln(it.freqHz) <= lf }
        val hi = old.firstOrNull { ln(it.freqHz) >= lf }
        val g = when {
            lo == null -> hi!!.gainDb
            hi == null -> lo.gainDb
            lo === hi -> lo.gainDb
            else -> {
                val t = (lf - ln(lo.freqHz)) / (ln(hi.freqHz) - ln(lo.freqHz))
                lo.gainDb + (hi.gainDb - lo.gainDb) * t
            }
        }
        b.copy(gainDb = Math.round(g * 10f) / 10f)
    }
    return s.copy(bands = fresh)
}

@Composable
private fun BandStrip(eq: EqState, selectedId: Long?, onSelect: (Long?) -> Unit, onCountChange: (Int) -> Unit, onRespace: () -> Unit) {
    val pal = LocalPalette.current
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Bands", Modifier.weight(1f))
                IconButton(onClick = { onCountChange(eq.bands.size - 1) }, Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Remove, "Fewer bands", tint = pal.text)
                }
                Text("${eq.bands.size}", color = pal.text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
                IconButton(onClick = { onCountChange(eq.bands.size + 1) }, Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Add, "More bands", tint = pal.text)
                }
                Spacer(Modifier.width(6.dp))
                Pill("Respace", false, onRespace)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                eq.bands.sortedBy { it.freqHz }.forEach { b ->
                    val sel = b.id == selectedId
                    Column(
                        Modifier.clip(RoundedCornerShape(14.dp))
                            .background(if (sel) pal.accent else pal.text.copy(alpha = 0.07f))
                            .clickable { onSelect(if (sel) null else b.id) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val c = if (sel) pal.onAccent else pal.text
                        Text(formatFreq(b.freqHz), color = c, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            if (b.type.usesGain) "%+.1f".format(Locale.ROOT, b.gainDb) else b.type.label,
                            color = c.copy(alpha = if (b.enabled) 0.8f else 0.4f), fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BandEditor(band: EqBand, onChange: (EqBand) -> Unit, onDelete: () -> Unit) {
    val pal = LocalPalette.current
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.07f) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Band · ${formatFreq(band.freqHz)} Hz", Modifier.weight(1f))
                IconButton(onClick = onDelete, Modifier.size(36.dp)) { Icon(Icons.Rounded.Delete, "Delete band", tint = pal.textDim) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterType.entries.forEach { t -> Pill(t.label, band.type == t, { onChange(band.copy(type = t)) }) }
            }
            Spacer(Modifier.height(10.dp))

            // Frequency: log slider + exact number entry
            NumberRow("Frequency", band.freqHz, "Hz", { "%.1f".format(Locale.ROOT, it).removeSuffix(".0") }) {
                onChange(band.copy(freqHz = it.coerceIn(EqLimits.MIN_FREQ, EqLimits.MAX_FREQ)))
            }
            val lf = ln(band.freqHz / EqLimits.MIN_FREQ) / ln(EqLimits.MAX_FREQ / EqLimits.MIN_FREQ)
            LabeledSlider("", lf, { t ->
                onChange(band.copy(freqHz = EqDefaults.roundFreq(EqLimits.MIN_FREQ * (EqLimits.MAX_FREQ / EqLimits.MIN_FREQ).pow(t))))
            }, 0f..1f, formatFreq(band.freqHz) + " Hz")

            if (band.type.usesGain) {
                NumberRow("Gain", band.gainDb, "dB", { "%.1f".format(Locale.ROOT, it) }) {
                    onChange(band.copy(gainDb = it.coerceIn(EqLimits.MIN_GAIN, EqLimits.MAX_GAIN)))
                }
                LabeledSlider("", band.gainDb, { onChange(band.copy(gainDb = Math.round(it * 10f) / 10f)) },
                    EqLimits.MIN_GAIN..EqLimits.MAX_GAIN, formatDb(band.gainDb))
            }

            NumberRow(if (band.type == FilterType.PEAK) "Width (Q)" else "Slope (Q)", band.q, "", { "%.2f".format(Locale.ROOT, it) }) {
                onChange(band.copy(q = it.coerceIn(EqLimits.MIN_Q, EqLimits.MAX_Q)))
            }
            val lq = ln(band.q / EqLimits.MIN_Q) / ln(EqLimits.MAX_Q / EqLimits.MIN_Q)
            LabeledSlider("", lq, { t ->
                val q = EqLimits.MIN_Q * (EqLimits.MAX_Q / EqLimits.MIN_Q).pow(t)
                onChange(band.copy(q = Math.round(q * 100f) / 100f))
            }, 0f..1f, "Q %.2f".format(Locale.ROOT, band.q) + if (band.type == FilterType.PEAK) "  ·  ${bandwidthOct(band.q)} oct" else "")

            SwitchRow("Band on", band.enabled, { onChange(band.copy(enabled = it)) })
        }
    }
}

private fun bandwidthOct(q: Float): String {
    // Bandwidth in octaves for a peaking filter with this Q.
    val x = 1.0 / (2 * q)
    val bw = 2.0 / ln(2.0) * kotlin.math.asinh(x)
    return "%.2f".format(Locale.ROOT, bw)
}

/** Label + exact numeric input that commits on Done or when focus leaves. */
@Composable
private fun NumberRow(label: String, value: Float, unit: String, fmt: (Float) -> String, onCommit: (Float) -> Unit) {
    val pal = LocalPalette.current
    var text by remember(value) { mutableStateOf(fmt(value)) }
    fun commit() { text.replace(',', '.').toFloatOrNull()?.let(onCommit) ?: run { text = fmt(value) } }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = pal.textDim, modifier = Modifier.weight(1f))
        Box(
            Modifier.clip(RoundedCornerShape(10.dp)).background(pal.text.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            BasicTextField(
                text, { text = it }, singleLine = true,
                textStyle = TextStyle(color = pal.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End),
                cursorBrush = SolidColor(pal.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.width(84.dp).onFocusChanged { if (!it.isFocused) commit() },
            )
        }
        if (unit.isNotEmpty()) Text(unit, color = pal.textFaint, modifier = Modifier.padding(start = 6.dp).width(24.dp))
        else Spacer(Modifier.width(30.dp))
    }
}

@Composable
private fun LevelCard(eq: EqState, edit: ((EqState) -> EqState) -> Unit) {
    val pal = LocalPalette.current
    val settings by AudioEngine.settings.collectAsState()
    val boost = remember(eq) { EqMath.peakBoostDb(eq).toFloat() }
    val total = remember(eq) { EqMath.inputGainDb(eq).toFloat() }
    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(16.dp)) {
            SectionTitle("Level & headroom")
            LabeledSlider("Preamp", eq.preampDb, { v -> edit { it.copy(preampDb = Math.round(v * 10f) / 10f) } }, -24f..12f, formatDb(eq.preampDb))
            SwitchRow(
                "Automatic headroom", eq.autoHeadroom, { on -> edit { it.copy(autoHeadroom = on) } },
                sub = "Lowers the level by the curve's biggest boost (${formatDb(boost)}) so boosts never clip.",
            )
            Text(
                "Signal into the EQ: ${formatDb(total)} · processed in 64-bit, output " +
                    if (settings.bitPerfectUsb) "16-bit dithered (bit-perfect mode)" else "32-bit float",
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PresetsCard(eq: EqState, onApply: (EqPreset) -> Unit) {
    val pal = LocalPalette.current
    var userPresets by remember { mutableStateOf(Store.loadUserPresets()) }
    var showSave by rememberSaveable { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<EqPreset?>(null) }
    val all = remember(userPresets) { Store.builtInPresets + userPresets }
    val active = remember(eq, all) { all.firstOrNull { EqTone.sameShape(it.eq, eq) } }
    val tabs = remember(userPresets) { Store.presetCategories + if (userPresets.isNotEmpty()) listOf(MINE) else emptyList() }
    // Opens on the group of the sound that's playing now.
    var tab by rememberSaveable { mutableStateOf(active?.let { if (it.builtIn) it.category else MINE } ?: Store.presetCategories.first()) }
    if (tab !in tabs) tab = tabs.first()

    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Sound presets", Modifier.weight(1f))
                Pill("Save current", true, { showSave = true })
            }
            // What's active right now, in plain words.
            Text(
                when {
                    !eq.enabled -> "EQ is off — pick a preset to switch it on."
                    active != null -> active.name + if (active.hint.isNotEmpty()) " · ${active.hint}" else ""
                    else -> "Custom sound — save it to keep it."
                },
                color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tabs.forEach { t ->
                    val on = t == tab
                    Text(
                        t, color = if (on) pal.text else pal.textFaint,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { tab = t }
                            .background(if (on) pal.text.copy(alpha = 0.08f) else Color.Transparent)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowPills {
                if (tab == MINE) {
                    userPresets.forEach { p -> Pill("★ " + p.name, p == active, { onApply(p) }, onLongClick = { toDelete = p }) }
                } else {
                    Store.builtInPresets.filter { it.category == tab }.forEach { p -> Pill(p.name, p == active, { onApply(p) }) }
                }
            }
            if (tab == MINE) Text(
                "Long-press one of your presets to delete it.",
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (showSave) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save preset") },
            text = {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(pal.text.copy(alpha = 0.08f)).padding(12.dp)) {
                    if (name.isEmpty()) Text("Preset name", color = pal.textFaint)
                    BasicTextField(name, { name = it }, singleLine = true, textStyle = TextStyle(color = pal.text, fontSize = 16.sp),
                        cursorBrush = SolidColor(pal.accent), modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim().ifEmpty { "My preset ${userPresets.size + 1}" }
                    userPresets = userPresets.filterNot { it.name == n } + EqPreset(n, eq)
                    Store.saveUserPresets(userPresets)
                    tab = MINE
                    showSave = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } },
        )
    }
    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete \"${p.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    userPresets = userPresets - p
                    Store.saveUserPresets(userPresets)
                    toDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

/**
 * The EQ profiles button and its menu: Custom 1-5. Tap a saved one to load it into the EQ you're
 * editing (the default or this song's); tap an empty one, or a save icon, to keep the current EQ there.
 */
@Composable
private fun EqProfiles(eq: EqState, onLoad: (EqState) -> Unit) {
    val pal = LocalPalette.current
    var slots by remember { mutableStateOf(Store.loadEqProfiles().let { saved -> List(EQ_PROFILES) { saved.getOrNull(it) } }) }
    var open by remember { mutableStateOf(false) }
    fun save(i: Int) {
        slots = slots.toMutableList().also { it[i] = eq }
        Store.saveEqProfiles(slots)
    }
    Box {
        GlassCircleButton({ open = true }, Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Person, "EQ profiles", tint = pal.text, modifier = Modifier.size(22.dp))
        }
        ProfileMenu(open, { open = false }, "EQ profiles", "Tap one to load it. Tap an empty one, or a save icon, to keep the EQ you have now.") {
            slots.forEachIndexed { i, slot ->
                val inUse = slot != null && EqTone.sameShape(slot, eq)
                ProfileRow(
                    name = customName(i),
                    status = when {
                        slot == null -> "Empty · tap to save the current EQ"
                        inUse -> "In use"
                        else -> "Saved · tap to load"
                    },
                    active = inUse,
                    onClick = { if (slot == null) save(i) else { onLoad(slot); open = false } },
                    trailing = {
                        IconButton(onClick = { save(i) }) {
                            Icon(Icons.Rounded.Save, "Save the current EQ to ${customName(i)}", tint = pal.textDim)
                        }
                    },
                )
            }
        }
    }
}

private const val EQ_PROFILES = 5

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FlowPills(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

private const val MINE = "Mine"
