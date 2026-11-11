package com.ltots.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ltots.app.data.HistoryImport
import com.ltots.app.data.ListenStats
import com.ltots.app.data.LocalLibrary
import com.ltots.app.data.StatsSummary
import com.ltots.app.data.Track
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ytm.YtmAccount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

private fun thisYear() = Calendar.getInstance().get(Calendar.YEAR)

/** "1,234" */
private fun num(n: Long) = String.format(Locale.ROOT, "%,d", n)

private fun minutes(ms: Long) = num(ms / 60_000)

private fun monthName(m: Int) = java.text.DateFormatSymbols(Locale.ENGLISH).months[m]

private fun hourName(h: Int) = when (h) { 0 -> "midnight"; 12 -> "noon"; in 1..11 -> "$h am"; else -> "${h - 12} pm" }

/**
 * Home: the year's listening at a glance (minutes, top song, top artist), with the way into the
 * stats and into Wrapped. In December and January it says this year's Wrapped is ready.
 */
@Composable
fun ListeningCard(onOpenStats: () -> Unit, onOpenWrapped: (Int) -> Unit) {
    val pal = LocalPalette.current
    val version by ListenStats.version.collectAsState()
    val cal = Calendar.getInstance()
    // In January, last year's Wrapped is the one to show.
    val year = if (cal.get(Calendar.MONTH) == Calendar.JANUARY && ListenStats.years().contains(thisYear() - 1)) thisYear() - 1 else thisYear()
    val s = remember(version, year) { ListenStats.summary(year) }
    if (s.plays == 0) return
    val ready = cal.get(Calendar.MONTH) == Calendar.DECEMBER || year < thisYear()
    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), tintBoost = 0.06f) {
        Column(Modifier.clickable(onClick = onOpenStats).padding(16.dp)) {
            Text(if (ready) "YOUR $year WRAPPED IS READY" else "YOUR $year SO FAR", color = pal.accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                Text(minutes(s.totalMs), color = pal.text, fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text("  minutes · ${num(s.plays.toLong())} plays", color = pal.textDim, modifier = Modifier.padding(bottom = 6.dp))
            }
            s.songs.firstOrNull()?.let { t ->
                Text("Top song: ${t.title}" + (if (t.artist.isNotBlank()) " — ${t.artist}" else ""), color = pal.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            s.artists.firstOrNull()?.let { a -> Text("Top artist: ${a.name}", color = pal.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("▶ Play Wrapped", true, { onOpenWrapped(year) })
                Pill("All stats & import", false, onOpenStats)
            }
        }
    }
}

// ---- stats page ------------------------------------------------------------------------------

/** Every number: the year (or all time), top songs and artists, when you listen, and imports. */
@Composable
fun StatsDialog(onDismiss: () -> Unit, onOpenWrapped: (Int) -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val pal = LocalPalette.current
        val version by ListenStats.version.collectAsState()
        val years = remember(version) { ListenStats.years() }
        var year by remember { mutableStateOf<Int?>(thisYear()) }
        val s = remember(version, year) { ListenStats.summary(year) }
        Box(Modifier.fillMaxSize().background(Color(0xFF07060C))) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your listening", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(thisYear()) + years).distinct().forEach { y -> Pill("$y", year == y, { year = y }) }
                    Pill("All time", year == null, { year = null })
                }
                if (s.plays == 0) {
                    Text("Nothing played yet in ${year ?: "LToTS"}. Play some songs, or import your history from another app below.",
                        color = pal.textDim, modifier = Modifier.padding(vertical = 16.dp))
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Stat("Minutes", minutes(s.totalMs), Modifier.weight(1f))
                        Stat("Plays", num(s.plays.toLong()), Modifier.weight(1f))
                        Stat("Songs", num(s.songs.size.toLong()), Modifier.weight(1f))
                    }
                    if (year != null) Pill("▶ Play your $year Wrapped", true, { onOpenWrapped(year!!) }, Modifier.padding(top = 12.dp))
                    if (s.importedShare > 0.005) Text("${(s.importedShare * 100).toInt()}% of these plays were imported from other apps.",
                        color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))

                    SectionTitle("Top songs", Modifier.padding(top = 18.dp))
                    s.songs.take(25).forEachIndexed { i, t ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", color = pal.textFaint, modifier = Modifier.width(28.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(t.artist, color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${t.plays} plays · ${minutes(t.ms)} min", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    SectionTitle("Top artists", Modifier.padding(top = 18.dp))
                    s.artists.take(15).forEachIndexed { i, a ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", color = pal.textFaint, modifier = Modifier.width(28.dp))
                            Text(a.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${a.songs} song${if (a.songs == 1) "" else "s"} · ${minutes(a.ms)} min", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    SectionTitle("When you listen", Modifier.padding(top = 18.dp))
                    Bars(s.hours.toList(), labels = listOf("12a", "6a", "12p", "6p"), height = 90.dp)
                    if (year != null) {
                        Spacer(Modifier.height(12.dp))
                        Bars(s.months.toList(), labels = listOf("Jan", "Apr", "Jul", "Oct"), height = 90.dp)
                    }
                }
                WrappedStyle()
                SectionTitle("Import your history", Modifier.padding(top = 22.dp))
                ImportSection()
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    val pal = LocalPalette.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f)).padding(12.dp)) {
        Text(value, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(label, color = pal.textDim, style = MaterialTheme.typography.bodySmall)
    }
}

/** A small bar chart (24 hours or 12 months), labels spread under it. */
@Composable
private fun Bars(values: List<Long>, labels: List<String>, height: Dp) {
    val pal = LocalPalette.current
    val top = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
    Column {
        Row(Modifier.fillMaxWidth().height(height), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            values.forEach { v ->
                Box(Modifier.weight(1f).fillMaxHeight((v.toFloat() / top).coerceAtLeast(0.02f)).clip(RoundedCornerShape(3.dp))
                    .background(if (v == top) pal.accent else pal.accent.copy(alpha = 0.45f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            labels.forEach { Text(it, color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
        }
    }
}

/** YouTube Music, Spotify's data download, Last.fm, or any app's CSV / JSON export. */
@Composable
private fun ImportSection() {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    var lastFmUser by remember { mutableStateOf("") }
    fun run(label: String, block: suspend () -> HistoryImport.Result) {
        if (busy != null) return
        busy = label; result = null
        scope.launch {
            result = runCatching { block() }.fold(
                { r -> if (r.found == 0) "$label: no plays found." else "$label: ${num(r.added.toLong())} new plays added" + (if (r.found > r.added) " (${num((r.found - r.added).toLong())} were already there)." else ".") },
                { e -> "$label: ${e.message ?: "couldn't import."}" },
            )
            busy = null
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) run("Files") { HistoryImport.files(context, uris) }
    }
    Text(
        "Plays from other apps count in your stats and Wrapped, and help Home's picks. Importing the same plays twice counts them once.",
        color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp),
    )
    ImportRow("YouTube Music", if (YtmAccount.signedIn) "Your history from the signed-in account (YouTube keeps the last few months)." else "Sign in on the Home tab first.") {
        Pill("Import", false, { run("YouTube Music") { HistoryImport.youTubeMusic() } }, enabled = YtmAccount.signedIn && busy == null)
    }
    ImportRow("Spotify", "Spotify > Account privacy > Download your data. Pick the zip it sends (or the StreamingHistory / Streaming_History_Audio files in it).") {
        Pill("Pick files", false, { files.launch(arrayOf("application/zip", "application/json", "text/*", "application/octet-stream")) }, enabled = busy == null)
    }
    ImportRow("Last.fm", "Every scrobble, from your public profile.") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(140.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (lastFmUser.isEmpty()) Text("Username", color = pal.textFaint)
                BasicTextField(lastFmUser, { lastFmUser = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(pal.accent))
            }
            Spacer(Modifier.width(8.dp))
            Pill("Import", false, { run("Last.fm") { HistoryImport.lastFm(lastFmUser) { p, n -> busy = "Last.fm: page $p of $n" } } }, enabled = busy == null && lastFmUser.isNotBlank())
        }
    }
    ImportRow("Other apps", "A CSV or JSON of your plays from any app or export tool (Apple Music's Play Activity, Deezer, SoundCloud…), with title, artist and time columns.") {
        Pill("Pick files", false, { files.launch(arrayOf("text/*", "application/json", "application/zip", "application/octet-stream")) }, enabled = busy == null)
    }
    busy?.let { b ->
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = pal.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Importing: $b…", color = pal.textDim)
        }
    }
    result?.let { Text(it, color = Color.White, modifier = Modifier.padding(top = 10.dp)) }
}

@Composable
private fun ImportRow(name: String, sub: String, action: @Composable () -> Unit) {
    val pal = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(sub, color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
        action()
    }
}

// ---- Wrapped ---------------------------------------------------------------------------------

/**
 * Wrapped, Spotify / YouTube Music style: full-screen slides about the year, tapped through
 * (right side: next, left side: back), each moving on by itself after a few seconds.
 */
@Composable
fun WrappedDialog(year: Int, onDismiss: () -> Unit) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val context = LocalContext.current
        val pal = LocalPalette.current
        val s = remember(year) { ListenStats.summary(year) }
        val library = remember { LocalLibrary.cachedSongs().orEmpty() }
        val app by com.ltots.app.audio.AudioEngine.settings.collectAsState()
        // Songs on the phone that have never been played at all (for the graveyard).
        val unplayed = remember(library) {
            val ever = ListenStats.summary(null).songs.mapTo(HashSet()) { it.key }
            library.count { ListenStats.key(it.title, it.artist) !in ever }
        }
        val slides = remember(s, app.wrappedTone, app.sleepRule, app.wrappedSlides, unplayed) {
            wrappedSlides(s, app.wrappedSlides, com.ltots.app.data.WrappedStats.lines(s, app.wrappedTone, app.sleepRule, unplayed, library.size))
        }
        var at by remember { mutableIntStateOf(0) }
        var progress by remember { mutableStateOf(0f) }
        BackHandler { onDismiss() }
        LaunchedEffect(at) {
            progress = 0f
            val steps = 60
            repeat(steps) { delay(SLIDE_MS / steps); progress = (it + 1f) / steps }
            if (at < slides.lastIndex) at++
        }
        val hues = listOf(pal.accent, Color(0xFF6A3DFF), Color(0xFFFF4D8D), Color(0xFF00C8FF), Color(0xFFFFB020), Color(0xFF2BD67B), pal.accent)
        Box(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(hues[at % hues.size].copy(alpha = 0.85f), Color(0xFF07060C))))
                .pointerInput(slides.size) {
                    detectTapGestures { o ->
                        if (o.x < size.width / 3f) { if (at > 0) at-- } else if (at < slides.lastIndex) at++ else onDismiss()
                    }
                },
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                // Progress segments, one per slide.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    slides.indices.forEach { i ->
                        val f = when { i < at -> 1f; i == at -> progress; else -> 0f }
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
                            Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(Color.White))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("LToTS Wrapped $year", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                }
                AnimatedContent(at, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "wrapped", modifier = Modifier.weight(1f)) { i ->
                    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
                        WrappedSlide(slides[i], library)
                    }
                }
                if (at == slides.lastIndex) Pill("Share a screenshot of this · Close", true, onDismiss, Modifier.align(Alignment.CenterHorizontally))
            }
        }
        LaunchedEffect(Unit) { if (s.plays == 0) Toast.makeText(context, "Nothing played in $year yet", Toast.LENGTH_SHORT).show() }
    }
}

