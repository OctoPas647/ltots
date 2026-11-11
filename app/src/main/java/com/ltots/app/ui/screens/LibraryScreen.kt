package com.ltots.app.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.horizontalScroll
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.platform.LocalConfiguration
import com.ltots.app.ui.PageBars
import com.ltots.app.ui.rememberTopArea
import com.ltots.app.ui.components.GlassScrollbar
import com.ltots.app.ui.components.LocalScrollbarHost
import com.ltots.app.ui.components.RegisterScroll
import com.ltots.app.ui.components.ScrollLabel
import com.ltots.app.ui.components.ScrollbarHost
import com.ltots.app.ui.components.initialOf
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.ltots.app.ui.glass.LocalBackdrop
import com.ltots.app.ui.glass.LocalGlassOverContent
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
import com.ltots.app.ui.components.SegmentedToggle
import com.ltots.app.ui.components.formatTime
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.glass.glass
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.theme.LocalThemeConfig
import java.util.Locale

private enum class LibView { SONGS, ALBUMS, ARTISTS, MIXES, DUPLICATES }

private data class Group(val title: String, val subtitle: String, val tracks: List<Track>)

@Composable
fun LibraryScreen(player: PlayerUi, onOpenPlayer: () -> Unit, onOpenHistory: () -> Unit = {}) {
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
    val allTracks by produceState(if (hasPerm) LocalLibrary.cached() ?: emptyList() else emptyList(), hasPerm, reload, downloadsDone) {
        value = if (hasPerm) LocalLibrary.load(context, version = reload * 100_003 + downloadsDone) else emptyList()
    }

    // Songs or All: what isn't a song (recordings, voice notes, call recordings, ringtones, short
    // sounds…) is told apart by its folder, name and tags, and the rest by the AI listening to it.
    var showAll by rememberSaveable { mutableStateOf(false) }
    val kindVerdicts by com.ltots.app.data.SongKinds.verdicts.collectAsState()
    val sorting by com.ltots.app.data.SongKinds.progress.collectAsState()
    val aiModels by com.ltots.app.identify.AiModels.status.collectAsState()
    val kindsAi = com.ltots.app.identify.AiModels.Model.MOODS in aiModels.installed
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.ltots.app.data.SongKinds.load() } }
    LaunchedEffect(allTracks, kindsAi) { if (allTracks.isNotEmpty()) com.ltots.app.data.SongKinds.classify(context, allTracks) }
    val songsOnly = remember(allTracks, kindVerdicts) { com.ltots.app.data.SongKinds.songs(allTracks, kindVerdicts) }
    val tracks = if (showAll) allTracks else songsOnly
    // The same recording saved more than once (worked out off the screen's thread).
    val duplicates by produceState(emptyList<List<Track>>(), songsOnly) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.ltots.app.data.Duplicates.groups(songsOnly) }
    }

    var query by rememberSaveable { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf(LibView.SONGS) }
    var openGroup by remember { mutableStateOf<Group?>(null) }

    // The order of the Songs list (and so what plays next): a button's pick on this page, kept
    // until the app restarts, or else the one in App settings. Neither changes the other.
    val settings by AudioEngine.settings.collectAsState()
    val quickSort by SongSorter.quick.collectAsState()
    val sortBy = quickSort ?: settings.songSort
    val colors by SongSorter.colors.collectAsState()
    if (sortBy == SongSort.COLOR) LaunchedEffect(tracks) { SongSorter.findColors(context, tracks) }

    // Each song's real genre and popularity, looked up once per song in music databases.
    val genres by com.ltots.app.data.GenreFinder.genres.collectAsState()
    val popularity by com.ltots.app.data.GenreFinder.popularity.collectAsState()
    val lookingUp by com.ltots.app.data.GenreFinder.busy.collectAsState()
    LaunchedEffect(tracks) { if (tracks.isNotEmpty()) com.ltots.app.data.GenreFinder.find(tracks) }
    val plays by com.ltots.app.data.PlayStats.plays.collectAsState()
    // What the mood model heard in each song, for themes and AI albums.
    val moods by com.ltots.app.moods.MoodIndex.embeddings.collectAsState()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.ltots.app.moods.MoodIndex.load() } }
    var showFilter by remember { mutableStateOf(false) }

    // The library after the user's filters (hidden artists, popular songs only), with genres.
    val shown = remember(tracks, genres, popularity, plays, lookingUp, settings.hiddenArtists, settings.onlyHits, settings.onlyMostPlayed) {
        com.ltots.app.data.LibraryFilter.apply(com.ltots.app.data.GenreFinder.apply(tracks, genres), settings, popularity, plays, lookingUp)
    }

    val filtered = remember(shown, query) {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) shown else shown.filter {
            it.title.lowercase(Locale.ROOT).contains(q) || it.artist.lowercase(Locale.ROOT).contains(q) ||
                it.album.lowercase(Locale.ROOT).contains(q)
        }
    }

    // The top of the page: on one liquid-glass panel (Look > Glass > Glass Library top), or
    // straight on the background. On the panel, the search box and shuffle button are plain
    // tinted shapes rather than glass on glass.
    val cfg = LocalThemeConfig.current
    val density = LocalDensity.current
    val headerGlass = cfg.libraryHeaderGlass
    val inner = pal.text.copy(alpha = 0.08f)

    // The very top. By default the clock and status icons are hidden on this page and the menu
    // moves up to the top of the screen: the logo in the top-left corner, level with the camera
    // (kept inside the screen's rounded corner, and below the camera if it's right above).
    // With the clock shown (Look > Library page), the menu sits right below it.
    val compactTop = !cfg.libraryStatusBar
    PageBars.StatusBar(hide = compactTop && !cfg.hideStatusBar, show = cfg.libraryStatusBar && cfg.hideStatusBar)
    val area = rememberTopArea()
    val sidePad = if (headerGlass) 30.dp else 16.dp
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    var titleRowHeight by remember { mutableStateOf(56.dp) }
    val rowTop = if (!compactTop) topInset() + (if (headerGlass) 20.dp else 12.dp) else {
        // Logo and buttons sit centred in the title row.
        val logoTop = area.minTop(sidePad, 40.dp, 11.dp)
        val buttonTop = area.minTop(sidePad, 42.dp, 21.dp, fromRight = true, screenWidth = screenWidth)
        maxOf(logoTop - (titleRowHeight - 40.dp) / 2, buttonTop - (titleRowHeight - 42.dp) / 2, 0.dp)
    }
    // The title and song count stop short of a camera in the middle of the top edge.
    val textStart = sidePad + 40.dp + 12.dp
    val textMax = if (compactTop && area.hasCutout && area.cutoutBottom > rowTop && area.cutoutLeft > textStart + 60.dp)
        area.cutoutLeft - textStart - 10.dp else Dp.Unspecified

    val top: @Composable ColumnScope.() -> Unit = {
        // Header
        Row(
            Modifier.padding(top = rowTop, bottom = 14.dp).onSizeChanged { with(density) { titleRowHeight = it.height.toDp() } },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(40.dp)
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) { Column(Modifier.widthIn(max = textMax)) {
                Text("LToTS", style = MaterialTheme.typography.displaySmall, color = pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        allTracks.isEmpty() -> "Your music"
                        showAll -> "${allTracks.size} audio files · ${songsOnly.size} songs"
                        else -> "${songsOnly.size} songs on this phone"
                    },
                    style = MaterialTheme.typography.bodySmall, color = pal.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } }
            // Top right, opposite the logo: shuffle, then the listening history.
            val round = if (headerGlass) Modifier.clip(CircleShape).background(inner) else Modifier.glass(CircleShape, strength = 0.6f)
            if (filtered.isNotEmpty()) {
                Box(
                    round.clip(CircleShape)
                        .clickable { player.play(filtered, (filtered.indices).random(), shuffle = true); onOpenPlayer() }
                        .padding(9.dp)
                ) { Icon(Icons.Rounded.Shuffle, "Shuffle all", tint = pal.text) }
                Spacer(Modifier.width(8.dp))
            }
            Box(round.clip(CircleShape).clickable(onClick = onOpenHistory).padding(9.dp)) {
                Icon(Icons.Rounded.History, "History", tint = pal.text)
            }
        }

        // Songs / All: only real songs, or every audio file on the phone.
        Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SegmentedToggle(
                listOf("Songs", "All"), if (showAll) 1 else 0, background = inner,
            ) { showAll = it == 1; openGroup = null }
            Spacer(Modifier.width(10.dp))
            val sortNote = when {
                sorting != null -> "${if (kindsAi) "AI is sorting" else "Sorting"} ${sorting!!.done}/${sorting!!.total} files…"
                showAll && allTracks.size > songsOnly.size -> "${allTracks.size - songsOnly.size} aren't songs"
                !showAll && allTracks.size > songsOnly.size -> "${allTracks.size - songsOnly.size} recordings & sounds hidden"
                else -> null
            }
            if (sortNote != null) Text(sortNote, color = pal.textFaint, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

        Row(
            Modifier.padding(vertical = 12.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("Songs", view == LibView.SONGS, { view = LibView.SONGS; openGroup = null })
            Pill("Albums", view == LibView.ALBUMS, { view = LibView.ALBUMS; openGroup = null })
            Pill("Artists", view == LibView.ARTISTS, { view = LibView.ARTISTS; openGroup = null })
            Pill("Mixes", view == LibView.MIXES, { view = LibView.MIXES; openGroup = null })
            // The same recording saved more than once (remixes, live and sped-up versions don't count).
            Pill(
                if (duplicates.isEmpty()) "Duplicates" else "Duplicates · ${duplicates.sumOf { it.size - 1 }}",
                view == LibView.DUPLICATES, { view = LibView.DUPLICATES; openGroup = null },
            )
        }

        if (hasPerm && tracks.isNotEmpty() && openGroup == null && view == LibView.SONGS) {
            val readingColors = remember(tracks, colors, sortBy) { sortBy == SongSort.COLOR && tracks.any { it.id !in colors } }
            val filtering = settings.hiddenArtists.isNotEmpty() || settings.onlyHits || settings.onlyMostPlayed
            SortBar(sortBy, quickSort, readingColors, lookingUp && sortBy == SongSort.GENRE, filtering, hiddenCount = tracks.size - shown.size) { showFilter = true }
        }
    }

    // The list is the rest of the page: no box around it. It runs down behind the mini player
    // and the nav bar, and ends with enough room for the last song to scroll clear of them.
    val list: @Composable () -> Unit = {
        val bottom = bottomChrome()
        when {
            !hasPerm -> EmptyState(
                "LToTS needs access to your music",
                "Allow \"Music and audio\" so the app can find songs on your phone.",
                "Allow access",
            ) { launcher.launch(arrayOf(audioPerm)) }
            tracks.isEmpty() && allTracks.isNotEmpty() -> EmptyState("No songs yet", "Only recordings and sounds were found. Tap All to see them.", "Show all") { showAll = true }
            tracks.isEmpty() -> EmptyState("No songs found", "Put some music files on your phone (any folder) and they'll show up here.", null) {}
            openGroup != null -> com.ltots.app.ui.components.SwipeBackPage("Library group", onBack = { openGroup = null }) {
                GroupDetail(openGroup!!, player, bottom, onBack = { openGroup = null }, onPlayed = onOpenPlayer)
            }
            view == LibView.DUPLICATES -> DuplicatesView(duplicates, player, bottom, onOpenPlayer) {
                reload++
                com.ltots.app.download.DownloadManager.onLibraryFilesDeleted()
            }
            view == LibView.SONGS -> SongList(
                remember(filtered, sortBy, if (sortBy == SongSort.COLOR) colors else null) { SongSorter.sort(filtered, sortBy, colors) },
                player, bottom, onOpenPlayer,
                note = if (showAll) { t -> com.ltots.app.data.SongKinds.describe(t, kindVerdicts) } else null,
                // The slider's bubble follows the order: the letter, the genre, or the colour.
                label = when (sortBy) {
                    SongSort.TITLE -> { t -> ScrollLabel.Words(initialOf(t.title)) }
                    SongSort.GENRE -> { t -> ScrollLabel.Words(SongSorter.familyName(t.genre) ?: SongSorter.genreName(t.genre) ?: "No genre") }
                    SongSort.COLOR -> { t -> colors[t.id]?.let { ScrollLabel.Swatch(it) } }
                    SongSort.RANDOM -> null
                },
            )
            view == LibView.MIXES -> {
                // Built off the main thread (see Mixes.buildAsync); shown at once when it's known.
                val mixes by produceState(Mixes.peek(filtered, moods), filtered, moods) { value = Mixes.buildAsync(context, filtered, moods) }
                mixes?.let { MixGrid(it, bottom) { m -> openGroup = Group(m.title, "${m.kind.label} · ${m.subtitle}", m.tracks) } }
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

    // The scroll slider (Look > Library page): starts a few pixels below the top menu and ends
    // just above the mini player and nav bar, or runs down behind them.
    val scrollHost = remember { ScrollbarHost() }
    val sliderBottom = if (cfg.scrollbarFullHeight) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
        else bottomChrome() - 32.dp + 6.dp
    var headerHeight by remember { mutableStateOf(0.dp) }

    if (headerGlass) {
        // The top floats over the page like the bars at the bottom: the songs scroll on under it
        // (and under the status bar), and its glass blurs and bends them as they pass.
        val listLayer = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(listLayer).padding(horizontal = 16.dp)) {
                CompositionLocalProvider(LocalListTop provides headerHeight + 8.dp, LocalScrollbarHost provides scrollHost, content = list)
            }
            // The panel is fixed to the very top of the screen, status bar included, so nothing
            // scrolls into view above it; only its bottom corners are rounded.
            CompositionLocalProvider(LocalBackdrop provides listLayer, LocalGlassOverContent provides true) {
                GlassPanel(
                    Modifier.fillMaxWidth()
                        .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                    shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
                    tintBoost = 0.04f,
                ) {
                    Column(Modifier.padding(start = 30.dp, end = 30.dp, bottom = 4.dp), content = top)
                }
                // Over the list too, so its glass bends the songs passing under it.
                GlassScrollbar(
                    scrollHost, cfg.scrollbarShow,
                    Modifier.align(Alignment.TopEnd).padding(top = headerHeight + 6.dp, bottom = sliderBottom),
                )
            }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                Column(Modifier.onSizeChanged { headerHeight = with(density) { it.height.toDp() } }, content = top)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    CompositionLocalProvider(LocalScrollbarHost provides scrollHost) { list() }
                }
            }
            GlassScrollbar(
                scrollHost, cfg.scrollbarShow,
                Modifier.align(Alignment.TopEnd).padding(top = headerHeight + 6.dp, bottom = sliderBottom),
            )
        }
    }
    if (showFilter) LibraryFilterDialog(tracks, settings, onDismiss = { showFilter = false })
}

