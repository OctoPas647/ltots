package com.ltots.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ltots.app.audio.AudioEngine
import com.ltots.app.data.AppSettings
import com.ltots.app.data.LibraryFilter
import com.ltots.app.data.LocalLibrary
import com.ltots.app.data.Track
import com.ltots.app.identify.AiModels
import com.ltots.app.lyrics.SongMatch
import com.ltots.app.moods.MoodIndex
import com.ltots.app.prep.LibraryPrep
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.theme.LocalPalette

/**
 * The Library's filter: only popular songs (hits, the user's most played, or both), and
 * artists to leave out. Applies to the Songs list, mixes and what plays next.
 */
@Composable
internal fun LibraryFilterDialog(tracks: List<Track>, settings: AppSettings, onDismiss: () -> Unit) {
    val pal = LocalPalette.current
    val artists = remember(tracks) { LibraryFilter.artists(tracks) }
    val hidden = settings.hiddenArtists.map { SongMatch.norm(it) }.toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = if (hidden.isNotEmpty() || settings.onlyHits || settings.onlyMostPlayed) {
            { TextButton(onClick = { AudioEngine.updateSettings { it.copy(hiddenArtists = emptyList(), onlyHits = false, onlyMostPlayed = false) } }) { Text("Clear all") } }
        } else null,
        title = { Text("Filter your music") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Popular songs only", color = pal.text, style = MaterialTheme.typography.titleSmall)
                ToggleRow(
                    "Well-known hits", "Songs that are popular worldwide, plus each artist's best-known songs you have.",
                    settings.onlyHits,
                ) { on -> AudioEngine.updateSettings { it.copy(onlyHits = on) } }
                ToggleRow(
                    "My most played", "The songs you listen to most in LToTS (played twice or more).",
                    settings.onlyMostPlayed,
                ) { on -> AudioEngine.updateSettings { it.copy(onlyMostPlayed = on) } }
                if (settings.onlyHits && settings.onlyMostPlayed) {
                    Text("With both on, a song stays if it's either.", color = pal.textFaint, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.padding(top = 6.dp))
                Text("Leave out artists", color = pal.text, style = MaterialTheme.typography.titleSmall)
                Text("Unticked artists don't show up or play.", color = pal.textFaint, style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(artists, key = { it.first }) { (name, count) ->
                        val shown = SongMatch.norm(name) !in hidden
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                AudioEngine.updateSettings { s ->
                                    val now = s.hiddenArtists.filter { SongMatch.norm(it) != SongMatch.norm(name) }
                                    s.copy(hiddenArtists = if (shown) now + name else now)
                                }
                            }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(shown, null, colors = CheckboxDefaults.colors(checkedColor = pal.accent))
                            Text(name, color = if (shown) pal.text else pal.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("$count", color = pal.textFaint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ToggleRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = pal.text)
            Text(sub, color = pal.textDim, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Switch(on, onChange)
    }
}

/**
 * Sets up and shows the mood listener behind AI albums and themes: download the model, listen
 * to the library (now, or while charging), and how far it has got.
 */
@Composable
internal fun MoodsCard(compact: Boolean = false) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val models by AiModels.status.collectAsState()
    val progress by MoodIndex.progress.collectAsState()
    val moods by MoodIndex.embeddings.collectAsState()
    val installed = AiModels.Model.MOODS in models.installed
    val downloading = models.downloading == AiModels.Model.MOODS
    val total = remember(moods) { LocalLibrary.cached()?.size ?: 0 }
    val missing = remember(moods, total) { LocalLibrary.cached()?.let { MoodIndex.missing(it).size } ?: 0 }

    if (compact && installed && progress == null && missing == 0) return
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!compact) {
            Text("AI albums & moods", color = pal.text, style = MaterialTheme.typography.titleMedium)
        }
        val p = progress
        when {
            downloading -> {
                Text("Downloading the mood listener…", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { models.progress }, modifier = Modifier.fillMaxWidth(), color = pal.accent)
            }
            !installed -> {
                Text(
                    "An on-device AI listens to each of your songs once and makes albums of songs that sound alike, plus themes like Sad, Happy, Chill, Party and Heartbreak. " +
                        "It's a ${AiModels.Model.MOODS.bytes / 1_000_000} MB download and works offline.",
                    color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                )
                models.error?.takeIf { it.startsWith(AiModels.Model.MOODS.title) }?.let { Text(it, color = pal.textFaint, style = MaterialTheme.typography.bodySmall) }
                Pill("Download (${AiModels.Model.MOODS.bytes / 1_000_000} MB)", true, {
                    val app = context.applicationContext
                    com.ltots.app.identify.SongFinder.downloadModel(AiModels.Model.MOODS) { LibraryPrep.runMoods(app) }
                })
            }
            p != null -> {
                Text("Listening to your songs… ${p.done} of ${p.total}", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { p.done / p.total.coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth(), color = pal.accent)
            }
            missing > 0 -> {
                Text(
                    "$missing songs not listened to yet. It happens by itself while the phone charges, or now (uses some battery).",
                    color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Listen now", true, { LibraryPrep.runMoods(context) })
                }
            }
            else -> Text("Needs more songs: AI albums and themes appear from about 15 songs.", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
        }
    }
}
