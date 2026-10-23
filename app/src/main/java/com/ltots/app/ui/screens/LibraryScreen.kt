package com.ltots.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ltots.app.audio.AudioEngine
import com.ltots.app.ui.topInset
import com.ltots.app.data.LocalLibrary
import com.ltots.app.data.Mix
import com.ltots.app.data.Mixes
import com.ltots.app.data.SongSort
import com.ltots.app.data.SongSorter
import com.ltots.app.data.Track
import com.ltots.app.ui.bottomChrome
import com.ltots.app.ui.PlayerUi
import com.ltots.app.ui.components.Logo
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.formatTime
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.glass.glass
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.LocalThemeConfig
import java.util.Locale

private enum class LibView { SONGS, ALBUMS, ARTISTS, MIXES }

private data class Group(val title: String, val subtitle: String, val tracks: List<Track>)

@Composable
fun LibraryScreen(player: PlayerUi, onOpenPlayer: () -> Unit) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun granted() = ContextCompat.checkSelfPermission(context, audioPerm) == PackageManager.PERMISSION_GRANTED
    var hasPerm by remember { mutableStateOf(granted()) }
    var reload by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPerm = granted(); reload++
    }
    LaunchedEffect(Unit) {
        if (!hasPerm) {
            val perms = buildList {
                add(audioPerm)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            launcher.launch(perms.toTypedArray())
        }
    }

    val downloadsDone by com.ltots.app.download.DownloadManager.libraryVersion.collectAsState()
    // Shown straight from memory when the tab opens again; only re-read when something changed.
    val tracks by produceState(if (hasPerm) LocalLibrary.cached() ?: emptyList() else emptyList(), hasPerm, reload, downloadsDone) {
        value = if (hasPerm) LocalLibrary.load(context, version = reload * 100_003 + downloadsDone) else emptyList()
    }

    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf(LibView.SONGS) }
    var openGroup by remember { mutableStateOf<Group?>(null) }
    BackHandler(enabled = openGroup != null) { openGroup = null }

    // The order of the Songs list (and so what plays next): a button's pick on this page, kept
    // until the app restarts, or else the one in App settings. Neither changes the other.
    val settings by AudioEngine.settings.collectAsState()
    val quickSort by SongSorter.quick.collectAsState()
    val sortBy = quickSort ?: settings.songSort
    val colors by SongSorter.colors.collectAsState()
    if (sortBy == SongSort.COLOR) LaunchedEffect(tracks) { SongSorter.findColors(context, tracks) }

    val filtered = remember(tracks, query) {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) tracks else tracks.filter {
            it.title.lowercase(Locale.ROOT).contains(q) || it.artist.lowercase(Locale.ROOT).contains(q) ||
                it.album.lowercase(Locale.ROOT).contains(q)
        }
    }

    // The top of the page: on one liquid-glass panel (Look > Glass > Glass Library top), or
    // straight on the background. On the panel, the search box and shuffle button are plain
    // tinted shapes rather than glass on glass.
    val headerGlass = LocalThemeConfig.current.libraryHeaderGlass
    val inner = pal.text.copy(alpha = 0.08f)
    val top: @Composable ColumnScope.() -> Unit = {
        // Header
        Row(Modifier.padding(top = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("LToTS", style = MaterialTheme.typography.displaySmall, color = pal.text)
                Text(
                    if (tracks.isEmpty()) "Your music" else "${tracks.size} songs on this phone",
                    style = MaterialTheme.typography.bodySmall, color = pal.textDim,
                )
            }
        }

        // Search
        val searchShape = RoundedCornerShape(26.dp)
        val searchBox: @Composable (@Composable BoxScope.() -> Unit) -> Unit = { content ->
            if (headerGlass) Box(Modifier.fillMaxWidth().height(52.dp).clip(searchShape).background(inner), content = content)
            else GlassPanel(Modifier.fillMaxWidth().height(52.dp), shape = searchShape, strength = 0.8f, content = content)
        }
        searchBox {
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, tint = pal.textDim)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Search songs, artists, albums", color = pal.textFaint)
                    BasicTextField(
                        query, { query = it }, singleLine = true,
                        textStyle = TextStyle(color = pal.text, fontSize = 15.sp),
                        cursorBrush = SolidColor(pal.accent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }, Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, "Clear", tint = pal.textDim)
                }
            }
        }

        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Songs", view == LibView.SONGS, { view = LibView.SONGS; openGroup = null })
            Pill("Albums", view == LibView.ALBUMS, { view = LibView.ALBUMS; openGroup = null })
            Pill("Artists", view == LibView.ARTISTS, { view = LibView.ARTISTS; openGroup = null })
            Pill("Mixes", view == LibView.MIXES, { view = LibView.MIXES; openGroup = null })
            Spacer(Modifier.weight(1f))
            if (filtered.isNotEmpty()) {
                Box(
                    (if (headerGlass) Modifier.clip(CircleShape).background(inner) else Modifier.glass(CircleShape, strength = 0.6f)).clip(CircleShape)
                        .clickable { player.play(filtered, (filtered.indices).random(), shuffle = true); onOpenPlayer() }
                        .padding(9.dp)
                ) { Icon(Icons.Rounded.Shuffle, "Shuffle all", tint = pal.text) }
            }
        }

        if (hasPerm && tracks.isNotEmpty() && openGroup == null && view == LibView.SONGS) {
            val readingColors = remember(tracks, colors, sortBy) { sortBy == SongSort.COLOR && tracks.any { it.id !in colors } }
            SortBar(sortBy, quickSort, readingColors)
        }
    }

    Column(Modifier.fillMaxSize().padding(top = topInset()).padding(horizontal = 16.dp)) {
        if (headerGlass) {
            GlassPanel(Modifier.fillMaxWidth().padding(top = 8.dp), tintBoost = 0.04f) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 4.dp), content = top)
            }
            Spacer(Modifier.height(8.dp))
        } else Column(content = top)

        // The list is the rest of the page: no box around it. It runs down behind the mini player
        // and the nav bar, and ends with enough room for the last song to scroll clear of them.
        val bottom = bottomChrome()
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                !hasPerm -> EmptyState(
                    "LToTS needs access to your music",
                    "Allow \"Music and audio\" so the app can find songs on your phone.",
                    "Allow access",
                ) { launcher.launch(arrayOf(audioPerm)) }
                tracks.isEmpty() -> EmptyState("No songs found", "Put some music files on your phone (any folder) and they'll show up here.", null) {}
                openGroup != null -> GroupDetail(openGroup!!, player, bottom, onBack = { openGroup = null }, onPlayed = onOpenPlayer)
                view == LibView.SONGS -> SongList(
                    remember(filtered, sortBy, if (sortBy == SongSort.COLOR) colors else null) { SongSorter.sort(filtered, sortBy, colors) },
                    player, bottom, onOpenPlayer,
                )
                view == LibView.MIXES -> MixGrid(remember(filtered) { Mixes.build(filtered) }, bottom) { m ->
                    openGroup = Group(m.title, "${m.kind.label} · ${m.subtitle}", m.tracks)
                }
                view == LibView.ALBUMS -> AlbumGrid(
                    remember(filtered) {
                        filtered.groupBy { it.albumId }.values.map { g ->
                            Group(g.first().album.ifBlank { "Unknown album" }, g.first().artist.takeIf { it != Track.UNKNOWN } ?: "", g)
                        }.sortedBy { it.title.lowercase(Locale.ROOT) }
                    },
                    bottom,
                ) { openGroup = it }
                else -> ArtistList(
                    remember(filtered) {
                        filtered.groupBy { if (it.artist == Track.UNKNOWN) "Unknown artist" else it.artist }.map { (a, g) ->
                            Group(a, "${g.size} song${if (g.size == 1) "" else "s"}", g)
                        }.sortedBy { it.title.lowercase(Locale.ROOT) }
                    },
                    bottom,
                ) { openGroup = it }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, action: String?, onAction: () -> Unit) {
    val pal = LocalPalette.current
    Column(
        Modifier.fillMaxSize().padding(bottom = bottomChrome()).padding(28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = pal.accent, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = pal.text)
        Spacer(Modifier.height(6.dp))
        Text(body, color = pal.textDim, style = MaterialTheme.typography.bodyMedium)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Pill(action, selected = true, onClick = onAction)
        }
    }
}