/** Room above a list for the floating top of the page (0 when the top isn't floating). */
private val LocalListTop = compositionLocalOf { 0.dp }

@Composable
private fun EmptyState(title: String, body: String, action: String?, onAction: () -> Unit) {
    val pal = LocalPalette.current
    Column(
        Modifier.fillMaxSize().padding(top = LocalListTop.current, bottom = bottomChrome()).padding(28.dp),
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
private fun SortBar(
    sortBy: SongSort,
    quick: SongSort?,
    readingColors: Boolean,
    readingGenres: Boolean,
    filtering: Boolean,
    hiddenCount: Int,
    onFilter: () -> Unit,
) {
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Sorted by ${sortBy.label}", color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    readingColors -> "Reading cover colours…"
                    readingGenres -> "Looking up each song's genre…"
                    filtering && hiddenCount > 0 -> "$hiddenCount songs filtered out"
                    quick != null -> "Just for now"
                    else -> "From settings"
                },
                color = pal.textFaint, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            )
        }
        for (s in listOf(SongSort.COLOR, SongSort.GENRE)) {
            Pill(s.label, sortBy == s, { SongSorter.setQuick(if (quick == s) null else s) })
        }
        Pill("Filter", filtering, onFilter)
    }
}

@Composable
private fun SongList(
    tracks: List<Track>, player: PlayerUi, bottom: Dp, onPlayed: () -> Unit,
    note: ((Track) -> String?)? = null, label: ((Track) -> ScrollLabel?)? = null,
) {
    val state = rememberLazyListState()
    RegisterScroll(state, label = label?.let { l -> { i: Int -> tracks.getOrNull(i)?.let(l) } })
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = LocalListTop.current + 2.dp, bottom = bottom)) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            TrackRow(t, playing = player.trackId == t.id, note = note?.invoke(t)) { player.play(tracks, i); onPlayed() }
        }
    }
}

