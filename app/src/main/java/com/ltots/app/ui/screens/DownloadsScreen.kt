package com.ltots.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ltots.app.data.Track
import com.ltots.app.ui.topInset
import com.ltots.app.download.DownloadManager
import com.ltots.app.download.DownloadTask
import com.ltots.app.download.Engine
import com.ltots.app.download.Suggestions
import com.ltots.app.download.SyncedPlaylist
import com.ltots.app.download.TaskState
import com.ltots.app.ui.bottomChrome
import com.ltots.app.ui.Inbox
import com.ltots.app.ui.PlayerUi
import com.ltots.app.ui.components.NetImage
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.components.SwitchRow
import com.ltots.app.ui.components.formatTime
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ytm.ArtistPage
import com.ltots.app.ytm.Collection
import com.ltots.app.ytm.InnerTube
import com.ltots.app.ytm.LinkTarget
import com.ltots.app.ytm.Links
import com.ltots.app.ytm.SearchResult
import com.ltots.app.ytm.YtAlbum
import com.ltots.app.ytm.YtArtist
import com.ltots.app.ytm.YtItem
import com.ltots.app.ytm.YtPlaylist
import com.ltots.app.ytm.YtSong
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One screen in the Downloads tab's back stack. */
private sealed interface Page {
    data class Search(val query: String, val params: String? = null) : Page
    data class AlbumPage(val id: String) : Page
    data class PlaylistPage(val id: String) : Page
    data class Artist(val id: String) : Page
}

private sealed interface Loaded {
    data object Loading : Loaded
    data class Error(val message: String) : Loaded
    data class Results(val result: SearchResult) : Loaded
    data class Coll(val c: Collection) : Loaded
    data class Art(val a: ArtistPage) : Loaded
}

