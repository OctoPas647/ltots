package com.ltots.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ltots.app.accounts.Accounts
import com.ltots.app.elsewhere.MusicApp
import com.ltots.app.elsewhere.OtherApps
import com.ltots.app.elsewhere.Service
import com.ltots.app.ui.components.Pill
import com.ltots.app.ui.components.SectionTitle
import com.ltots.app.ui.components.SignInHost
import com.ltots.app.ui.components.SignInTarget
import com.ltots.app.ui.components.SwitchRow
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ytm.YtmAccount
import kotlinx.coroutines.launch

/** What connecting each service gives you, in one line. */
private fun Service.why(): String = when (this) {
    Service.YOUTUBE_MUSIC -> "Your history, likes and shelves on Home"
    Service.SPOTIFY -> "Top songs & artists, liked songs, what you played lately"
    Service.APPLE_MUSIC -> "Heavy rotation, recently played and your library"
    Service.SOUNDCLOUD -> "Likes and listening history"
    Service.DEEZER -> "Favourites and your own charts"
    Service.LASTFM -> "Every scrobble, from any app (just your username)"
}

/** Opens the right way to connect [s]: its sign-in page, or Last.fm's username box. */
fun connect(s: Service, onLastFm: () -> Unit) {
    when (s) {
        Service.LASTFM -> onLastFm()
        Service.YOUTUBE_MUSIC -> SignInHost.open(SignInTarget.YouTubeMusic) { com.ltots.app.recs.Recommender.invalidate() }
        else -> SignInHost.open(SignInTarget.Music(s)) { com.ltots.app.recs.Recommender.invalidate() }
    }
}

/**
 * Profile > Accounts: every music service in one list, each one tap to connect (signs in on its
 * own page; the password goes only to the service) and one to disconnect.
 */
@Composable
fun MusicAccounts() {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { YtmAccount.load(); Accounts.load() }
    val ytm by YtmAccount.account.collectAsState()
    val conns by Accounts.connections.collectAsState()
    var askLastFm by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in Service.entries) {
            val c = conns[s]
            val connected = if (s == Service.YOUTUBE_MUSIC) ytm != null else c != null
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.label, color = pal.text, fontWeight = FontWeight.SemiBold)
                    val status = when {
                        s == Service.YOUTUBE_MUSIC && ytm != null -> "Signed in as ${ytm!!.name}"
                        c?.error != null -> "Connected · ${c.error}"
                        c != null -> "Connected" + (c.name.takeIf { it.isNotBlank() && it != s.label }?.let { " as $it" } ?: "") +
                            (if (c.lastSync > 0) " · read ${ago(c.lastSync)}" else "")
                        else -> s.why()
                    }
                    Text(status, color = if (c?.error != null) pal.accent else pal.textFaint, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                if (connected) Pill("Disconnect", false, { Accounts.disconnect(s); com.ltots.app.recs.Recommender.invalidate() })
                else Pill("Connect", true, { connect(s) { askLastFm = true } })
            }
        }
        if (conns.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Read again every few hours when Home refreshes.",
                color = pal.textFaint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
            )
            Pill(if (syncing) "Reading…" else "Read now", false, {
                if (!syncing) scope.launch {
                    syncing = true
                    runCatching { Accounts.readAll(context) }
                    com.ltots.app.recs.AccountTaste.invalidate()
                    com.ltots.app.recs.Recommender.invalidate()
                    syncing = false
                }
            }, enabled = !syncing)
        }
        Text(
            "Spotify, Apple Music, SoundCloud and Deezer are read the way their own websites read them, so if one changes its site it may stop working for a while — nothing else breaks.",
            color = pal.textFaint, style = MaterialTheme.typography.bodySmall,
        )
    }
    if (askLastFm) LastFmDialog(onDismiss = { askLastFm = false })
}

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / (24 * 60)} days ago"
    }
}

