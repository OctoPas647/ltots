package com.ltots.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ltots.app.data.ListenHistory
import com.ltots.app.data.Listen
import com.ltots.app.data.LocalLibrary
import com.ltots.app.data.Track
import com.ltots.app.ui.PlayerUi
import com.ltots.app.ui.bottomChrome
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.topInset
import com.ltots.app.ui.theme.LocalPalette
import java.text.DateFormat
import java.util.Date

/**
 * The songs played lately, newest first and grouped by day (Today, Yesterday, This week…),
 * like YouTube Music's history. Shown on the Playing tab when nothing plays, and from the
 * history button at the top right of the Library.
 *
 * Tapping a song plays it, followed by the songs played before it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryPage(player: PlayerUi, onPlayed: () -> Unit, onBack: (() -> Unit)?, onOpenEq: (() -> Unit)? = null) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ListenHistory.ensureLoaded() } }
    val items by ListenHistory.items.collectAsState()
    // The files on the phone, to play history entries (a deleted file stays listed, greyed out).
    val library by produceState(LocalLibrary.cached() ?: emptyList()) {
        if (LocalLibrary.cached() == null && com.ltots.app.prep.LibraryPrep.hasAudioPermission(context)) value = LocalLibrary.load(context)
    }
    val byId = remember(library) { library.associateBy { it.id } }
    val byKey = remember(library) { library.associateBy { it.profileKey } }
    fun trackOf(l: Listen): Track? = byId[l.trackId]?.takeIf { it.profileKey == l.key } ?: byKey[l.key]
    val groups = remember(items) { ListenHistory.groups(items) }
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = topInset() + 12.dp, bottom = bottomChrome()),
    ) {
        item("head") {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("History", style = MaterialTheme.typography.headlineSmall, color = pal.text, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (player.hasMedia) "Songs you played lately" else "Nothing is playing — pick up where you left off",
                        color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                    )
                }
                // The EQ lives on the Playing tab, which shows this page while nothing plays.
                if (onOpenEq != null) { Pill("EQ", false, onOpenEq); Spacer(Modifier.width(8.dp)) }
                if (onBack != null) { Pill("Now playing", true, onBack); Spacer(Modifier.width(8.dp)) }
                if (items.isNotEmpty()) Pill("Clear", false, { confirmClear = true })
            }
        }
        if (items.isEmpty()) item("empty") {
            Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.History, null, tint = pal.textFaint, modifier = Modifier.size(64.dp))
                Text("No history yet", color = pal.textDim, modifier = Modifier.padding(top = 12.dp))
                Text("Songs you play show up here.", color = pal.textFaint, style = MaterialTheme.typography.bodySmall)
            }
        }
        for ((label, list) in groups) {
            item("g:$label") {
                Text(label, color = pal.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            }
            items(list, key = { "h:$label:${it.key}:${it.at}" }) { l ->
                val t = trackOf(l)
                HistoryRow(l, t, playing = player.mediaId == l.key, onPlay = {
                    if (t == null) return@HistoryRow
                    // This song, then the ones played before it (each once), like a "recently played" queue.
                    val after = items.dropWhile { it.at != l.at || it.key != l.key }.drop(1).mapNotNull { trackOf(it) }
                    val queue = (listOf(t) + after).distinctBy { it.id }.take(200)
                    player.play(queue, 0)
                    onPlayed()
                }, onRemove = { ListenHistory.remove(l) })
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear history?") },
        text = { Text("Every song you've played is removed from the history. Recommendations start learning again from scratch.") },
        confirmButton = { TextButton({ ListenHistory.clear(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(l: Listen, t: Track?, playing: Boolean, onPlay: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onPlay, onLongClick = { menu = true })
            .padding(horizontal = 4.dp, vertical = 7.dp)
            .then(if (t == null) Modifier.alpha(0.45f) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val id = t?.id ?: l.trackId
        val bmp by produceState<android.graphics.Bitmap?>(null, id) { value = if (id >= 0) LocalLibrary.artwork(context, id, 192) else null }
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(pal.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b != null) Image(remember(b) { b.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Rounded.MusicNote, null, tint = pal.textFaint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(l.title.ifBlank { "Unknown song" }, color = if (playing) pal.accent else pal.text, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(l.at))
            val sub = listOfNotNull(l.artist.ifBlank { null }, if (t == null) "no longer on the phone" else time).joinToString("  ·  ")
            Text(sub, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = pal.textDim) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                if (t != null) DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; onPlay() })
                DropdownMenuItem(text = { Text("Remove from history") }, onClick = { menu = false; onRemove() })
            }
        }
    }
}
