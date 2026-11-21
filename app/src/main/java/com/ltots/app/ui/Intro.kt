package com.ltots.app.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ltots.app.elsewhere.OtherApps
import com.ltots.app.elsewhere.Service
import com.ltots.app.playback.KeepAlive
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.glass.GlassPanel
import com.ltots.app.ui.screens.KeepAliveSteps
import com.ltots.app.ui.screens.LastFmDialog
import com.ltots.app.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Showing what LToTS can do:
 *
 *  - **The tour** — a few swipeable cards the first time LToTS opens (sound, Home's picks and
 *    connecting your music accounts, other apps, getting songs, lyrics, Rooms, Wrapped, keeping
 *    music playing on HyperOS, your look). Each card can act at once: connect, turn on, set up.
 *  - **Tips** — a small card the first time each tab is opened, saying what's there that's easy
 *    to miss, and tips for features added since you last looked.
 *
 * Someone who has clearly used LToTS already — 2 hours of listening, or LToTS used on 3
 * different days — never gets the tour, and only gets the tips for features that are new to
 * them (connecting accounts, other apps, keeping music playing). Everything is shown once.
 */
object Intro {
    private const val PREFS = "intro"
    private const val EXPERIENCED_MS = 2 * 3600_000L
    private const val EXPERIENCED_DAYS = 3

    enum class Mode { TOUR, NEW_FEATURES_ONLY, NONE }

    /** The desktop UI tests drive the screens directly: no tour over them unless a test asks. */
    @Volatile var showInTests = false

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Notes today as a day LToTS was used (call once per start). */
    fun noteDay(context: Context) {
        val p = prefs(context)
        val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
        val days = p.getStringSet("days", emptySet())!!
        if (day !in days) p.edit().putStringSet("days", (days + day).toList().takeLast(30).toSet()).apply()
    }

    /** Used LToTS a fair bit already (reads files: call off the main thread). */
    fun experienced(context: Context): Boolean {
        if ((prefs(context).getStringSet("days", emptySet())?.size ?: 0) >= EXPERIENCED_DAYS) return true
        val listened = runCatching { com.ltots.app.data.ListenStats.summary(null).totalMs }.getOrDefault(0L)
        if (listened >= EXPERIENCED_MS) return true
        // Installed before days were counted, and played since: an existing listener.
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val installedDays = pkg?.let { (System.currentTimeMillis() - it.firstInstallTime) / 86_400_000L } ?: 0L
        val history = runCatching { com.ltots.app.data.ListenHistory.ensureLoaded(); com.ltots.app.data.ListenHistory.items.value.size }.getOrDefault(0)
        return installedDays >= EXPERIENCED_DAYS && history >= 20
    }

    fun mode(context: Context): Mode = when {
        prefs(context).getBoolean("tour_done", false) -> Mode.NONE
        experienced(context) -> Mode.NEW_FEATURES_ONLY
        else -> Mode.TOUR
    }

    fun tourDone(context: Context) = prefs(context).edit().putBoolean("tour_done", true).apply()

    /** Shows the tour again (Profile > App settings). */
    fun replay(context: Context) {
        prefs(context).edit().putBoolean("tour_done", false).putBoolean("replay", true).apply()
        _replay.value++
    }

    private val _replay = kotlinx.coroutines.flow.MutableStateFlow(0)
    internal val replayRequests: kotlinx.coroutines.flow.StateFlow<Int> = _replay

    internal fun forcedReplay(context: Context) = prefs(context).getBoolean("replay", false)

    fun tipSeen(context: Context, id: String) = id in prefs(context).getStringSet("tips", emptySet())!!
    fun markTip(context: Context, id: String) {
        val p = prefs(context)
        p.edit().putStringSet("tips", p.getStringSet("tips", emptySet())!! + id).apply()
    }

    /** All tips seen from now on (the tour's "Skip all"). */
    fun markAllTips(context: Context) = TIPS.forEach { markTip(context, it.id) }

    // ---- tips ----------------------------------------------------------------------------------

    /** A one-time card on a tab. [new]: a feature added lately (also shown to experienced users). */
    internal class Tip(
        val id: String, val tab: Tab, val title: String, val text: String, val new: Boolean = false,
        val applies: (Context) -> Boolean = { true },
        val action: Pair<String, (Context) -> Unit>? = null,
    )