@Composable
fun DownloadsScreen(player: PlayerUi, onOpenPlayer: () -> Unit) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var input by rememberSaveable { mutableStateOf("") }
    val stack = remember { mutableStateListOf<Page>() }
    var loaded by remember { mutableStateOf<Loaded?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(3200); toast = null } }

    // Warm up the engine in the background and check for a newer yt-dlp once a week.
    // Weekly engine update — never while songs are downloading (it replaces yt-dlp's file).
    LaunchedEffect(Unit) { if (Engine.ensureReady() && !DownloadManager.hasWork()) Engine.autoUpdateIfDue() }

    val page = stack.lastOrNull()
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }

    // Load whatever page is on top of the stack.
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(page, reload) {
        val p = page ?: run { loaded = null; return@LaunchedEffect }
        loaded = Loaded.Loading
        loaded = runCatching<Loaded> {
            when (p) {
                is Page.Search -> Loaded.Results(InnerTube.search(p.query, p.params))
                is Page.AlbumPage -> Loaded.Coll(InnerTube.album(p.id))
                is Page.PlaylistPage -> Loaded.Coll(InnerTube.playlist(p.id))
                is Page.Artist -> Loaded.Art(InnerTube.artist(p.id))
            }
        }.getOrElse { Loaded.Error(it.message ?: "Couldn't reach YouTube Music. Check your connection.") }
    }

    fun open(item: YtItem) {
        when (item) {
            is YtSong -> {
                val n = DownloadManager.enqueueSong(item)
                toast = if (n > 0) "Downloading “${item.title}”" else "Already downloaded or in the queue"
            }
            is YtAlbum -> stack += Page.AlbumPage(item.browseId)
            is YtPlaylist -> stack += Page.PlaylistPage(item.playlistId)
            is YtArtist -> stack += Page.Artist(item.browseId)
        }
    }

    fun submit(text: String, fromShare: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        focus.clearFocus()
        when (val link = Links.parse(t)) {
            is LinkTarget.Song -> scope.launch {
                toast = "Looking up the song…"
                val s = runCatching { InnerTube.song(link.videoId) }.getOrNull()
                    ?: YtSong(link.videoId, com.ltots.app.download.Downloader.PLACEHOLDER_TITLE, emptyList(), isVideo = true)
                val n = DownloadManager.enqueueSong(s)
                toast = if (n > 0) "Downloading “${s.title}”" else "Already downloaded or in the queue"
                if (fromShare) input = ""
            }
            is LinkTarget.Album -> stack += Page.AlbumPage(link.browseId)
            is LinkTarget.Playlist -> stack += Page.PlaylistPage(link.playlistId)
            is LinkTarget.Artist -> stack += Page.Artist(link.channelId)
            null -> {
                if (t.startsWith("http")) { toast = "That link isn't a YouTube or YouTube Music link"; return }
                stack.clear(); stack += Page.Search(t)
            }
        }
    }

    // A link shared to LToTS from another app.
    val shared by Inbox.sharedText.collectAsState()
    LaunchedEffect(shared) {
        val s = shared ?: return@LaunchedEffect
        Inbox.sharedText.value = null
        input = Links.parse(s)?.let { s.trim() } ?: s
        submit(s, fromShare = true)
    }

    Column(Modifier.fillMaxSize().padding(top = topInset()).padding(horizontal = 14.dp)) {
        // Header
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (stack.isNotEmpty()) {
                IconButton(onClick = { stack.removeAt(stack.lastIndex) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = pal.text) }
            }
            Column(Modifier.weight(1f)) {
                Text("Get music", style = MaterialTheme.typography.displaySmall, color = pal.text)
                Text("From YouTube Music, saved to Music/LToTS", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
            }
        }

        // Search / paste box
        GlassPanel(Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp), strength = 0.8f) {
            Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = pal.textDim)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (input.isEmpty()) Text("Search or paste a YouTube Music link", color = pal.textFaint, maxLines = 1)
                    BasicTextField(
                        input, { input = it }, singleLine = true,
                        textStyle = TextStyle(color = pal.text, fontSize = 15.sp),
                        cursorBrush = SolidColor(pal.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit(input) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (input.isNotEmpty()) {
                    IconButton(onClick = { input = "" }, Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "Clear", tint = pal.textDim) }
                } else {
                    IconButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = cb.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (text.isNullOrBlank()) toast = "Clipboard is empty" else { input = text; submit(text) }
                    }, Modifier.size(36.dp)) { Icon(Icons.Rounded.ContentPaste, "Paste link", tint = pal.textDim) }
                }
            }
        }

        AnimatedVisibility(toast != null) {
            Text(
                toast ?: "", color = pal.onAccent, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(50)).background(pal.accent).padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        if (loaded == null) {
            Intro()
            Spacer(Modifier.height(10.dp))
        }

        GlassPanel(Modifier.fillMaxWidth().weight(1f), tintBoost = 0.06f) {
            when (val l = loaded) {
                null -> HomeContent(player, onOpenPlayer, onToast = { toast = it })
                Loaded.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = pal.accent) }
                is Loaded.Error -> Message(l.message, "Try again") { reload++ }
                is Loaded.Results -> SearchContent(l.result, (page as? Page.Search)?.params,
                    onChip = { chip -> (page as? Page.Search)?.let { p -> stack[stack.lastIndex] = p.copy(params = if (chip.selected) null else chip.params) } },
                    onOpen = ::open)
                is Loaded.Coll -> CollectionContent(l.c, onToast = { toast = it }, onArtist = { stack += Page.Artist(it) })
                is Loaded.Art -> ArtistContent(l.a, onOpen = ::open)
            }
        }
        Spacer(Modifier.height(bottomChrome()))
    }
}

@Composable
private fun Message(text: String, action: String?, onAction: () -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = pal.textDim, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(text, color = pal.textDim)
        if (action != null) { Spacer(Modifier.height(12.dp)); Pill(action, true, onAction) }
    }
}

// ---- rows ------------------------------------------------------------------------------------

