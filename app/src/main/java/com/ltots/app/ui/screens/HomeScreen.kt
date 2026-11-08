package com.ltots.app.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ltots.app.data.ListenHistory
import com.ltots.app.data.LocalLibrary
import com.ltots.app.data.Mix
import com.ltots.app.data.Mixes
import com.ltots.app.data.Track
import com.ltots.app.download.DownloadManager
import com.ltots.app.recs.RecSong
import com.ltots.app.recs.Recommender
import com.ltots.app.recs.Shelf
import com.ltots.app.recs.SongPreview
import com.ltots.app.ui.Inbox
import com.ltots.app.ui.PlayerUi
import com.ltots.app.ui.bottomChrome
import com.ltots.app.ui.components.NetImage
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SignInHost
import com.ltots.app.ui.components.SignInTarget
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ui.topInset
import com.ltots.app.ytm.YtAlbum
import com.ltots.app.ytm.YtItem
import com.ltots.app.ytm.YtPlaylist
import com.ltots.app.ytm.YtSong
import com.ltots.app.ytm.YtmAccount
import kotlinx.coroutines.launch
import java.util.Calendar

/** Mood chips at the top, as on YouTube Music's home. */
private val MOODS = listOf("Energize", "Relax", "Feel good", "Workout", "Focus", "Party", "Romance", "Sad", "Sleep")

/**
 * Home: recommendations, a mix of YouTube Music's and Spotify's home screens.
 *
 * Spotify's greeting and grid of things to jump back into (from the phone, they play at once),
 * YouTube Music's mood chips and "Quick picks" (pages of four songs side by side), then shelves:
 * "Because you listened to …", the AI's picks for the vibe of what you've been playing, your
 * YouTube Music shelves when signed in, your mixes and songs to listen to again.
 *
 * Every new song can be previewed — 30 seconds of the hook — and downloaded from where it is,
 * so you hear whether you want it before getting it.
 */
