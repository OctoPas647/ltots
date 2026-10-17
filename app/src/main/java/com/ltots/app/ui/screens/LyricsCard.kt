package com.ltots.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ltots.app.lyrics.LyricsDoc
import com.ltots.app.lyrics.Lyrics
import com.ltots.app.lyrics.LyricsState
import com.ltots.app.lyrics.SongQuery
import com.ltots.app.ui.PlayerUi
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.theme.LocalPalette
import kotlin.math.max

/** Lines light up slightly before they're sung, so they never feel late. */
private const val LEAD_MS = 150L

/**
 * Lyrics under the player, Spotify-style: the line being sung is highlighted and kept in view,
 * tap any line to jump there. Found once per song (then saved), so it also works offline.
 */
@Composable
fun LyricsCard(player: PlayerUi) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val key = player.mediaId
    val current by Lyrics.current.collectAsState()
    val hasLength = player.durationMs > 0
    fun query() = key?.let { SongQuery(it, player.title, player.artist.ifBlank { null }, player.album.ifBlank { null }, player.durationMs) }
    // Only once the song's length is known: it's how a match is checked.
    LaunchedEffect(key, hasLength) { if (hasLength) query()?.let { Lyrics.show(context, it) } }
    val state = current?.takeIf { it.first == key }?.second ?: LyricsState.Idle
    var big by rememberSaveable { mutableStateOf(false) }
    val height by animateDpAsState(if (big) 560.dp else 340.dp, label = "lyricsHeight")

    // Pick an .lrc file yourself for this song; it replaces whatever was found automatically.
    val lrcPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val k = key ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text != null) Lyrics.import(context, k, text)
        }
    }

    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.05f) {
        Column(Modifier.padding(top = 14.dp, start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Lyrics", Modifier.weight(1f))
                if (state is LyricsState.Found && !state.doc.synced.isNullOrEmpty()) {
                    Pill(if (big) "Smaller" else "Bigger", false, { big = !big })
                }
                Pill("Import .lrc", false, { lrcPicker.launch(arrayOf("text/*", "application/octet-stream")) })
            }
            when (state) {
                is LyricsState.Found -> {
                    val doc = state.doc
                    Box(Modifier.fillMaxWidth().height(if (doc.synced != null) height else 340.dp)) {
                        when {
                            doc.synced != null -> SyncedLyrics(doc, player)
                            doc.instrumental && doc.plain.isNullOrBlank() -> Message("♪  Instrumental")
                            else -> Text(
                                doc.plain.orEmpty(), color = pal.text, fontSize = 17.sp, lineHeight = 26.sp,
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                    Text(
                        buildString {
                            append("Lyrics from ").append(doc.source.ifBlank { "LRCLIB" })
                            if (doc.synced == null && !doc.instrumental) append(" · not time-synced for this song")
                        },
                        color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                    )
                }
                LyricsState.Searching, LyricsState.Idle -> Message("Looking for lyrics…")
                LyricsState.NotFound -> {
                    Message("No lyrics found for this song.")
                    Pill("Search again", false, { query()?.let { Lyrics.retry(context, it) } })
                }
                is LyricsState.Offline -> {
                    Message(state.message)
                    Pill("Try again", false, { query()?.let { Lyrics.retry(context, it) } })
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    val pal = LocalPalette.current
    Text(text, color = pal.textDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 18.dp, horizontal = 4.dp))
}

@Composable
private fun SyncedLyrics(doc: LyricsDoc, player: PlayerUi) {
    val pal = LocalPalette.current
    val lines = doc.synced ?: return
    val now = doc.lineAt(player.positionMs + LEAD_MS)
    val list = rememberLazyListState()
    // Follow the song unless you've just scrolled yourself (then wait a few seconds).
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) userScrolledAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(now, doc) {
        if (System.currentTimeMillis() - userScrolledAt < 4000) return@LaunchedEffect
        autoScrolling = true
        try { list.animateScrollToItem(max(0, now - 1)) } finally { autoScrolling = false }
    }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 200.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(lines) { i, line ->
            val color by animateColorAsState(
                when {
                    i == now -> pal.text
                    i < now -> pal.textFaint
                    else -> pal.textDim.copy(alpha = 0.55f)
                }, label = "lyricLine",
            )
            Text(
                line.text.ifBlank { "♪" },
                color = color,
                fontSize = if (i == now) 22.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 28.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .clickable { player.controller?.seekTo(line.timeMs) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            )
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}