private const val SLIDE_MS = 6_000L

private sealed interface Slide {
    data class Big(val small: String, val big: String, val sub: String) : Slide
    data class TopSong(val s: com.ltots.app.data.SongStat) : Slide
    data class List5(val title: String, val rows: List<Pair<String, String>>) : Slide
    data class Summary(val s: StatsSummary) : Slide
}

private fun wrappedSlides(s: StatsSummary, which: com.ltots.app.data.WrappedSlides, lines: List<com.ltots.app.data.WrappedLine>): List<Slide> {
    if (s.plays == 0) return listOf(Slide.Big("Wrapped ${s.year}", "Nothing yet", "Play some songs and come back."))
    val out = ArrayList<Slide>()
    out += Slide.Big("Your ${s.year}", "Wrapped", "A year of music, in a few taps.")
    val stats = lines.map { Slide.Big(it.small, it.big, it.sub) }
    if (which == com.ltots.app.data.WrappedSlides.STATS) {
        out += stats
        if (stats.isEmpty()) out += Slide.Big("Not enough yet", "Keep listening", "The stats need a little more of your year to have an opinion about.")
        out += Slide.Summary(s)
        return out
    }
    val days = s.totalMs / 86_400_000.0
    out += Slide.Big("You listened for", "${minutes(s.totalMs)} minutes",
        (if (days >= 1) "That's %.1f whole days of music".format(Locale.ROOT, days) else "${num(s.plays.toLong())} plays") + ", on ${s.activeDays} different days.")
    s.songs.firstOrNull()?.let { out += Slide.TopSong(it) }
    if (s.songs.size >= 2) out += Slide.List5("Your top songs", s.songs.take(5).map { it.title to "${it.artist} · ${it.plays} plays" })
    s.artists.firstOrNull()?.let { a ->
        out += Slide.Big("Your top artist", a.name, "${minutes(a.ms)} minutes, ${a.plays} plays across ${a.songs} songs.")
    }
    if (s.artists.size >= 2) out += Slide.List5("Your top artists", s.artists.take(5).map { it.name to "${minutes(it.ms)} minutes" })
    if (which == com.ltots.app.data.WrappedSlides.BOTH) out += stats
    val peakHour = s.hours.indices.maxBy { s.hours[it] }
    val peakMonth = s.months.indices.maxBy { s.months[it] }
    out += Slide.Big("You listen most at", hourName(peakHour),
        "Your biggest month was ${monthName(peakMonth)}" + (s.topDay?.let { (m, d, ms) -> ", and your biggest day ${monthName(m)} $d (${minutes(ms)} minutes)." } ?: "."))
    out += Slide.Summary(s)
    return out
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.WrappedSlide(slide: Slide, library: List<Track>) {
    when (slide) {
        is Slide.Big -> {
            Text(slide.small, color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(slide.big, color = Color.White, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(vertical = 10.dp))
            Text(slide.sub, color = Color.White.copy(alpha = 0.85f), fontSize = 17.sp)
        }
        is Slide.TopSong -> {
            val t = slide.s
            Text("Your top song", color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            val track = remember(t) { library.firstOrNull { ListenStats.key(it.title, it.artist) == t.key } }
            Box(Modifier.padding(vertical = 18.dp).align(Alignment.CenterHorizontally)) { Art(track?.id, 220.dp) }
            Text(t.title, color = Color.White, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(t.artist, color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp)
            Text("${t.plays} plays · ${minutes(t.ms)} minutes", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
        }
        is Slide.List5 -> {
            Text(slide.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 16.dp))
            slide.rows.forEachIndexed { i, (a, b) ->
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(44.dp))
                    Column {
                        Text(a, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(b, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        is Slide.Summary -> {
            val s = slide.s
            Text("${s.year} in LToTS", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(bottom = 18.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Top artists", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    s.artists.take(5).forEachIndexed { i, a -> Text("${i + 1}  ${a.name}", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Column(Modifier.weight(1f)) {
                    Text("Top songs", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    s.songs.take(5).forEachIndexed { i, t -> Text("${i + 1}  ${t.title}", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            Text("Minutes listened", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, modifier = Modifier.padding(top = 18.dp))
            Text(minutes(s.totalMs), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text("${num(s.plays.toLong())} plays · ${num(s.songs.size.toLong())} songs · ${s.activeDays} days", color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** A song's cover from the phone, or a note when it isn't on the phone. */
@Composable
private fun Art(trackId: Long?, size: Dp) {
    val context = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, trackId) { value = trackId?.let { LocalLibrary.artwork(context, it, 512) } }
    Box(Modifier.size(size).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(remember(b) { b.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(size / 3))
    }
}

/** All stats > Wrapped style: the tone of the stat screens, what counts as asleep, which slides show. */
@Composable
private fun WrappedStyle() {
    val pal = LocalPalette.current
    val app by com.ltots.app.audio.AudioEngine.settings.collectAsState()
    SectionTitle("Wrapped style", Modifier.padding(top = 22.dp))
    Text("Tone", color = Color.White, style = MaterialTheme.typography.bodyMedium)
    com.ltots.app.ui.components.SegmentedToggle(
        com.ltots.app.data.WrappedTone.entries.map { it.label }, app.wrappedTone.ordinal, Modifier.padding(vertical = 8.dp),
    ) { i -> com.ltots.app.audio.AudioEngine.updateSettings { it.copy(wrappedTone = com.ltots.app.data.WrappedTone.entries[i]) } }
    Text("Asleep means", color = Color.White, style = MaterialTheme.typography.bodyMedium)
    com.ltots.app.ui.components.SegmentedToggle(
        com.ltots.app.data.SleepRule.entries.map { it.label }, app.sleepRule.ordinal, Modifier.padding(vertical = 8.dp),
    ) { i -> com.ltots.app.audio.AudioEngine.updateSettings { it.copy(sleepRule = com.ltots.app.data.SleepRule.entries[i]) } }
    Text(
        when (app.sleepRule) {
            com.ltots.app.data.SleepRule.NIGHT -> "A song counts when it plays between midnight and 5 am and the phone hasn't been touched for 20 minutes."
            com.ltots.app.data.SleepRule.TIMER -> "A song counts when it plays while the sleep timer is running."
            com.ltots.app.data.SleepRule.EITHER -> "A song counts when it plays late with the phone untouched for 20 minutes, or while the sleep timer is running."
        },
        color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
    )
    Text("Slides", color = Color.White, style = MaterialTheme.typography.bodyMedium)
    com.ltots.app.ui.components.SegmentedToggle(
        com.ltots.app.data.WrappedSlides.entries.map { it.label }, app.wrappedSlides.ordinal, Modifier.padding(vertical = 8.dp),
    ) { i -> com.ltots.app.audio.AudioEngine.updateSettings { it.copy(wrappedSlides = com.ltots.app.data.WrappedSlides.entries[i]) } }
}