@Composable
fun HomeScreen(player: PlayerUi, onOpenPlayer: () -> Unit, onOpenGet: () -> Unit) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    val feed by Recommender.feed.collectAsState()
    val loading by Recommender.loading.collectAsState()
    val error by Recommender.error.collectAsState()
    val account by YtmAccount.account.collectAsState()
    val preview by SongPreview.state.collectAsState()
    val history by ListenHistory.items.collectAsState()
    LaunchedEffect(Unit) {
        YtmAccount.load(); ListenHistory.ensureLoaded()
        // Download buttons are on this page: get the download engine ready ahead of the first tap,
        // but only once the page has settled (unpacking it right at start-up held up the first
        // screens; a tap before then readies it by itself).
        kotlinx.coroutines.delay(4_000)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.ltots.app.download.Engine.ensureReady() }
    }
    LaunchedEffect(account) { Recommender.load(context) }
    // Popular music (charts, new albums, radios) for the chosen country.
    val settings by com.ltots.app.audio.AudioEngine.settings.collectAsState()
    val discover by com.ltots.app.recs.Discover.feed.collectAsState()
    LaunchedEffect(settings.chartsRegion, account) { com.ltots.app.recs.Discover.load(context) }
    // The all-time numbers start with what's already in the history (once).
    LaunchedEffect(history.isNotEmpty()) {
        if (history.isNotEmpty()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.ltots.app.data.ListenStats.backfill(history) }
    }
    val statsVersion by com.ltots.app.data.ListenStats.version.collectAsState()
    var showStats by rememberSaveable { mutableStateOf(false) }
    var wrappedYear by rememberSaveable { mutableStateOf<Int?>(null) }
    var pickRegion by remember { mutableStateOf(false) }
    // A radio opened from the Radios shelf: its songs replace the page, like a mood.
    var radio by remember { mutableStateOf<com.ltots.app.recs.Radio?>(null) }
    var radioSongs by remember { mutableStateOf<List<RecSong>?>(null) }
    LaunchedEffect(radio) {
        radioSongs = null
        radio?.let { r -> radioSongs = runCatching { com.ltots.app.recs.Discover.radioSongs(r) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(feed) { feed?.shelves?.firstOrNull()?.songs?.let { SongPreview.warmUp(it.map { r -> r.song }) } }
    LaunchedEffect(preview.error) { preview.error?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); SongPreview.clearError() } }

    // The phone's songs, for the parts that play straight away.
    val library by produceState(emptyList<Track>()) {
        // The phone's songs as last seen at once (read off the main thread), then fresh.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { LocalLibrary.cachedSongs() }?.let { value = it }
        if (com.ltots.app.prep.LibraryPrep.hasAudioPermission(context)) value = LocalLibrary.loadSongs(context)
    }
    val moods by com.ltots.app.moods.MoodIndex.embeddings.collectAsState()
    val mixes by produceState(Mixes.peek(library, moods)?.take(10) ?: emptyList(), library, moods) {
        value = Mixes.buildAsync(context, library, moods).take(10)
    }
    val local = remember(history, library) { localPicks(history, library) }

    var mood by rememberSaveable { mutableStateOf<String?>(null) }
    var moodSongs by remember { mutableStateOf<List<RecSong>?>(null) }
    androidx.activity.compose.BackHandler(enabled = mood != null) { mood = null }
    LaunchedEffect(mood) {
        moodSongs = null
        mood?.let { m -> moodSongs = runCatching { Recommender.mood(context, m) }.getOrDefault(emptyList()) }
    }

    fun playLocal(tracks: List<Track>, index: Int = 0) {
        if (tracks.isEmpty()) return
        SongPreview.stop()
        player.play(tracks, index)
        onOpenPlayer()
    }
    // A song started from Home carries on with the songs on the phone most like it.
    val playable = remember(library, settings.hiddenArtists) {
        val hidden = settings.hiddenArtists.map { com.ltots.app.lyrics.SongMatch.norm(it) }.toSet()
        com.ltots.app.data.SongKinds.songs(library).filter { com.ltots.app.lyrics.SongMatch.norm(it.artist) !in hidden }
    }
    fun playWithSimilar(t: Track) = playLocal(listOf(t) + com.ltots.app.recs.LibraryRadio.similarTo(t, playable, moods))
    fun playMix(m: Mix) {
        val ids = m.tracks.mapTo(HashSet()) { it.id }
        playLocal(m.tracks + com.ltots.app.recs.LibraryRadio.similarTo(m.tracks.first(), playable, moods).filter { it.id !in ids })
    }
    val actions = SongActions(
        preview = { s -> SongPreview.toggle(s) { player.controller?.pause() } },
        download = { s ->
            val n = DownloadManager.enqueueSong(s)
            Toast.makeText(context, if (n > 0) "Downloading “${s.title}”" else "Already downloaded or in the queue", Toast.LENGTH_SHORT).show()
        },
        notInterested = { s -> Recommender.notInterested(s); if (preview.videoId == s.videoId) SongPreview.stop() },
        lessOf = { a -> Recommender.lessOfArtist(a) },
    )

    // Songs you've played most of all (all time, imports included), from the phone.
    val most = remember(statsVersion, library) {
        val byKey = library.associateBy { com.ltots.app.data.ListenStats.key(it.title, it.artist) }
        com.ltots.app.data.ListenStats.summary(null).songs.mapNotNull { s -> byKey[s.key]?.let { it to s } }.take(15)
    }

    // Loved once, not played for two months.
    val forgotten = remember(statsVersion, playable, history) {
        val since = System.currentTimeMillis() - 60L * 24 * 3600_000L
        val recent = history.filter { it.at >= since }.mapTo(HashSet()) { it.key }
        val byKey = playable.associateBy { com.ltots.app.data.ListenStats.key(it.title, it.artist) }
        com.ltots.app.data.ListenStats.summary(null).songs.take(200).mapNotNull { byKey[it.key] }.filter { it.profileKey !in recent }.take(15)
    }
    val fromLibrary = remember(playable) {
        playable.shuffled(kotlin.random.Random(Calendar.getInstance().get(Calendar.DAY_OF_YEAR))).take(14)
    }
    val recentlyAdded = remember(playable) { playable.filter { it.addedAt != null }.sortedByDescending { it.addedAt }.take(14) }

    com.ltots.app.ui.components.SwipeBackPage("Radio", enabled = radio != null, onBack = { radio = null }) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = topInset() + 10.dp, bottom = bottomChrome() + if (preview.active) 76.dp else 0.dp)) {
            // Greeting, refresh, account
            item("head") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting(), style = MaterialTheme.typography.headlineMedium, color = pal.text, fontWeight = FontWeight.Bold)
                        val f = feed
                        Text(
                            when {
                                loading && f == null -> "Finding songs for you…"
                                f != null && f.historySize > 0 -> "Picked from ${f.historySize} plays" + if (f.signedIn) " and your YouTube Music" else ""
                                f != null -> "Picked from the music on your phone" + if (f.signedIn) " and your YouTube Music" else ""
                                else -> "Recommendations from what you listen to"
                            },
                            color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (loading) CircularProgressIndicator(color = pal.accent, strokeWidth = 2.5.dp, modifier = Modifier.padding(10.dp).size(22.dp))
                    else IconButton(onClick = { scope.launch { Recommender.load(context, force = true); com.ltots.app.recs.Discover.load(context, force = true) } }) {
                        Icon(Icons.Rounded.Refresh, "New picks", tint = pal.text)
                    }
                    AccountButton(account) {
                        if (account == null) SignInHost.open(SignInTarget.YouTubeMusic) { Recommender.invalidate(); scope.launch { Recommender.load(context, force = true) } }
                    }
                }
            }

            // Mood chips (YouTube Music)
            item("chips") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    item { Pill("For you", mood == null && radio == null, { mood = null; radio = null }) }
                    items(MOODS) { m -> Pill(m, mood == m, { radio = null; mood = if (mood == m) null else m }) }
                }
            }

            if (mood != null) {
                item("moodh") { ShelfHeader(small = "Mood", title = mood!!, sub = "Leaning towards the artists you play") }
                val ms = moodSongs
                if (ms == null) item("moodl") { LoadingRow() }
                else if (ms.isEmpty()) item("mood0") { Hint("Nothing found right now — check your connection.") }
                else item("moodq") { QuickPicks(ms, preview, actions) }
            }

            radio?.let { r ->
                item("radioh") { ShelfHeader(small = "Radio", title = r.title, sub = "Songs like ${r.seed.title}" + (if (r.seed.artistText.isNotBlank()) " by ${r.seed.artistText}" else "") + " · tap one to preview") }
                val rs = radioSongs
                if (rs == null) item("radiol") { LoadingRow() }
                else if (rs.isEmpty()) item("radio0") { Hint("Couldn't load this radio — check your connection.") }
                else item("radioq") { QuickPicks(rs, preview, actions) }
                item("radiob") { Pill("← Back to Home", false, { radio = null }, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            }

            // Jump back in (Spotify's grid), from the phone
            if (mood == null && radio == null && local.jumpBackIn.isNotEmpty()) item("jump") {
                JumpBackIn(local.jumpBackIn, onPlay = { t -> playWithSimilar(t) })
            }

            // The year's listening, and the way into Wrapped and the stats.
            if (mood == null && radio == null) item("listening") {
                @Suppress("UNUSED_EXPRESSION") statsVersion
                com.ltots.app.ui.screens.ListeningCard(onOpenStats = { showStats = true }, onOpenWrapped = { wrappedYear = it })
            }

            if (account == null && mood == null && radio == null) item("signin") {
                GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), tintBoost = 0.05f) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Better picks with YouTube Music", color = pal.text, fontWeight = FontWeight.SemiBold)
                            Text("Sign in and Home learns from your YouTube Music history and shows your own shelves.",
                                color = pal.textDim, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(10.dp))
                        Pill("Sign in", true, { SignInHost.open(SignInTarget.YouTubeMusic) { Recommender.invalidate(); scope.launch { Recommender.load(context, force = true) } } })
                    }
                }
            }

            val f = feed
            if (mood == null && radio == null) {
                if (f == null && loading) item("load") { LoadingRow() }
                if (f == null && !loading) item("err") {
                    Hint(error ?: "Play some songs and recommendations show up here. New picks: ↻ at the top.")
                }
                f?.shelves?.forEach { shelf ->
                    when (shelf.kind) {
                        Shelf.Kind.QUICK_PICKS -> {
                            item("h:${shelf.id}") { ShelfHeader(small = null, title = shelf.title, sub = shelf.subtitle) }
                            item("q:${shelf.id}") { QuickPicks(shelf.songs, preview, actions) }
                        }
                        Shelf.Kind.BECAUSE -> {
                            item("h:${shelf.id}") { ShelfHeader(small = shelf.title, title = shelf.subtitle ?: "", sub = null) }
                            item("c:${shelf.id}") { SongCarousel(shelf.songs, preview, actions) }
                        }
                        Shelf.Kind.AI_VIBE -> {
                            item("h:${shelf.id}") { ShelfHeader(small = "AI picks", title = shelf.title.removePrefix("AI picks · "), sub = shelf.subtitle, ai = true) }
                            item("c:${shelf.id}") { SongCarousel(shelf.songs, preview, actions) }
                        }
                        Shelf.Kind.YOUTUBE -> {
                            item("h:${shelf.id}") { ShelfHeader(small = "YouTube Music", title = shelf.title, sub = null) }
                            if (shelf.songs.isNotEmpty()) item("c:${shelf.id}") { SongCarousel(shelf.songs, preview, actions) }
                            if (shelf.items.isNotEmpty()) item("i:${shelf.id}") {
                                CollectionCarousel(shelf.items) { link -> Inbox.sharedText.value = link; onOpenGet() }
                            }
                        }
                    }
                }
            }

            if (mood == null && radio == null && most.size >= 3) {
                item("mosth") { ShelfHeader(small = "Mixed for you", title = "Replay mix", sub = "The songs you keep coming back to") }
                item("most") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(most, key = { it.first.id }) { (t, st) ->
                            LocalCard(t, note = "${st.plays} plays · ${st.ms / 60_000} min") { playWithSimilar(t) }
                        }
                    }
                }
            }

            // What's popular: trending, top songs and artists in your region, new albums, radios.
            val d = discover
            if (mood == null && radio == null && d != null) {
                val region = com.ltots.app.recs.Discover.regionName(d.region)
                if (d.trending.isNotEmpty()) {
                    item("trendh") { RegionHeader("Trending", if (d.region == "ZZ") "Trending worldwide" else "Trending in $region") { pickRegion = true } }
                    item("trend") { SongCarousel(d.trending.map { RecSong(it, 0.0, "Trending") }, preview, actions) }
                }
                if (d.topSongs.isNotEmpty()) {
                    item("toph") { RegionHeader("Charts", if (d.region == "ZZ") "Top songs worldwide" else "Top songs in $region") { pickRegion = true } }
                    item("top") { QuickPicks(d.topSongs.map { RecSong(it, 0.0, "Top song") }, preview, actions) }
                }
                if (d.artists.isNotEmpty()) {
                    item("arth") { ShelfHeader(small = "Charts", title = "Popular artists", sub = if (d.region == "ZZ") "Most played worldwide" else "Most played in $region") }
                    item("art") { ArtistCarousel(d.artists) { link -> Inbox.sharedText.value = link; onOpenGet() } }
                }
                if (d.albums.isNotEmpty()) {
                    item("albh") { ShelfHeader(small = "New & popular", title = "Albums & singles", sub = "Just out on YouTube Music") }
                    item("alb") { CollectionCarousel(d.albums) { link -> Inbox.sharedText.value = link; onOpenGet() } }
                }
                if (d.radios.isNotEmpty()) {
                    item("radh") { ShelfHeader(small = "Radios", title = "Popular radios", sub = "Endless songs like the ones you love and what's hot") }
                    item("rad") { RadioCarousel(d.radios) { radio = it } }
                }
            }

            // From the phone: your mixes (Spotify's "Made for you") and songs to play again.
            if (mood == null && radio == null && mixes.isNotEmpty()) {
                item("mixh") { ShelfHeader(small = "Made for you", title = "Your mixes", sub = "From the songs on your phone") }
                item("mix") { MixCarousel(mixes) { m -> playMix(m) } }
            }
            if (mood == null && radio == null && local.listenAgain.isNotEmpty()) {
                item("againh") { ShelfHeader(small = null, title = "Listen again", sub = null) }
                item("again") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(local.listenAgain, key = { it.id }) { t -> LocalCard(t) { playWithSimilar(t) } }
                    }
                }
            }
            if (mood == null && radio == null) {
                fun localShelf(id: String, small: String, title: String, sub: String?, songs: List<Track>) {
                    if (songs.size < 3) return
                    item("$id-h") { ShelfHeader(small = small, title = title, sub = sub) }
                    item(id) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(songs, key = { it.id }) { t -> LocalCard(t) { playWithSimilar(t) } }
                        }
                    }
                }
                localShelf("forgotten", "Your favourites", "Forgotten favourites", "You loved these, and haven't played them in a while", forgotten)
                localShelf("fromlib", "On your phone", "From your library", null, fromLibrary)
                localShelf("added", "On your phone", "Recently added", null, recentlyAdded)
            }
            if (mood == null && f != null && f.basedOn.isNotEmpty()) item("based") {
                Hint("Based on " + f.basedOn.take(4).joinToString(", ") { "“$it”" } + ". Long-press a song for Not interested.")
            }
        }

        if (showStats) com.ltots.app.ui.screens.StatsDialog(onDismiss = { showStats = false }, onOpenWrapped = { showStats = false; wrappedYear = it })
        wrappedYear?.let { y -> com.ltots.app.ui.screens.WrappedDialog(y) { wrappedYear = null } }
        if (pickRegion) RegionPicker(settings.chartsRegion, onDismiss = { pickRegion = false }) { code ->
            pickRegion = false
            com.ltots.app.audio.AudioEngine.updateSettings { it.copy(chartsRegion = code) }
        }

        // The preview playing, with its download button.
        if (preview.active) PreviewBar(preview, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomChrome() - 16.dp, start = 14.dp, end = 14.dp)) {
            preview.song?.let(actions.download)
        }
    }
    }
}