/**
 * Above the songs: how they're sorted, and the Colour and Genre buttons to sort by that just
 * for now (tap the lit one again to go back to the order from App settings).
 */
@Composable
private fun SortBar(sortBy: SongSort, quick: SongSort?, readingColors: Boolean) {
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Sorted by ${sortBy.label}", color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    readingColors -> "Reading cover colours…"
                    quick != null -> "Just for now"
                    else -> "From settings"
                },
                color = pal.textFaint, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            )
        }
        for (s in listOf(SongSort.COLOR, SongSort.GENRE)) {
            Pill(s.label, sortBy == s, { SongSorter.setQuick(if (quick == s) null else s) })
        }
    }
}

@Composable
private fun SongList(tracks: List<Track>, player: PlayerUi, bottom: Dp, onPlayed: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 2.dp, bottom = bottom)) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            TrackRow(t, playing = player.trackId == t.id) { player.play(tracks, i); onPlayed() }
        }
    }
}

@Composable
private fun TrackRow(t: Track, playing: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(t.id, 48)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title, color = if (playing) pal.accent else pal.text, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(t.artist.takeIf { it != Track.UNKNOWN }, t.qualityLabel).joinToString("  ·  ")
            Text(sub, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatTime(t.durationMs), color = pal.textFaint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Thumb(trackId: Long, sizeDp: Int) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val bmp by produceState<android.graphics.Bitmap?>(null, trackId) { value = LocalLibrary.artwork(context, trackId, 192) }
    Box(
        Modifier.size(sizeDp.dp).clip(RoundedCornerShape(10.dp)).background(pal.text.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(remember(b) { b.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, tint = pal.textFaint, modifier = Modifier.size((sizeDp / 2).dp))
    }
}

@Composable
private fun AlbumGrid(groups: List<Group>, bottom: Dp, onOpen: (Group) -> Unit) {
    val pal = LocalPalette.current
    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 4.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(groups, key = { it.tracks.first().albumId }) { g ->
            Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpen(g) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f)) { BigThumb(g.tracks.first().id) }
                Spacer(Modifier.height(6.dp))
                Text(g.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(g.subtitle, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Playlists the app made from the library (see [Mixes]), in sections: games & soundtracks,
 * decades, genres. Each card shows its first song's cover.
 */
@Composable
private fun MixGrid(mixes: List<Mix>, bottom: Dp, onOpen: (Mix) -> Unit) {
    val pal = LocalPalette.current
    if (mixes.isEmpty()) {
        EmptyState(
            "No mixes yet",
            "Mixes appear by themselves once there are ${Mixes.MIN_SONGS} or more songs from the same game or soundtrack, the same decade or the same genre.",
            null,
        ) {}
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 4.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for ((kind, group) in mixes.groupBy { it.kind }) {
            item(key = "head:${kind.name}", span = { GridItemSpan(maxLineSpan) }) {
                Text(kind.label, color = pal.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            }
            items(group, key = { it.id }) { m ->
                Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpen(m) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f)) { BigThumb(m.tracks.first().id) }
                    Spacer(Modifier.height(6.dp))
                    Text(m.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.subtitle, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun BigThumb(trackId: Long) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val bmp by produceState<android.graphics.Bitmap?>(null, trackId) { value = LocalLibrary.artwork(context, trackId, 384) }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(pal.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(remember(b) { b.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.Album, null, tint = pal.textFaint, modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun ArtistList(groups: List<Group>, bottom: Dp, onOpen: (Group) -> Unit) {
    val pal = LocalPalette.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 2.dp, bottom = bottom)) {
        items(groups, key = { it.title }) { g ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onOpen(g) }.padding(horizontal = 4.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(pal.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Person, null, tint = pal.accent)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(g.subtitle, color = pal.textDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun GroupDetail(g: Group, player: PlayerUi, bottom: Dp, onBack: () -> Unit, onPlayed: () -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = pal.text) }
            Column(Modifier.weight(1f)) {
                Text(g.title, style = MaterialTheme.typography.titleMedium, color = pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(g.subtitle, style = MaterialTheme.typography.bodySmall, color = pal.textDim, maxLines = 1)
            }
            IconButton(onClick = { player.play(g.tracks, 0); onPlayed() }) {
                Icon(Icons.Rounded.PlayArrow, "Play", tint = pal.accent, modifier = Modifier.size(30.dp))
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
            itemsIndexed(g.tracks, key = { _, t -> t.id }) { i, t ->
                TrackRow(t, playing = player.trackId == t.id) { player.play(g.tracks, i); onPlayed() }
            }
        }
    }
}