@Composable
private fun TrackRow(t: Track, playing: Boolean, note: String? = null, onClick: () -> Unit) {
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
            val sub = listOfNotNull(note, t.artist.takeIf { it != Track.UNKNOWN }, t.qualityLabel).joinToString("  ·  ")
            Text(sub, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatTime(t.durationMs), color = pal.textFaint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Thumb(trackId: Long, sizeDp: Int) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val bmp by produceState(LocalLibrary.peekArtwork(trackId, 192), trackId) { value = LocalLibrary.peekArtwork(trackId, 192) ?: LocalLibrary.artwork(context, trackId, 192) }
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
    val state = rememberLazyGridState()
    RegisterScroll(state, label = { i -> groups.getOrNull(i)?.let { ScrollLabel.Words(initialOf(it.title)) } })
    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = LocalListTop.current + 4.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(groups, key = { it.tracks.first().albumId }) { g ->
            Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpen(g) }.padding(bottom = 10.dp)) {
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
    if (mixes.none { it.kind == Mix.Kind.AI_ALBUM || it.kind == Mix.Kind.THEME } && mixes.isNotEmpty()) {
        // No AI mixes yet: the card that sets them up sits above the other mixes.
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.padding(top = LocalListTop.current + 4.dp)) { MoodsCard() }
            Box(Modifier.weight(1f)) { CompositionLocalProvider(LocalListTop provides 8.dp) { MixGridBody(mixes, bottom, onOpen) } }
        }
        return
    }
    if (mixes.isEmpty()) {
        Column(Modifier.padding(top = LocalListTop.current + 4.dp)) { MoodsCard() }
        EmptyState(
            "No mixes yet",
            "Mixes appear by themselves once there are ${Mixes.MIN_SONGS} or more songs from the same game or soundtrack, the same decade or the same genre — and AI albums and moods once the mood listener has heard your songs.",
            null,
        ) {}
        return
    }
    MixGridBody(mixes, bottom, onOpen)
}