private class SongActions(
    val preview: (YtSong) -> Unit,
    val download: (YtSong) -> Unit,
    val notInterested: (YtSong) -> Unit,
    val lessOf: (String) -> Unit,
)

private data class LocalPicks(val jumpBackIn: List<Track>, val listenAgain: List<Track>)

/** From the history: the last few things played (one per album) and the most played lately. */
private fun localPicks(history: List<com.ltots.app.data.Listen>, library: List<Track>): LocalPicks {
    val byKey = library.associateBy { it.profileKey }
    val recent = history.mapNotNull { byKey[it.key] }
    val jump = recent.distinctBy { if (it.album.isBlank()) "t${it.id}" else "a${it.albumId}" }.take(6)
    val counts = history.take(400).filter { !it.skipped }.groupingBy { it.key }.eachCount()
    val again = counts.entries.sortedByDescending { it.value }.mapNotNull { byKey[it.key] }.filter { t -> jump.none { it.id == t.id } }.take(14)
    return LocalPicks(jump, again)
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun AccountButton(account: YtmAccount.Account?, onClick: () -> Unit) {
    val pal = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.padding(start = 4.dp).size(36.dp).clip(CircleShape).background(pal.text.copy(alpha = 0.1f))
                .combinedClickable(onClick = { if (account != null) menu = true else onClick() }),
            contentAlignment = Alignment.Center,
        ) {
            if (account?.photoUrl != null) NetImage(account.photoUrl, 36.dp, CircleShape, placeholder = Icons.Rounded.Person)
            else Icon(Icons.Rounded.Person, if (account == null) "Sign in to YouTube Music" else account.name, tint = pal.textDim)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            account?.let { DropdownMenuItem(text = { Text("Signed in as ${it.name}") }, onClick = { menu = false }, enabled = false) }
            DropdownMenuItem(text = { Text("Sign out of YouTube Music") }, onClick = { menu = false; YtmAccount.signOut(); Recommender.invalidate() })
        }
    }
}

