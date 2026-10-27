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
import com.ltots.app.audio.AudioEngine
import com.ltots.app.lyrics.AiSync
import com.ltots.app.lyrics.LyricLine
import com.ltots.app.lyrics.Lrc
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionTitle("Lyrics", Modifier.weight(1f))
                if (state is LyricsState.Found && (!state.doc.synced.isNullOrEmpty() || !state.doc.plain.isNullOrBlank())) {
                    Pill(if (big) "Smaller" else "Bigger", false, { big = !big })
                }
                // AI: recognise the song from its sound and get its real lyrics, lined up with this
                // version (for edits, re-uploads and wrongly named files).
                Pill("✦ AI", state is LyricsState.Matching, {
                    if (state !is LyricsState.Matching) query()?.let { com.ltots.app.identify.LyricsMatch.start(context, it, player.trackId) }
                }, enabled = player.trackId >= 0)
                Pill("Import .lrc", false, { lrcPicker.launch(arrayOf("text/*", "application/octet-stream")) })
            }
            val shown = when (state) {
                is LyricsState.Found -> state.doc
                is LyricsState.MatchFailed -> state.previous
                else -> null
            }
            if (state is LyricsState.Matching) Message("✦  ${state.status}")
            if (state is LyricsState.MatchFailed) Message("✦  ${state.message}")
            when {
                shown != null -> {
                    val doc = shown
                    // Plain lyrics (no times anywhere) get guessed times from the song's length,
                    // so they still follow along and can be tapped.
                    val estimated = remember(doc, player.durationMs) { if (doc.synced == null) Lrc.estimate(doc.plain, player.durationMs) else null }
                    val lines = doc.synced ?: estimated
                    Box(Modifier.fillMaxWidth().height(if (lines != null) height else 340.dp)) {
                        when {
                            lines != null -> SyncedLyrics(lines, player)
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
                            if (doc.matchedAs.isNotBlank() && doc.note.isNotBlank()) append(" · ").append(doc.matchedAs)
                            if (estimated != null) append(" · timing estimated (no time-synced lyrics for this song)")
                            else if (doc.synced == null && !doc.instrumental) append(" · not time-synced for this song")
                            if (doc.note.isNotBlank()) append("\n").append(doc.note)
                        },
                        color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp),
                    )
                    if (estimated != null && key != null && player.trackId >= 0) AiTiming(key, player.trackId, doc, player.durationMs)
                }
                state is LyricsState.Matching -> {}
                state == LyricsState.Searching || state == LyricsState.Idle -> Message("Looking for lyrics…")
                state == LyricsState.NotFound || state is LyricsState.MatchFailed -> {
                    if (state == LyricsState.NotFound) Message("No lyrics found for this song. Tap ✦ AI to recognise it from its sound.")
                    Pill("Search again", false, { query()?.let { Lyrics.retry(context, it) } })
                }
                state is LyricsState.Offline -> {
                    Message(state.message)
                    Pill("Try again", false, { query()?.let { Lyrics.retry(context, it) } })
                }
            }
        }
    }
}

/**
 * For lyrics with estimated timing: the on-device AI listens to the song and times them for
 * real. Runs by itself once the speech model is installed (Profile > App settings), or on a tap.
 */
@Composable
private fun AiTiming(key: String, trackId: Long, doc: com.ltots.app.lyrics.LyricsDoc, durationMs: Long) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val status by AiSync.status.collectAsState()
    val ready by AiSync.modelReady.collectAsState()
    val settings by AudioEngine.settings.collectAsState()
    var asking by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, ready, settings.aiLyrics) {
        if (ready && settings.aiLyrics) AiSync.request(context, key, trackId, doc, durationMs)
    }
    val s = status
    val line = when {
        s is AiSync.Status.Listening && s.key == key -> "AI is listening to the song to time the lyrics… ${(s.progress * 100).toInt()}%"
        s is AiSync.Status.Listening -> "AI is timing another song's lyrics; this one is next."
        s is AiSync.Status.DownloadingModel -> "Downloading the speech model… ${(s.progress * 100).toInt()}%"
        s is AiSync.Status.Failed && (s.key == key || s.key == null) -> s.message
        asking -> "The on-device AI listens to the song and matches the words it hears to these lyrics. " +
            "It needs a speech model (English, ${AiSync.MODEL_SIZE}) downloaded once; after that it works offline."
        else -> null
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(line ?: "Let the on-device AI time these lyrics by listening to the song.", color = pal.textDim,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        val busy = s is AiSync.Status.Listening || s is AiSync.Status.DownloadingModel
        if (!busy) {
            when {
                ready -> Pill(if (s is AiSync.Status.Failed && s.key == key) "Try again" else "Time with AI", true,
                    { AiSync.request(context, key, trackId, doc, durationMs, again = true) })
                asking -> Pill("Download & time", true, { AiSync.request(context, key, trackId, doc, durationMs, download = true, again = true) })
                else -> Pill("Time with AI", true, { asking = true })
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
private fun SyncedLyrics(lines: List<LyricLine>, player: PlayerUi) {
    val pal = LocalPalette.current
    val now = Lrc.lineAt(lines, player.positionMs + LEAD_MS)
    val list = rememberLazyListState()
    // Follow the song unless you've just scrolled yourself (then wait a few seconds).
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) userScrolledAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(now, lines) {
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
