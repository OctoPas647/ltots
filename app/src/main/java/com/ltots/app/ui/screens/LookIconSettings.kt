package com.ltots.app.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ltots.app.data.IconBars
import com.ltots.app.data.IconCover
import com.ltots.app.data.IconFiles
import com.ltots.app.data.IconFill
import com.ltots.app.data.IconIdle
import com.ltots.app.data.ThemeConfig
import com.ltots.app.ui.components.IconLoad
import com.ltots.app.ui.components.IconMask
import com.ltots.app.ui.components.IconPreview
import com.ltots.app.ui.components.LabeledSlider
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SwitchRow
import com.ltots.app.ui.components.rememberIconLoad
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.ThemeState
import kotlinx.coroutines.launch
import java.util.Locale

/** Default dimming of the full-size cover (Reset goes back to it). */
private val DEFAULT_COVER_DIM = ThemeConfig().iconCoverDim

/** Default black / white threshold (Reset goes back to it). */
private const val DEFAULT_THRESHOLD = 0.5f

/**
 * Look > Visualizer > On the Playing tab > Icon: the picture, how it becomes black and white, and
 * how its dark parts and bars look. Everything follows the Look profile in use.
 */
@Composable
internal fun IconSettings(cfg: ThemeConfig, artwork: Bitmap?, set: ((ThemeConfig) -> ThemeConfig) -> Unit) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var problem by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = IconFiles.import(context, uri)
            if (name == null) { problem = "Couldn't read that picture. Try a PNG or JPG."; return@launch }
            problem = null
            set { it.copy(iconFile = name, iconMode = true) }
            // Pictures no look of any user uses any more are removed.
            val inUse = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.ltots.app.data.Users.iconsInUse() +
                    ThemeState.profiles.value.mapNotNull { it.look.iconFile } + listOfNotNull(ThemeState.config.value.iconFile)
            }
            IconFiles.cleanUp(context, inUse)
        }
    }
    fun pickPicture() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    Spacer(Modifier.height(4.dp))
    // Preview: the icon as the Playing tab will show it, with a still spectrum.
    val load = rememberIconLoad(cfg)
    Box(
        Modifier.fillMaxWidth().aspectRatio(1.4f).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        when (load) {
            is IconLoad.Ready -> if (load.shape.isEmpty) {
                Hint("Nothing is dark at this threshold.")
            } else {
                IconPreview(load.shape, artwork, Modifier.fillMaxSize())
            }
            IconLoad.Loading -> Hint("Tracing the picture…")
            IconLoad.None -> Hint(if (cfg.iconFile == null) "No picture yet: the Playing tab shows the cover until you pick one." else "That picture can't be read any more. Pick another.")
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(if (cfg.iconFile == null) "Pick a picture" else "Change picture", false, ::pickPicture)
    }
    Text(
        problem ?: "A black-and-white picture: the bars grow out of its dark parts. PNG works best.",
        color = if (problem != null) Color(0xFFFFC14D) else pal.textFaint, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
    )

    ThresholdSlider(cfg.iconThreshold) { v -> set { it.copy(iconThreshold = v) } }
    Description("How dark a pixel must be to count as part of the shape.")
    SwitchRow("Invert", cfg.iconInvert, { on -> set { it.copy(iconInvert = on) } },
        sub = "Swap black and white: the light parts become the shape.")
    LabeledSlider(
        "Minimum hole size", cfg.iconMinHole, { v -> set { it.copy(iconMinHole = v) } }, 0f..0.01f,
        "%.2f%%".format(Locale.ROOT, cfg.iconMinHole * 100),
        onReset = { set { it.copy(iconMinHole = IconMask.DEFAULT_MIN_HOLE) } },
        isDefault = cfg.iconMinHole == IconMask.DEFAULT_MIN_HOLE,
    )
    Description("White spots inside the shape smaller than this are ignored, like an eye's highlight.")

    Spacer(Modifier.height(8.dp))
    Label("Dark parts")
    FlowPills { IconFill.entries.forEach { f -> Pill(f.label, cfg.iconFill == f, { set { it.copy(iconFill = f) } }) } }
    Description(
        when (cfg.iconFill) {
            IconFill.DARK -> "Left empty: the shape shows only through the bars."
            IconFill.GLASS -> "Glass in the play button's colour; follows Blur, Frost / tint and Rim light above."
            IconFill.ACCENT -> "Solid, in the play button's colour."
        }
    )

    Spacer(Modifier.height(8.dp))
    Label("Song's cover")
    FlowPills { IconCover.entries.forEach { c -> Pill(c.label, cfg.iconCover == c, { set { it.copy(iconCover = c) } }) } }
    Description(
        when (cfg.iconCover) {
            IconCover.OFF -> "No cover: only the icon and the bars."
            IconCover.INSIDE -> "The song's cover shows inside the dark parts."
            IconCover.FULL -> "The whole cover, as big as usual, stays behind the visualizer, dimmed."
        }
    )
    if (cfg.iconCover == IconCover.FULL) {
        LabeledSlider(
            "Cover dimming", cfg.iconCoverDim, { v -> set { it.copy(iconCoverDim = v) } }, 0f..0.9f,
            "%.0f%%".format(Locale.ROOT, cfg.iconCoverDim * 100),
            onReset = { set { it.copy(iconCoverDim = DEFAULT_COVER_DIM) } },
            isDefault = cfg.iconCoverDim == DEFAULT_COVER_DIM,
        )
        Description("Higher makes the cover darker, so the bars stand out more.")
    }

    Spacer(Modifier.height(8.dp))
    Label("Bars")
    FlowPills { IconBars.entries.forEach { b -> Pill(b.label, cfg.iconBars == b, { set { it.copy(iconBars = b) } }) } }
    Description(
        when (cfg.iconBars) {
            IconBars.OUT -> "From the outline out over the background, never past the safe edge."
            IconBars.IN -> "From the outline into the dark parts."
            IconBars.BOTH -> "From the outline both ways: out over the background and into the dark parts."
        }
    )

    Spacer(Modifier.height(8.dp))
    Label("When nothing is playing")
    FlowPills { IconIdle.entries.forEach { i -> Pill(i.label, cfg.iconIdle == i, { set { it.copy(iconIdle = i) } }) } }
    Description(
        when (cfg.iconIdle) {
            IconIdle.NOTHING -> if (cfg.iconCover == IconCover.FULL) "Only the dimmed cover stays while the music is paused or stopped."
                else "Nothing is drawn while the music is paused or stopped."
            IconIdle.SHAPE -> "The dark parts stay as set above, without bars."
            IconIdle.COVER -> "The song's cover shows in the icon's place."
        }
    )
    Spacer(Modifier.height(10.dp))
}