@Composable
fun LastFmDialog(onDismiss: () -> Unit) {
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect Last.fm") },
        text = {
            Column {
                Text("Your Last.fm username. Your scrobbles are public, so no password is needed.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    user, { user = it; error = null }, singleLine = true, label = { Text("Username") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                error?.let { Text(it, color = pal.accent, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (busy) return@TextButton
                scope.launch {
                    busy = true
                    if (Accounts.connectLastFm(user)) { com.ltots.app.recs.Recommender.invalidate(); onDismiss() }
                    else error = "Couldn't find that profile — check the name (and your connection)."
                    busy = false
                }
            }) { Text(if (busy) "Checking…" else "Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Profile: seeing what other music apps play (notification access), and what LToTS does with it. */
@Composable
fun OtherAppsSettings() {
    val pal = LocalPalette.current
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val access = remember(refresh) { OtherApps.hasAccess(context) }
    var count by remember { mutableStateOf(OtherApps.countPlays(context)) }
    var offer by remember { mutableStateOf(OtherApps.offerContinue(context)) }
    var notify by remember { mutableStateOf(OtherApps.notifyContinue(context)) }
    val plays by OtherApps.plays.collectAsState()
    LaunchedEffect(Unit) { OtherApps.ensureLoaded() }

    SectionTitle("Other music apps")
    Text(
        "LToTS can see what Spotify, YouTube Music, Deezer, SoundCloud, Apple Music and other players are playing — " +
            "to count those songs in your stats and Wrapped, learn your taste, and offer to carry on the song here with your EQ. " +
            "Android calls this notification access; LToTS reads no notifications, only what's playing.",
        color = pal.textDim, style = MaterialTheme.typography.bodySmall,
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (access) "On" + (if (plays.isNotEmpty()) " · ${plays.size} songs heard elsewhere so far" else "") else "Off",
            color = if (access) pal.accent else pal.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
        )
        Pill(if (access) "Android settings" else "Turn on", !access, { OtherApps.openAccessSettings(context) })
    }
    if (access) {
        SwitchRow("Count their songs", count, { on -> count = on; OtherApps.setCountPlays(context, on) },
            sub = "A song heard for 30 s counts in your listening time, Home's stats and Wrapped, like songs played here.")
        SwitchRow("Offer to continue here", offer, { on -> offer = on; OtherApps.setOfferContinue(context, on) },
            sub = "When the song is on your phone, Home shows \"Continue here\": LToTS picks it up at the same second.")
        SwitchRow("…also as a notification", notify, { on -> notify = on; OtherApps.setNotifyContinue(context, on) },
            sub = "A quiet notification while LToTS is closed. Tap it to carry on in LToTS.")
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * Home: "Playing on Spotify: <song> — Continue here", and the one-time suggestion to connect
 * that app's account. Nothing shows when no other app is playing.
 */
@Composable
fun ElsewhereBanner(modifier: Modifier = Modifier, onOpenPlayer: () -> Unit) {
    val pal = LocalPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val np by OtherApps.nowPlaying.collectAsState()
    val prompt by OtherApps.promptFor.collectAsState()
    var askLastFm by remember { mutableStateOf(false) }
    val n = np
    // Where the song is: on the phone (play now) or not (download, then continue).
    val local by androidx.compose.runtime.produceState<Boolean?>(null, n?.title, n?.artist) {
        value = n?.let { OtherApps.findLocal(context, it) != null }
    }
    var note by remember(n?.title) { mutableStateOf<String?>(null) }
    val showContinue = n != null && n.playing && OtherApps.offerContinue(context)
    if (!showContinue && prompt == null) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showContinue && n != null) {
            Column {
                Text("Playing on ${n.appLabel}", color = pal.textDim, style = MaterialTheme.typography.labelSmall)
                Text(
                    n.title + (n.artist.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                    color = pal.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    note ?: when (local) {
                        true -> "It's on your phone: carry on here from the same second, with your EQ."
                        false -> "Not on your phone yet. Get it and LToTS takes over as soon as it's downloaded."
                        null -> " "
                    },
                    color = pal.textFaint, style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (local == true) Pill("Continue here", true, {
                        scope.launch {
                            when (OtherApps.continueHere(context)) {
                                OtherApps.Outcome.PLAYING -> onOpenPlayer()
                                OtherApps.Outcome.NOT_ON_PHONE -> note = "Couldn't find it on the phone."
                                OtherApps.Outcome.NOTHING -> note = "It stopped playing there."
                            }
                        }
                    })
                    if (local == false) Pill("Get it & continue", true, {
                        scope.launch {
                            note = if (OtherApps.downloadAndContinue(context)) "Downloading… LToTS takes over when it's ready."
                            else "Couldn't find it on YouTube Music."
                        }
                    })
                }
            }
        }
        val p = prompt
        if (p != null && p.service != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("You listen on ${p.label}", color = pal.text, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Connect your ${p.service.label} account: Home learns from it and what you play there counts in Wrapped.",
                        color = pal.textDim, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Connect", true, { connect(p.service) { askLastFm = true } })
                    Pill("Not now", false, { OtherApps.snoozePrompt(context, p) })
                }
            }
        }
    }
    if (askLastFm) LastFmDialog(onDismiss = { askLastFm = false })
}

/** Used by the intro: one-line names of the apps LToTS recognises. */
internal val KNOWN_APPS: String = MusicApp.entries.filter { it != MusicApp.OTHER && it != MusicApp.YTM_CLIENT }.joinToString(", ") { it.label }