    internal val TIPS: List<Tip> = listOf(
        Tip(
            "elsewhere", Tab.HOME, "Listening on Spotify or YouTube Music too?",
            "LToTS can count those songs in your stats and Wrapped, learn your taste from them, and offer to carry a song on here with your EQ.",
            new = true, applies = { !OtherApps.hasAccess(it) }, action = "Turn on" to { c -> OtherApps.openAccessSettings(c) },
        ),
        Tip(
            "home", Tab.HOME, "Home learns what you like",
            "Tap any new song to hear 30 seconds of it. Rolls at the top are mixes dealt from your phone — the dice deals new ones. Long-press a song for Not interested.",
        ),
        Tip(
            "library", Tab.LIBRARY, "More than a list of songs",
            "Mixes builds playlists by game, decade and genre. Duplicates finds songs saved twice. The Colour and Genre buttons sort just for now.",
        ),
        Tip(
            "keepalive", Tab.PLAYER, "Keep the music going when the phone locks",
            "HyperOS closes apps a while after the screen locks. Three switches stop that for LToTS.",
            new = true, applies = { KeepAlive.isXiaomi },
        ),
        Tip(
            "player", Tab.PLAYER, "Your sound, per song",
            "Tap the output chip for the Sound page and the EQ. Any song can keep its own EQ, pitch and speed. Lyrics follow below the cover — tap a line to jump there.",
        ),
        Tip(
            "get", Tab.DOWNLOADS, "Get any song — even from a TikTok",
            "Search YouTube Music or paste a link. Paste a TikTok, Short or Reel (or tap Listen) and the song finder tells you what's playing.",
        ),
        Tip(
            "accounts", Tab.PROFILE, "Connect your music",
            "Spotify, Apple Music, Deezer, SoundCloud, YouTube Music and Last.fm: one tap each in Accounts, and Home's picks get much better.",
            new = true, applies = { c -> Service.entries.none { com.ltots.app.accounts.Accounts.isConnected(c, it) } },
        ),
        Tip(
            "profile", Tab.PROFILE, "Make it yours",
            "Look changes everything you see. Rooms plays your phones as one. Up to 3 people can each have their own EQs and looks.",
        ),
    )
}

/** What a tour card shows, and an optional thing it does right there. */
private class Card(
    val icon: ImageVector,
    val title: String,
    val text: String,
    val extra: (@Composable () -> Unit)? = null,
)

/**
 * The tour over everything, then a tip whenever a tab is opened for the first time. [tab] is
 * the tab on screen; [onTab] opens one.
 */
@Composable
fun IntroHost(tab: Tab, overlayOpen: Boolean, onTab: (Tab) -> Unit) {
    if (android.os.Build.FINGERPRINT == "robolectric" && !Intro.showInTests) return
    val context = LocalContext.current
    val replays by Intro.replayRequests.collectAsState()
    val mode by produceState<Intro.Mode?>(null, replays) {
        value = withContext(Dispatchers.IO) {
            Intro.noteDay(context)
            if (Intro.forcedReplay(context)) Intro.Mode.TOUR else Intro.mode(context)
        }
    }
    var tourOpen by remember(mode) { mutableStateOf(mode == Intro.Mode.TOUR) }
    if (mode == Intro.Mode.NEW_FEATURES_ONLY) LaunchedEffect(Unit) { Intro.tourDone(context) }

    Box(Modifier.fillMaxSize()) {
        val m = mode
        if (m != null && !tourOpen && !overlayOpen) TipFor(tab, newOnly = m == Intro.Mode.NEW_FEATURES_ONLY, onTab = onTab)
        if (tourOpen) Tour(onDone = { skipTips ->
            tourOpen = false
            Intro.tourDone(context)
            context.getSharedPreferences("intro", Context.MODE_PRIVATE).edit().remove("replay").apply()
            if (skipTips) Intro.markAllTips(context)
        })
    }
}