/**
 * The black / white threshold, with Reset (back to 50%) and Undo (back to the value before the
 * last change: a whole drag, or a Reset, is one step), like the EQ tab's.
 */
@Composable
private fun ThresholdSlider(value: Float, onSet: (Float) -> Unit) {
    var previous by remember { mutableStateOf<Float?>(null) }
    var dragFrom by remember { mutableStateOf<Float?>(null) }
    LabeledSlider(
        "Black / white threshold", value,
        { v -> if (dragFrom == null) dragFrom = value; onSet((v * 100).toInt() / 100f) },
        0f..1f, "%.0f%%".format(Locale.ROOT, value * 100),
        onChangeFinished = { dragFrom?.let { if (it != value) previous = it }; dragFrom = null },
        trailing = {
            val canUndo = previous != null
            Pill("Undo", false, { previous?.let { onSet(it); previous = null } }, modifier = Modifier.alpha(if (canUndo) 1f else 0.4f))
            Spacer(Modifier.width(6.dp))
            Pill("Reset", false, {
                if (value != DEFAULT_THRESHOLD) { previous = value; onSet(DEFAULT_THRESHOLD) }
            }, modifier = Modifier.alpha(if (value != DEFAULT_THRESHOLD) 1f else 0.4f))
        },
    )
}

@Composable
private fun Label(text: String) {
    Text(text, color = LocalPalette.current.textDim, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Description(text: String) {
    Text(text, color = LocalPalette.current.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = LocalPalette.current.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(24.dp))
}
