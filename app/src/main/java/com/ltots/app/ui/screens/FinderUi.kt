package com.ltots.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ltots.app.data.Track
import com.ltots.app.download.DownloadManager
import com.ltots.app.identify.AiModels
import com.ltots.app.identify.ClipSource
import com.ltots.app.identify.FinderState
import com.ltots.app.identify.IdentifyProgress
import com.ltots.app.identify.Preview
import com.ltots.app.identify.SongFinder
import com.ltots.app.identify.Step
import com.ltots.app.identify.StepState
import com.ltots.app.identify.Suggestion
import com.ltots.app.ui.PlayerUi
import com.ltots.app.ui.components.NetImage
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/**
 * The song finder's other ways in, under the search box on the Get tab (a video link goes
 * straight into the search box).
 */
@Composable
fun FinderTools(onToast: (String) -> Unit) {
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) SongFinder.start(ClipSource.Mic) else onToast("LToTS needs the microphone to listen")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) SongFinder.start(ClipSource.LocalFile(uri, displayName(context, uri)))
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolPill(Icons.Rounded.Mic, "Listen") {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) SongFinder.start(ClipSource.Mic)
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        ToolPill(Icons.Rounded.VideoFile, "From a video") { runCatching { picker.launch(arrayOf("video/*", "audio/*")) } }
    }
}

@Composable
private fun ToolPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(pal.text.copy(alpha = 0.08f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = pal.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = pal.text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

/** The song finder's content inside the Get tab's panel. */
@Composable
fun FinderContent(
    state: FinderState,
    player: PlayerUi,
    onOpenPlayer: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onToast: (String) -> Unit,
) {
    when (state) {
        FinderState.Idle -> Unit
        is FinderState.Listening -> ListeningView(state)
        is FinderState.Working -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item { FinderHeader("Finding the song…", state.label) }
            item { Steps(state.progress) }
            item { Row(Modifier.padding(16.dp)) { Pill("Cancel", false, { SongFinder.cancel() }) } }
        }
        is FinderState.Failed -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item { FinderHeader("No match", state.label) }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = LocalPalette.current.textDim, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(state.message, color = LocalPalette.current.textDim, style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.signIn?.let { p -> item { SignInRow(p) } }
            item { Steps(state.progress) }
            item { FinderModelsCard() }
            item { Row(Modifier.padding(16.dp)) { Pill("Close", false, { SongFinder.clear() }) } }
        }
        is FinderState.Results -> ResultsView(state, player, onOpenPlayer, onOpenAlbum, onOpenArtist, onToast)
    }
}

/** "Sign in to Instagram" — then the same link is tried again by itself. */
@Composable
private fun SignInRow(p: com.ltots.app.identify.Platform) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Pill("Sign in to ${p.label}", true, { open = true })
    }
    if (open) com.ltots.app.ui.components.SignInDialog(p, onDismiss = { open = false }, onSignedIn = { open = false; SongFinder.retry() })
}