@Composable
private fun DownloadBadge(videoId: String) {
    val pal = LocalPalette.current
    val done by DownloadManager.downloaded.collectAsState()
    val tasks by DownloadManager.tasks.collectAsState()
    val t = tasks.firstOrNull { it.meta.videoId == videoId && !it.state.finished }
    when {
        done.containsKey(videoId) -> Icon(Icons.Rounded.CheckCircle, "Downloaded", tint = pal.accent, modifier = Modifier.size(24.dp))
        t != null && t.state == TaskState.DOWNLOADING -> CircularProgressIndicator(
            progress = { t.progress }, color = pal.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp),
            trackColor = pal.text.copy(alpha = 0.12f),
        )
        t != null -> CircularProgressIndicator(color = pal.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
        else -> Icon(Icons.Rounded.Download, "Download", tint = pal.textDim, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ItemRow(item: YtItem, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    val pal = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        val (shape, icon) = when (item) {
            is YtArtist -> CircleShape to Icons.Rounded.Person
            is YtAlbum -> RoundedCornerShape(10.dp) to Icons.Rounded.Album
            is YtPlaylist -> RoundedCornerShape(10.dp) to Icons.AutoMirrored.Rounded.QueueMusic
            else -> RoundedCornerShape(10.dp) to Icons.Rounded.Download
        }
        NetImage(item.thumbUrl, 50.dp, shape, placeholder = icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when (item) {
                is YtSong -> listOfNotNull(
                    if (item.isVideo) "Video" else "Song",
                    item.artistText.ifBlank { null }, item.album?.name, item.durationSec?.let { formatTime(it * 1000L) },
                )
                is YtAlbum -> listOfNotNull(item.kind, item.artist.ifBlank { null }, item.year)
                is YtPlaylist -> listOfNotNull("Playlist", item.author.ifBlank { null }, item.countText)
                is YtArtist -> listOfNotNull("Artist", item.subtitle)
            }.joinToString(" · ")
            Text(sub, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (item is YtSong) DownloadBadge(item.videoId)
    }
}

// ---- search ----------------------------------------------------------------------------------

@Composable
private fun SearchContent(r: SearchResult, params: String?, onChip: (com.ltots.app.ytm.SearchChip) -> Unit, onOpen: (YtItem) -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        if (r.chips.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.chips.forEach { c -> Pill(c.text, c.selected || (params != null && c.params == params), { onChip(c) }) }
            }
        }
        val all = r.sections.sumOf { it.items.size }
        if (all == 0) {
            Message("Nothing found. Try other words, or paste a link.", null) {}
            return@Column
        }
        // Songs like the one found (the first song in the results), shown after the first section.
        val context = LocalContext.current
        val seed = remember(r) { r.sections.flatMap { it.items }.filterIsInstance<YtSong>().let { l -> l.firstOrNull { !it.isVideo } ?: l.firstOrNull() } }
        var alsoLike by remember(r) { mutableStateOf<List<YtSong>>(emptyList()) }
        LaunchedEffect(seed) { if (seed != null) alsoLike = runCatching { Suggestions.forSong(context, seed) }.getOrDefault(emptyList()) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
            // Keys include the position: YouTube sometimes lists the same song twice.
            r.sections.forEachIndexed { si, s ->
                s.title?.let { t -> item(key = "h$si") { SectionTitle(t, Modifier.padding(start = 12.dp, top = 8.dp)) } }
                itemsIndexed(s.items, key = { idx, it -> "$si-$idx-" + keyOf(it) }) { _, it -> ItemRow(it, onClick = { onOpen(it) }) }
                if (si == 0 && alsoLike.isNotEmpty() && seed != null) {
                    item(key = "likeh") {
                        Column(Modifier.padding(start = 12.dp, top = 8.dp)) {
                            SectionTitle("You might also like")
                            Text("Songs like “${seed.title}”", color = pal.textFaint, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    itemsIndexed(alsoLike, key = { idx, it -> "like-$idx-" + it.videoId }) { _, it -> ItemRow(it, onClick = { onOpen(it) }) }
                }
            }
            item {
                Text(
                    "Tap a song to download it. Albums, playlists and artists open first.",
                    color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

private fun keyOf(i: YtItem) = when (i) {
    is YtSong -> "s" + i.videoId
    is YtAlbum -> "a" + i.browseId
    is YtPlaylist -> "p" + i.browseId
    is YtArtist -> "r" + i.browseId
}

// ---- album / playlist ------------------------------------------------------------------------

@Composable
private fun CollectionContent(c: Collection, onToast: (String) -> Unit, onArtist: (String) -> Unit) {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    val done by DownloadManager.downloaded.collectAsState()
    val synced by DownloadManager.synced.collectAsState()
    val selected = remember(c.id) { mutableStateListOf<String>().apply { addAll(c.tracks.map { it.videoId }) } }
    val missing = c.tracks.count { !done.containsKey(it.videoId) }
    val isSynced = synced.any { it.playlistId == c.id.removePrefix("VL") }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        item("header") {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NetImage(c.thumbUrl, 112.dp, RoundedCornerShape(16.dp), placeholder = if (c.isAlbum) Icons.Rounded.Album else Icons.AutoMirrored.Rounded.QueueMusic, px = 400)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.titleMedium, color = pal.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(c.subtitle, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Text(
                            "${c.tracks.size} songs · " + if (missing == 0) "all downloaded" else "$missing not downloaded yet",
                            color = pal.textFaint, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val sel = selected.toSet()
                    val n = c.tracks.count { it.videoId in sel && !done.containsKey(it.videoId) }
                    Pill(if (n == 0) "Nothing new" else "Download $n", n > 0, {
                        val added = if (c.isAlbum) DownloadManager.enqueueAlbum(c, sel) else DownloadManager.enqueuePlaylist(c, sel)
                        onToast(if (added > 0) "Added $added song${if (added == 1) "" else "s"} to downloads" else "Already downloaded")
                    })
                    if (!c.isAlbum) {
                        Pill(if (isSynced) "Synced ✓" else "Keep in sync", isSynced, {
                            if (isSynced) {
                                DownloadManager.forgetPlaylist(c.id.removePrefix("VL")); onToast("Stopped syncing this playlist")
                            } else {
                                val added = DownloadManager.enqueuePlaylist(c, remember = true)
                                onToast("Syncing — $added new song${if (added == 1) "" else "s"} queued")
                            }
                        })
                    }
                    Pill(if (selected.size == c.tracks.size) "Select none" else "Select all", false, {
                        if (selected.size == c.tracks.size) selected.clear() else { selected.clear(); selected.addAll(c.tracks.map { it.videoId }) }
                    })
                }
            }
        }
        itemsIndexed(c.tracks, key = { idx, it -> "t$idx-" + it.videoId }) { _, t ->
            val on = t.videoId in selected
            ItemRow(t, onClick = { if (on) selected.remove(t.videoId) else selected.add(t.videoId) }, leading = {
                Checkbox(
                    checked = on || done.containsKey(t.videoId),
                    onCheckedChange = { if (it) selected.add(t.videoId) else selected.remove(t.videoId) },
                    enabled = !done.containsKey(t.videoId),
                    colors = CheckboxDefaults.colors(checkedColor = pal.accent, uncheckedColor = pal.textFaint, checkmarkColor = pal.onAccent),
                )
            })
        }
    }
}

// ---- artist ----------------------------------------------------------------------------------

@Composable
private fun ArtistContent(a: ArtistPage, onOpen: (YtItem) -> Unit) {
    val pal = LocalPalette.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        item("head") {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                NetImage(a.thumbUrl, 72.dp, CircleShape, placeholder = Icons.Rounded.Person, px = 300)
                Spacer(Modifier.width(14.dp))
                Text(a.name, style = MaterialTheme.typography.headlineSmall, color = pal.text)
            }
        }
        if (a.sections.isEmpty()) item { Text("Nothing to show for this artist here.", color = pal.textDim, modifier = Modifier.padding(16.dp)) }
        a.sections.forEachIndexed { i, s ->
            s.title?.let { t -> item("h$i") { SectionTitle(t, Modifier.padding(start = 12.dp, top = 8.dp)) } }
            itemsIndexed(s.items, key = { idx, it -> "$i-$idx-" + keyOf(it) }) { _, it -> ItemRow(it, onClick = { onOpen(it) }) }
        }
    }
}

// ---- home: queue, synced playlists, history, engine -----------------------------------------

@Composable
private fun HomeContent(player: PlayerUi, onOpenPlayer: () -> Unit, onToast: (String) -> Unit) {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    val tasks by DownloadManager.tasks.collectAsState()
    val synced by DownloadManager.synced.collectAsState()
    val paused by DownloadManager.paused.collectAsState()
    val active = tasks.filter { !it.state.finished }
    val history = tasks.filter { it.state.finished }

    // Suggestions: songs like the last few downloads (or the newest songs on the phone),
    // looked up again when a new download finishes.
    val context = LocalContext.current
    val suggested by Suggestions.home.collectAsState()
    var suggesting by remember { mutableStateOf(false) }
    val lastDone = history.filter { it.state == TaskState.DONE }.maxByOrNull { it.finishedAt }?.id
    LaunchedEffect(lastDone) { suggesting = true; runCatching { Suggestions.loadHome(context) }; suggesting = false }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        if (active.isNotEmpty()) {
            item("qh") {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Downloading · ${active.size}", Modifier.weight(1f))
                    IconButton(onClick = { DownloadManager.setPaused(!paused) }) {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Resume" else "Pause", tint = pal.text)
                    }
                    Pill("Cancel all", false, { DownloadManager.cancelAll() })
                }
            }
            items(active, key = { "q" + it.id }) { TaskRow(it, player, onOpenPlayer) }
        }

        val sug = suggested
        if (sug != null && sug.basedOn.isNotEmpty() || suggesting) {
            item("sugh") {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        SectionTitle("Suggested for you")
                        if (sug != null && sug.basedOn.isNotEmpty()) Text(
                            "Like " + sug.basedOn.joinToString(", ") { "“$it”" },
                            color = pal.textFaint, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (suggesting) CircularProgressIndicator(color = pal.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                    else Pill("New picks", false, {
                        scope.launch { suggesting = true; runCatching { Suggestions.loadHome(context, force = true) }; suggesting = false }
                    })
                }
            }
            if (sug != null && sug.songs.isEmpty() && !suggesting && sug.basedOn.isNotEmpty()) item("sug0") {
                Text("No suggestions right now. Check your connection and tap New picks.", color = pal.textDim,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            if (sug != null) items(sug.songs, key = { "sg" + it.videoId }) { song ->
                ItemRow(song, onClick = {
                    val n = DownloadManager.enqueueSong(song)
                    onToast(if (n > 0) "Downloading “${song.title}”" else "Already downloaded or in the queue")
                })
            }
        }

        if (synced.isNotEmpty()) {
            item("sh") {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Synced playlists", Modifier.weight(1f))
                    Pill("Sync all", false, {
                        scope.launch { onToast("Checking playlists…"); val n = DownloadManager.syncAll(); onToast(if (n == 0) "Everything is up to date" else "$n new songs queued") }
                    })
                }
            }
            items(synced, key = { "p" + it.playlistId }) { SyncedRow(it, onToast) }
        }

        if (history.isNotEmpty()) {
            item("hh") {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Recent", Modifier.weight(1f))
                    if (history.any { it.state == TaskState.FAILED }) {
                        Pill("Retry failed", false, { DownloadManager.retryFailed() })
                        Spacer(Modifier.width(8.dp))
                    }
                    Pill("Clear", false, { DownloadManager.clearFinished() })
                }
            }
            items(history.take(150), key = { "h" + it.id }) { TaskRow(it, player, onOpenPlayer) }
        }

        item("engine") { EngineCard(onToast) }
    }
}

@Composable
private fun Intro() {
    val pal = LocalPalette.current
    Column(Modifier.padding(18.dp)) {
        Text("Download from YouTube Music", style = MaterialTheme.typography.titleMedium, color = pal.text)
        Spacer(Modifier.height(6.dp))
        listOf(
            "Search above, or paste a link to a song, album, playlist or artist.",
            "Or share straight from the YouTube Music app: Share → LToTS.",
            "Songs keep YouTube's original audio (Opus) with no re-encoding, plus full album tags and the full-size cover.",
            "Music videos are swapped for the official audio release when there is one.",
        ).forEach { Text("•  $it", color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp)) }
    }
}

@Composable
private fun TaskRow(t: DownloadTask, player: PlayerUi, onOpenPlayer: () -> Unit) {
    val pal = LocalPalette.current
    val m = t.meta
    var showLog by remember(t.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable(enabled = (t.state == TaskState.DONE && t.savedUri != null) || t.state == TaskState.FAILED) {
            if (t.state == TaskState.FAILED) { showLog = !showLog; return@clickable }
            val uri = Uri.parse(t.savedUri)
            val track = Track(
                id = uri.lastPathSegment?.toLongOrNull() ?: -1L, uri = uri, title = m.title, artist = m.artist,
                album = m.album ?: "", albumId = -1, durationMs = (m.durationSec ?: 0) * 1000L,
                sampleRate = null, bitsPerSample = null, mime = null,
            )
            player.play(listOf(track), 0); onOpenPlayer()
        }.padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NetImage(m.coverUrl, 46.dp, RoundedCornerShape(10.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = when (t.state) {
                    TaskState.DONE -> listOfNotNull(m.artist, m.album, t.qualityNote).joinToString(" · ")
                    TaskState.FAILED -> t.error ?: "Failed"
                    TaskState.DOWNLOADING -> "${m.artist} · ${(t.progress * 100).toInt()}%"
                    else -> "${m.artist} · ${t.state.label}"
                }
                Text(
                    sub, color = if (t.state == TaskState.FAILED) Color(0xFFFF8A80) else pal.textDim,
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            when (t.state) {
                TaskState.FAILED, TaskState.CANCELED -> IconButton(onClick = { DownloadManager.retry(t.id) }) { Icon(Icons.Rounded.Refresh, "Retry", tint = pal.text) }
                TaskState.DONE, TaskState.SKIPPED -> Icon(Icons.Rounded.CheckCircle, null, tint = pal.accent, modifier = Modifier.padding(12.dp))
                else -> IconButton(onClick = { DownloadManager.cancel(t.id) }) { Icon(Icons.Rounded.Close, "Cancel", tint = pal.textDim) }
            }
        }
        if (t.state.active || t.state == TaskState.QUEUED) {
            if (t.state == TaskState.DOWNLOADING) LinearProgressIndicator(
                progress = { t.progress }, color = pal.accent, trackColor = pal.text.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
            ) else if (t.state != TaskState.QUEUED) LinearProgressIndicator(
                color = pal.accent, trackColor = pal.text.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
        if (showLog && t.state == TaskState.FAILED) {
            DetailsBox(t.log ?: (t.error ?: "No details were recorded."), "LToTS download log — ${m.title}")
        }
    }
}

/** Scrollable, copyable technical details (what yt-dlp said). */
@Composable
private fun DetailsBox(text: String, clipLabel: String) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(
            Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.35f)).verticalScroll(rememberScrollState()).padding(10.dp)
        ) {
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(text, color = pal.textDim, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Pill("Copy details", false, {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText(clipLabel, text))
        })
    }
}

@Composable
private fun SyncedRow(p: SyncedPlaylist, onToast: (String) -> Unit) {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        NetImage(p.thumbUrl, 46.dp, RoundedCornerShape(10.dp), placeholder = Icons.AutoMirrored.Rounded.QueueMusic)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${p.trackCount} songs · synced ${ago(p.lastSync)}", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
        }
        if (busy) CircularProgressIndicator(color = pal.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp).padding(end = 4.dp))
        else IconButton(onClick = {
            busy = true
            scope.launch {
                val n = runCatching { DownloadManager.sync(p.playlistId) }.getOrElse { onToast("Couldn't sync: ${it.message}"); -1 }
                if (n >= 0) onToast(if (n == 0) "“${p.title}” is up to date" else "$n new song${if (n == 1) "" else "s"} queued")
                busy = false
            }
        }) { Icon(Icons.Rounded.Sync, "Sync", tint = pal.text) }
        IconButton(onClick = { DownloadManager.forgetPlaylist(p.playlistId) }) { Icon(Icons.Rounded.Close, "Stop syncing", tint = pal.textFaint) }
    }
}

@Composable
private fun EngineCard(onToast: (String) -> Unit) {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    val engine by Engine.state.collectAsState()
    val settings by DownloadManager.settings.collectAsState()
    var testing by remember { mutableStateOf(false) }
    var testReport by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(16.dp)) {
        SectionTitle("Download settings")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads at once", color = pal.text, modifier = Modifier.weight(1f))
            (1..5).forEach { n ->
                Box(
                    Modifier.padding(start = 6.dp).size(34.dp).clip(CircleShape)
                        .background(if (settings.concurrency == n) pal.accent else pal.text.copy(alpha = 0.08f))
                        .clickable { DownloadManager.updateSettings { it.copy(concurrency = n) } },
                    contentAlignment = Alignment.Center,
                ) { Text("$n", color = if (settings.concurrency == n) pal.onAccent else pal.text, fontWeight = FontWeight.SemiBold) }
            }
        }
        SwitchRow(
            "Prefer official audio", settings.preferOfficialAudio, { on -> DownloadManager.updateSettings { it.copy(preferOfficialAudio = on) } },
            sub = "When you pick a music video, download the album version of the song instead (cleaner audio, proper tags).",
        )
        Spacer(Modifier.height(10.dp))
        SectionTitle("Download engine")
        Text(
            buildString {
                append("yt-dlp ").append(engine.version ?: Engine.BUNDLED_YTDLP)
                if (engine.lastCheck > 0) append(" · checked ").append(ago(engine.lastCheck))
                if (engine.preparing) append(" · starting…")
            },
            color = pal.textDim, style = MaterialTheme.typography.bodySmall,
        )
        engine.error?.let { Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (engine.updating) "Updating…" else "Update engine", false, {
                if (DownloadManager.hasWork()) onToast("Let the current downloads finish first")
                else if (!engine.updating) scope.launch { onToast(Engine.update()) }
            })
            Spacer(Modifier.width(8.dp))
            Pill(if (testing) "Testing…" else "Test engine", false, {
                if (!testing) { testing = true; scope.launch { testReport = Engine.selfTest(); testing = false } }
            })
        }
        Text("If downloads fail: Update engine, then Test engine. Tap a failed song to see why.",
            color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        testReport?.let { DetailsBox(it, "LToTS engine test") }
    }
}

private fun ago(t: Long): String {
    if (t <= 0) return "never"
    val m = (System.currentTimeMillis() - t) / 60000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / (60 * 24)} days ago"
    }
}