@Composable
private fun MixGridBody(mixes: List<Mix>, bottom: Dp, onOpen: (Mix) -> Unit) {
    val pal = LocalPalette.current
    val state = rememberLazyGridState()
    // The bubble names the section: each section's title, then its mixes.
    val sections = remember(mixes) { mixes.groupBy { it.kind }.flatMap { (k, g) -> List(g.size + 1) { k.label } } }
    RegisterScroll(state, label = { i -> sections.getOrNull(i)?.let { ScrollLabel.Words(it) } })
    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = LocalListTop.current + 4.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        for ((kind, group) in mixes.groupBy { it.kind }) {
            item(key = "head:${kind.name}", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(kind.label, color = pal.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                    // Under the AI albums: progress of the listening, and the button to update.
                    if (kind == Mix.Kind.AI_ALBUM) MoodsCard(compact = true)
                }
            }
            items(group, key = { it.id }) { m ->
                Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpen(m) }.padding(bottom = 10.dp)) {
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
    val bmp by produceState(LocalLibrary.peekArtwork(trackId, 384), trackId) { value = LocalLibrary.peekArtwork(trackId, 384) ?: LocalLibrary.artwork(context, trackId, 384) }
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(pal.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(remember(b) { b.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.Album, null, tint = pal.textFaint, modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun ArtistList(groups: List<Group>, bottom: Dp, onOpen: (Group) -> Unit) {
    val pal = LocalPalette.current
    val state = rememberLazyListState()
    RegisterScroll(state, label = { i -> groups.getOrNull(i)?.let { ScrollLabel.Words(initialOf(it.title)) } })
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = LocalListTop.current + 2.dp, bottom = bottom)) {
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
    Column(Modifier.fillMaxSize().padding(top = LocalListTop.current)) {
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
        // The slider starts below this title row, clear of the play button.
        val state = rememberLazyListState()
        RegisterScroll(state, extraTop = 56.dp)
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = bottom)) {
            itemsIndexed(g.tracks, key = { _, t -> t.id }) { i, t ->
                TrackRow(t, playing = player.trackId == t.id) { player.play(g.tracks, i); onPlayed() }
            }
        }
    }
}

/**
 * Library > Duplicates: every recording that's on the phone more than once. The best copy of each
 * (lossless before lossy, then the finer and bigger file) is kept; the others are ticked for
 * deleting. Tap a copy to tick or untick it, the play button to hear it first. Android asks once
 * before anything is deleted.
 */
@Composable
private fun DuplicatesView(groups: List<List<Track>>, player: PlayerUi, bottom: Dp, onPlayed: () -> Unit, onDeleted: () -> Unit) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    if (groups.isEmpty()) {
        EmptyState("No duplicates", "No recording is on your phone twice. Remixes, live, sped-up and other versions count as different songs.", null) {}
        return
    }
    val extras = remember(groups) { groups.flatMap { it.drop(1) }.mapTo(HashSet()) { it.id } }
    var remove by remember(groups) { mutableStateOf<Set<Long>>(extras) }
    val asked = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) onDeleted()
    }
    fun delete() {
        val uris = groups.flatten().filter { it.id in remove }.map { it.uri }
        if (uris.isEmpty()) return
        runCatching {
            val request = android.provider.MediaStore.createDeleteRequest(context.contentResolver, uris)
            asked.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build())
        }.onFailure { android.widget.Toast.makeText(context, "Couldn't ask Android to delete these files", android.widget.Toast.LENGTH_SHORT).show() }
    }
    val bytes = groups.flatten().filter { it.id in remove }.sumOf { it.sizeBytes }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = LocalListTop.current + 2.dp, bottom = bottom)) {
        item("head") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
                Text(
                    "${groups.size} recording${if (groups.size == 1) " is" else "s are"} on your phone more than once. " +
                        "The best copy of each is kept; remixes, live and sped-up versions aren't counted.",
                    color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(
                        if (remove.isEmpty()) "Nothing ticked" else "Delete ${remove.size} cop${if (remove.size == 1) "y" else "ies"} · ${"%.0f".format(Locale.ROOT, bytes / 1_000_000.0)} MB",
                        remove.isNotEmpty(), { delete() }, enabled = remove.isNotEmpty(),
                    )
                    Pill(if (remove == extras) "Untick all" else "Tick the extras", false, { remove = if (remove == extras) emptySet() else extras })
                }
            }
        }
        groups.forEach { g ->
            val first = g.first()
            item("g" + first.id) {
                Text(
                    listOfNotNull(first.title, first.artist.takeIf { it != Track.UNKNOWN }).joinToString(" — ") + "  ·  ${g.size} copies",
                    color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 2.dp),
                )
            }
            items(g, key = { "c" + it.id }) { t ->
                val going = t.id in remove
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .clickable {
                            // One copy of every recording always stays.
                            remove = if (going) remove - t.id else if (g.count { it.id !in remove } > 1) remove + t.id else remove
                        }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(if (going) Color(0xFFE5484D) else pal.text.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center,
                    ) { if (going) Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Thumb(t.id, 44)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            listOfNotNull(t.qualityLabel, "%.1f MB".format(Locale.ROOT, t.sizeBytes / 1_000_000.0), formatTime(t.durationMs)).joinToString("  ·  "),
                            color = if (going) pal.textDim else pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            (if (going) "Delete" else "Keep") + "  ·  " + listOfNotNull(t.folder, t.fileName).joinToString(""),
                            color = if (going) Color(0xFFFF8A80) else pal.accent, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { player.play(listOf(t), 0) }) { Icon(Icons.Rounded.PlayArrow, "Play this copy", tint = pal.textDim) }
                }
            }
        }
    }
}