@Composable
private fun FinderHeader(title: String, label: String) {
    val pal = LocalPalette.current
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = pal.text)
        Text(label, color = pal.textFaint, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ListeningView(s: FinderState.Listening) {
    val pal = LocalPalette.current
    val pulse = rememberInfiniteTransition(label = "pulse")
    val p by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    val level by animateFloatAsState(s.level, label = "level")
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(150.dp).scale(p + level * 0.35f).clip(CircleShape).background(pal.accent.copy(alpha = 0.18f)))
            Box(Modifier.size(104.dp).clip(CircleShape).background(pal.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Mic, null, tint = pal.onAccent, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(22.dp))
        Text("Listening…", style = MaterialTheme.typography.titleMedium, color = pal.text)
        Text("Hold the phone near the music · ${s.secondsLeft} s", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Pill("Cancel", false, { SongFinder.cancel() })
    }
}

@Composable
private fun Steps(p: IdentifyProgress) {
    val pal = LocalPalette.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        for (step in Step.entries) {
            val st = p.steps[step] ?: StepState.WAITING
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    when (st) {
                        StepState.RUNNING -> CircularProgressIndicator(color = pal.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        StepState.DONE -> Icon(Icons.Rounded.CheckCircle, null, tint = pal.accent, modifier = Modifier.size(18.dp))
                        StepState.SKIPPED -> Icon(Icons.Rounded.RemoveCircleOutline, null, tint = pal.textFaint, modifier = Modifier.size(18.dp))
                        StepState.FAILED -> Icon(Icons.Rounded.ErrorOutline, null, tint = Color(0xFFFF8A80), modifier = Modifier.size(18.dp))
                        StepState.WAITING -> Box(Modifier.size(8.dp).clip(CircleShape).background(pal.textFaint.copy(alpha = 0.5f)))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        step.label, style = MaterialTheme.typography.bodyMedium,
                        color = if (st == StepState.WAITING || st == StepState.SKIPPED) pal.textFaint else pal.text,
                        fontWeight = if (st == StepState.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    p.details[step]?.let { Text(it, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    if (st == StepState.RUNNING && p.fraction != null) LinearProgressIndicator(
                        progress = { p.fraction }, color = pal.accent, trackColor = pal.text.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultsView(
    s: FinderState.Results,
    player: PlayerUi,
    onOpenPlayer: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onToast: (String) -> Unit,
) {
    val pal = LocalPalette.current
    var open by remember(s) { mutableStateOf(s.suggestions.firstOrNull()?.let { keyOf(it) }) }
    var showSteps by remember(s) { mutableStateOf(false) }
    // "Download & play": play it as soon as the download lands.
    var playWhenReady by remember { mutableStateOf<Suggestion?>(null) }
    val done by DownloadManager.downloaded.collectAsState()
    LaunchedEffect(done, playWhenReady) {
        val want = playWhenReady ?: return@LaunchedEffect
        val id = want.song?.videoId ?: return@LaunchedEffect
        val uriStr = done[id] ?: return@LaunchedEffect
        playWhenReady = null
        val uri = Uri.parse(uriStr)
        player.play(listOf(Track(
            id = uri.lastPathSegment?.toLongOrNull() ?: -1L, uri = uri, title = want.title, artist = want.artist,
            album = want.album ?: "", albumId = -1, durationMs = (want.song.durationSec ?: 0) * 1000L,
            sampleRate = null, bitsPerSample = null, mime = null,
        )), 0)
        onOpenPlayer()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        item("head") {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val songs = s.suggestions.mapNotNull { it.part }.distinct().size
                    Text(if (songs >= 2) "$songs songs in this video" else "Best matches", style = MaterialTheme.typography.titleMedium, color = pal.text)
                    Text(if (songs >= 2) "In the order they play · ${s.label}" else "Most likely first · ${s.label}", color = pal.textFaint, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { Preview.stop(); SongFinder.clear() }) { Icon(Icons.Rounded.Close, "Close", tint = pal.textDim) }
            }
        }
        itemsIndexed(s.suggestions, key = { i, it -> "$i-" + keyOf(it) }) { i, sug ->
            SuggestionRow(i + 1, sug, expanded = open == keyOf(sug), onClick = { open = if (open == keyOf(sug)) null else keyOf(sug) },
                player = player, onOpenAlbum = onOpenAlbum, onOpenArtist = onOpenArtist, onToast = onToast,
                onDownloadAndPlay = { playWhenReady = it })
        }
        item("steps") {
            Column(Modifier.padding(top = 8.dp)) {
                Text(
                    if (showSteps) "Hide how it was found" else "How it was found",
                    color = pal.accent, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { showSteps = !showSteps }.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (showSteps) Steps(s.progress)
            }
        }
        item("models") { FinderModelsCard(onlyMissing = true) }
    }
}

private fun keyOf(s: Suggestion) = s.song?.videoId ?: (s.identityKey + (s.version ?: ""))

@Composable
private fun SuggestionRow(
    rank: Int, s: Suggestion, expanded: Boolean, onClick: () -> Unit,
    player: PlayerUi, onOpenAlbum: (String) -> Unit, onOpenArtist: (String) -> Unit,
    onToast: (String) -> Unit, onDownloadAndPlay: (Suggestion) -> Unit,
) {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    val preview by Preview.state.collectAsState()
    val done by DownloadManager.downloaded.collectAsState()
    val key = keyOf(s)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp).clip(RoundedCornerShape(16.dp))
            .background(if (expanded) pal.text.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        s.part?.let { part ->
            // A clip with several songs: which one this is and when it plays.
            Text(
                "Song $part" + (s.heardAt.takeIf { it.isNotEmpty() }?.let { at -> " · at " + at.take(3).joinToString(", ") { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) } } ?: ""),
                color = pal.accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$rank", color = if (rank == 1 || s.part != null) pal.accent else pal.textFaint, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(24.dp))
            NetImage(s.coverUrl, 52.dp, RoundedCornerShape(10.dp), placeholder = Icons.Rounded.MusicNote)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    s.version?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(it, color = pal.accent, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(pal.accent.copy(alpha = 0.15f)).padding(horizontal = 7.dp, vertical = 2.dp))
                    }
                }
                Text(listOfNotNull(s.artist, s.album).joinToString(" · "), color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Box(Modifier.width(70.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(pal.text.copy(alpha = 0.1f))) {
                        Box(Modifier.fillMaxWidth(s.confidence.toFloat().coerceIn(0.03f, 1f)).height(4.dp).background(pal.accent))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("${(s.confidence * 100).toInt()}% likely", color = pal.textFaint, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (s.song != null && done.containsKey(s.song.videoId)) Icon(Icons.Rounded.CheckCircle, "Downloaded", tint = pal.accent, modifier = Modifier.size(22.dp))
        }
        if (expanded) {
            Column(Modifier.padding(start = 24.dp, top = 8.dp)) {
                s.reasons.take(3).forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = pal.textFaint, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(r, color = pal.textDim, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val song = s.song
                    if (song != null) {
                        val isDone = done.containsKey(song.videoId)
                        Pill(if (isDone) "Downloaded ✓" else "Download", !isDone, {
                            val n = DownloadManager.enqueueSong(song)
                            onToast(if (n > 0) "Downloading “${song.title}”" else "Already downloaded or in the queue")
                        })
                        Pill("Download & play", false, {
                            Preview.stop()
                            DownloadManager.enqueueSong(song)
                            onDownloadAndPlay(s)
                            if (!isDone) onToast("Downloading “${song.title}” — it plays when it's ready")
                        })
                    }
                    val previewing = preview.id == key
                    if (s.previewUrl != null || song != null) Pill(
                        when { previewing && preview.loading -> "Loading…"; previewing -> "Stop preview"; else -> "Preview" }, previewing, {
                            scope.launch {
                                Preview.toggle(key, s.previewUrl, song?.videoId) { if (player.isPlaying) player.togglePlay() }
                                Preview.state.value.error?.let(onToast)
                            }
                        })
                    song?.album?.id?.let { id -> Pill("Open album", false, { Preview.stop(); onOpenAlbum(id) }) }
                    song?.artists?.firstOrNull { it.id != null }?.id?.let { id -> Pill("Artist", false, { Preview.stop(); onOpenArtist(id) }) }
                    if (song == null) Text("Not found on YouTube Music — search for it above.", color = pal.textFaint, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Download / remove the song finder's AI models. */
@Composable
fun FinderModelsCard(onlyMissing: Boolean = false) {
    val pal = LocalPalette.current
    val st by AiModels.status.collectAsState()
    val models = AiModels.Model.entries.filter { it.forFinder && (!onlyMissing || it !in st.installed) }
    if (models.isEmpty()) return
    Column(Modifier.padding(16.dp)) {
        SectionTitle("Song finder AI")
        Text(
            "Runs on your phone. Downloaded once, then works offline (matching the sound still needs the internet).",
            color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp),
        )
        for (m in models) {
            val installed = m in st.installed
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.title, color = pal.text, fontWeight = FontWeight.SemiBold)
                    Text("${m.purpose} · ${m.bytes / 1_000_000} MB", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
                    if (st.downloading == m) LinearProgressIndicator(
                        progress = { st.progress }, color = pal.accent, trackColor = pal.text.copy(alpha = 0.1f),
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    )
                }
                Spacer(Modifier.width(8.dp))
                when {
                    st.downloading == m -> Text("${(st.progress * 100).toInt()}%", color = pal.textDim)
                    installed -> Pill("Remove", false, { AiModels.remove(m) })
                    else -> Pill("Download", true, { SongFinder.downloadModel(m) }, enabled = st.downloading == null)
                }
            }
        }
        st.error?.let { Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall) }
    }
}