@Composable
private fun ShelfHeader(small: String?, title: String, sub: String?, ai: Boolean = false) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)) {
        if (small != null) Row(verticalAlignment = Alignment.CenterVertically) {
            if (ai) { Icon(Icons.Rounded.AutoAwesome, null, tint = pal.accent, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
            Text(small.uppercase(), color = if (ai) pal.accent else pal.textFaint, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
        Text(title, color = pal.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = LocalPalette.current.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
}

@Composable
private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = LocalPalette.current.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
    }
}

/** Spotify's grid of six: two columns of wide tiles with the cover on the left. */
@Composable
private fun JumpBackIn(tracks: List<Track>, onPlay: (Track) -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tracks.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { t ->
                    Row(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(8.dp)).background(pal.text.copy(alpha = 0.08f))
                            .combinedClickable(onClick = { onPlay(t) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LocalArt(t.id, 56.dp, RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                        Text(
                            t.album.takeIf { it.isNotBlank() && !it.equals("Music", true) } ?: t.title,
                            color = pal.text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp),
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** YouTube Music's Quick picks: pages of four songs side by side, swiped sideways. */
@Composable
private fun QuickPicks(songs: List<RecSong>, preview: SongPreview.State, actions: SongActions) {
    val width = LocalConfiguration.current.screenWidthDp.dp
    LazyHorizontalGrid(
        GridCells.Fixed(4),
        Modifier.fillMaxWidth().height(256.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        items(songs, key = { it.song.videoId }) { r ->
            QuickPickRow(r, preview, actions, Modifier.width(width * 0.86f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickPickRow(r: RecSong, preview: SongPreview.State, actions: SongActions, modifier: Modifier) {
    val pal = LocalPalette.current
    val downloaded by DownloadManager.downloaded.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val s = r.song
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = { actions.preview(s) }, onLongClick = { menu = true })
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(contentAlignment = Alignment.Center) {
                NetImage(s.thumbUrl, 50.dp, RoundedCornerShape(8.dp))
                PreviewBadge(s, preview, 50.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, color = if (preview.videoId == s.videoId) pal.accent else pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(s.artistText, r.reason).filter { it.isNotBlank() }.joinToString(" · "), color = pal.textDim,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DownloadButton(s, s.videoId in downloaded || DownloadManager.isQueued(s.videoId), actions)
        }
        SongMenu(menu, s, actions) { menu = false }
    }
}

/** Over a cover: the preview's progress ring while it plays, a spinner while it loads. */
@Composable
private fun PreviewBadge(s: YtSong, preview: SongPreview.State, size: Dp) {
    val pal = LocalPalette.current
    if (preview.videoId != s.videoId) return
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
        if (preview.loading) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(size * 0.45f))
        else {
            CircularProgressIndicator(progress = { preview.progress }, color = pal.accent, trackColor = Color.White.copy(alpha = 0.25f), strokeWidth = 2.5.dp, modifier = Modifier.size(size * 0.6f))
            Icon(Icons.Rounded.Stop, "Stop preview", tint = Color.White, modifier = Modifier.size(size * 0.32f))
        }
    }
}

@Composable
private fun DownloadButton(s: YtSong, have: Boolean, actions: SongActions) {
    val pal = LocalPalette.current
    IconButton(onClick = { if (!have) actions.download(s) }) {
        Icon(if (have) Icons.Rounded.Check else Icons.Rounded.Download, if (have) "Downloaded" else "Download", tint = if (have) pal.accent else pal.textDim)
    }
}

@Composable
private fun SongMenu(open: Boolean, s: YtSong, actions: SongActions, onDismiss: () -> Unit) {
    DropdownMenu(open, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Preview 30 seconds") }, onClick = { onDismiss(); actions.preview(s) })
        DropdownMenuItem(text = { Text("Download") }, onClick = { onDismiss(); actions.download(s) })
        DropdownMenuItem(text = { Text("Not interested") }, onClick = { onDismiss(); actions.notInterested(s) })
        s.artists.firstOrNull()?.name?.let { a -> DropdownMenuItem(text = { Text("Fewer songs by $a") }, onClick = { onDismiss(); actions.lessOf(a) }) }
    }
}

/** Spotify-style shelf of big square covers; tapping one previews it. */
@Composable
private fun SongCarousel(songs: List<RecSong>, preview: SongPreview.State, actions: SongActions) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(songs, key = { it.song.videoId }) { r -> SongCard(r, preview, actions) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongCard(r: RecSong, preview: SongPreview.State, actions: SongActions) {
    val pal = LocalPalette.current
    val downloaded by DownloadManager.downloaded.collectAsState()
    var menu by remember { mutableStateOf(false) }
    val s = r.song
    val have = s.videoId in downloaded || DownloadManager.isQueued(s.videoId)
    Box {
        Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { actions.preview(s) }, onLongClick = { menu = true }).padding(bottom = 8.dp)) {
            Box {
                NetImage(s.thumbUrl, 150.dp, RoundedCornerShape(12.dp), px = 400)
                PreviewBadge(s, preview, 150.dp)
                // Play-the-preview and download, over the cover's bottom corners.
                Row(Modifier.align(Alignment.BottomEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CoverButton(if (have) Icons.Rounded.Check else Icons.Rounded.Download, if (have) "Downloaded" else "Download") { if (!have) actions.download(s) }
                    if (preview.videoId != s.videoId) CoverButton(Icons.Rounded.PlayArrow, "Preview", accent = true) { actions.preview(s) }
                }
            }
            Text(s.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Text(s.artistText, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SongMenu(menu, s, actions) { menu = false }
    }
}

@Composable
private fun CoverButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(if (accent) pal.accent else Color.Black.copy(alpha = 0.55f))
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (accent) pal.onAccent else Color.White, modifier = Modifier.size(20.dp)) }
}

/** Albums and playlists from YouTube Music's own shelves: they open on the Get tab. */
@Composable
private fun CollectionCarousel(items: List<YtItem>, onOpen: (String) -> Unit) {
    val pal = LocalPalette.current
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        items(items) { it ->
            val link = when (it) {
                is YtAlbum -> "https://music.youtube.com/browse/${it.browseId}"
                is YtPlaylist -> "https://music.youtube.com/playlist?list=${it.playlistId}"
                else -> null
            } ?: return@items
            Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { onOpen(link) }).padding(bottom = 8.dp)) {
                NetImage(it.thumbUrl, 150.dp, RoundedCornerShape(12.dp), px = 400)
                Text(it.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(if (it is YtAlbum) "Album · ${it.artist}" else "Playlist", color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Your mixes: big cards with the mix's name over its first song's cover. */
@Composable
private fun MixCarousel(mixes: List<Mix>, onPlay: (Mix) -> Unit) {
    val pal = LocalPalette.current
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(mixes, key = { it.id }) { m ->
            Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { onPlay(m) }).padding(bottom = 8.dp)) {
                Box(Modifier.size(150.dp)) {
                    LocalArt(m.tracks.first().id, 150.dp, RoundedCornerShape(12.dp))
                    Box(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))))
                    Text(m.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                }
                Text("${m.kind.label} · ${m.subtitle}", color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun LocalCard(t: Track, note: String? = null, onPlay: () -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.width(130.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onPlay).padding(bottom = 8.dp)) {
        LocalArt(t.id, 130.dp, RoundedCornerShape(12.dp))
        Text(t.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(t.artist.takeIf { it != Track.UNKNOWN } ?: "", color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (note != null) Text(note, color = pal.accent, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

/** A shelf header with the region to change, on the right. */
@Composable
private fun RegionHeader(small: String, title: String, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) { ShelfHeader(small = small, title = title, sub = null) }
        Pill("Region ▾", false, onPick, Modifier.padding(end = 16.dp, bottom = 10.dp))
    }
}

/** Every country (and worldwide) to pick the charts from. */
@Composable
private fun RegionPicker(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val pal = LocalPalette.current
    val all = remember { com.ltots.app.recs.Discover.regions() }
    var q by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Charts from") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Search countries") }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().height(360.dp).padding(top = 8.dp)) {
                    items(all.filter { it.second.contains(q, true) || it.first.equals(q, true) }, key = { it.first }) { (code, name) ->
                        Text(
                            name, color = if (code == current) pal.accent else pal.text, fontWeight = if (code == current) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = { onPick(code) }).padding(vertical = 10.dp, horizontal = 6.dp),
                        )
                    }
                }
            }
        },
    )
}

/** Popular artists: round photos; they open on the Get tab. */
@Composable
private fun ArtistCarousel(artists: List<com.ltots.app.ytm.YtArtist>, onOpen: (String) -> Unit) {
    val pal = LocalPalette.current
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(artists, key = { it.browseId }) { a ->
            Column(
                Modifier.width(110.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { onOpen("https://music.youtube.com/channel/${a.browseId}") }).padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NetImage(a.thumbUrl, 110.dp, CircleShape, placeholder = Icons.Rounded.Person, px = 300)
                Text(a.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Radios: the song's cover with "Radio" over it; tapping opens its songs to preview. */
@Composable
private fun RadioCarousel(radios: List<com.ltots.app.recs.Radio>, onOpen: (com.ltots.app.recs.Radio) -> Unit) {
    val pal = LocalPalette.current
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(radios, key = { it.seed.videoId }) { r ->
            Column(Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = { onOpen(r) }).padding(bottom = 8.dp)) {
                Box(Modifier.size(150.dp)) {
                    NetImage(r.thumbUrl, 150.dp, RoundedCornerShape(12.dp), px = 400)
                    Box(Modifier.matchParentSize().clip(RoundedCornerShape(12.dp))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, pal.accent.copy(alpha = 0.75f)))))
                    Text("RADIO", color = Color.White, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                }
                Text(r.title, color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(r.subtitle, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A song's cover from the phone. */
@Composable
private fun LocalArt(trackId: Long, size: Dp, shape: androidx.compose.ui.graphics.Shape) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val bmp by produceState(LocalLibrary.peekArtwork(trackId, 256), trackId) { value = LocalLibrary.peekArtwork(trackId, 256) ?: LocalLibrary.artwork(context, trackId, 256) }
    Box(Modifier.size(size).clip(shape).background(pal.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(remember(b) { b.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, tint = pal.textFaint, modifier = Modifier.size(size / 2.5f))
    }
}

/** The preview playing: its song, 30 seconds of progress, download and stop. */
@Composable
private fun PreviewBar(p: SongPreview.State, modifier: Modifier, onDownload: () -> Unit) {
    val pal = LocalPalette.current
    GlassPanel(modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(20.dp), tintBoost = 0.1f) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            NetImage(p.thumbUrl, 44.dp, RoundedCornerShape(10.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (p.loading) "Loading preview…" else "Preview · ${p.title}", color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p.artist, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { p.progress }, Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)), color = pal.accent, trackColor = pal.text.copy(alpha = 0.12f))
            }
            IconButton(onClick = onDownload) { Icon(Icons.Rounded.Download, "Download", tint = pal.accent) }
            IconButton(onClick = { SongPreview.stop() }) { Icon(Icons.Rounded.Close, "Stop preview", tint = pal.text) }
        }
    }
}