@Composable
private fun BoxScope.TipFor(tab: Tab, newOnly: Boolean, onTab: (Tab) -> Unit) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    var shownVersion by remember { mutableStateOf(0) }
    val tip = remember(tab, shownVersion) {
        Intro.TIPS.firstOrNull { t ->
            t.tab == tab && (!newOnly || t.new) && !Intro.tipSeen(context, t.id) && runCatching { t.applies(context) }.getOrDefault(false)
        }
    }
    var expanded by remember(tip?.id) { mutableStateOf(false) }
    val bottom = com.ltots.app.ui.LocalBottomChrome.current
    AnimatedVisibility(
        visible = tip != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = bottom - 24.dp),
    ) {
        val t = tip ?: return@AnimatedVisibility
        GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.14f) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (t.new) {
                        Text(
                            "NEW", color = pal.onAccent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(CircleShape).background(pal.accent).padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(t.title, color = pal.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                Text(t.text, color = pal.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                if (expanded && t.id == "keepalive") KeepAliveSteps(Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    t.action?.let { (label, act) -> Pill(label, true, { act(context); Intro.markTip(context, t.id); shownVersion++ }) }
                    if (t.id == "keepalive" && !expanded) Pill("Set up", true, { expanded = true })
                    if (t.id == "accounts") Pill("Show me", true, { Intro.markTip(context, t.id); shownVersion++; onTab(Tab.PROFILE) })
                    Pill("Got it", false, { Intro.markTip(context, t.id); shownVersion++ })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tour(onDone: (skipTips: Boolean) -> Unit) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askLastFm by remember { mutableStateOf(false) }
    val conns by com.ltots.app.accounts.Accounts.connections.collectAsState()
    val ytm by com.ltots.app.ytm.YtmAccount.account.collectAsState()
    val cards = remember {
        buildList {
            add(Card(Icons.Rounded.MusicNote, "Welcome to LToTS", "A music player built around sound and control. Here's what it can do — a minute, and you can skip any time."))
            add(Card(Icons.Rounded.GraphicEq, "Sound you shape", "33 EQ presets or a fully parametric EQ, Quick tone sliders, and every song can keep its own EQ, pitch and speed. Bit-perfect to a USB DAC, too."))
            add(Card(Icons.Rounded.Home, "Picks that know you", "Home mixes what you play here with your accounts elsewhere. Connect the ones you use — one tap each, the password only goes to them:") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in Service.entries) {
                        val on = if (s == Service.YOUTUBE_MUSIC) ytm != null else conns.containsKey(s)
                        Pill(if (on) "${s.label} ✓" else s.label, !on, { if (!on) com.ltots.app.ui.screens.connect(s) { askLastFm = true } })
                    }
                }
            })
            add(Card(Icons.Rounded.Apps, "Spotify, YouTube Music… counted too", "Let LToTS see what other music apps play: those songs count in your stats and Wrapped, and when one's on your phone you can carry it on here from the same second.") {
                Pill(if (OtherApps.hasAccess(context)) "On ✓" else "Turn on", !OtherApps.hasAccess(context), { OtherApps.openAccessSettings(context) })
            })
            add(Card(Icons.Rounded.Download, "Get any song", "Search YouTube Music or paste a link: best audio, full tags and cover. Heard a song in a TikTok or Short? Paste it — the song finder tells you what it is."))
            add(Card(Icons.Rounded.Lyrics, "Lyrics that follow", "Time-synced lyrics for the songs on your phone, found by themselves. Tap a line to jump there; on-device AI times the ones that only have plain text."))
            add(Card(Icons.Rounded.SpeakerGroup, "Rooms", "Your phones play as one over Wi-Fi: speakers in sync, earbuds that follow you from one phone to the other. Profile > Rooms."))
            add(Card(Icons.Rounded.Insights, "Your year in music", "Every minute you listen adds up — here and in other apps. Home > All stats, and Wrapped with an opinion (Mild, Snarky or Brutal)."))
            if (KeepAlive.isXiaomi) add(Card(Icons.Rounded.BatteryChargingFull, "Keep playing when locked", "HyperOS closes apps a while after the screen locks. Set these once:") { KeepAliveSteps() })
            add(Card(Icons.Rounded.Palette, "Make it yours", "Liquid glass, colours from the cover, a visualizer round it. Profile > Look has it all, in up to 10 profiles."))
        }
    }
    val pager = rememberPagerState { cards.size }
    BackHandler { if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else onDone(false) }
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            // Nothing behind the tour can be touched.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${pager.currentPage + 1} / ${cards.size}", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Pill("Skip", false, { onDone(false) })
            }
            HorizontalPager(pager, Modifier.weight(1f)) { i ->
                val c = cards[i]
                Box(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    GlassPanel(Modifier.fillMaxWidth(), tintBoost = 0.16f) {
                        Column(
                            Modifier.padding(22.dp).verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.size(72.dp).clip(CircleShape).background(pal.accent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                                Icon(c.icon, null, tint = pal.accent, modifier = Modifier.size(38.dp))
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(c.title, color = pal.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            Text(c.text, color = pal.textDim, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            c.extra?.let { Spacer(Modifier.height(16.dp)); it() }
                        }
                    }
                }
            }
            // Dots and the next step.
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(cards.size) { i ->
                        Box(
                            Modifier.size(if (i == pager.currentPage) 10.dp else 7.dp).clip(CircleShape)
                                .background(if (i == pager.currentPage) pal.accent else Color.White.copy(alpha = 0.35f)),
                        )
                    }
                }
                val last = pager.currentPage == cards.lastIndex
                Pill(if (last) "Start listening" else "Next", true, {
                    if (last) onDone(false) else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                })
            }
        }
    }
    if (askLastFm) LastFmDialog(onDismiss = { askLastFm = false })
}
