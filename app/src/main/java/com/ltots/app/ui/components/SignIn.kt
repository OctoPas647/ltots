package com.ltots.app.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ltots.app.identify.Platform
import com.ltots.app.identify.Platforms
import com.ltots.app.ui.theme.LocalPalette
import com.ltots.app.ytm.YtmAccount
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What to sign in to: a site the song finder opens posts on, or YouTube Music for recommendations. */
sealed interface SignInTarget {
    val label: String
    data class Site(val platform: Platform) : SignInTarget { override val label get() = platform.label }
    data object YouTubeMusic : SignInTarget { override val label get() = "YouTube Music" }
}

/**
 * Opens the sign-in page over the whole app (any screen can ask). It's a full page, not a
 * pop-up: the earlier pop-up could end up under the status bar and the keyboard, and on some
 * phones its web page never got the keyboard at all, so signing in was impossible.
 */
object SignInHost {
    class Request(val target: SignInTarget, val onSignedIn: () -> Unit)

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request

    fun open(target: SignInTarget, onSignedIn: () -> Unit = {}) { _request.value = Request(target, onSignedIn) }
    fun close() { _request.value = null }
}

/** Shown by the app's root over everything while a sign-in is open. */
@Composable
fun SignInOverlay() {
    val req by SignInHost.request.collectAsState()
    val r = req ?: return
    SignInPage(r.target, onClose = { SignInHost.close() }, onSignedIn = { SignInHost.close(); r.onSignedIn() })
}

/**
 * The site's own sign-in page inside the app; the password goes only to the site. As soon as the
 * site has signed in (its session cookie appears) the page closes by itself; "Done" checks too.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInPage(target: SignInTarget, onClose: () -> Unit, onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var finishing by remember { mutableStateOf(false) }
    var pageVersion by remember { mutableIntStateOf(0) }

    fun signedInNow(): Boolean = when (target) {
        is SignInTarget.Site -> Platforms.browserSignedIn(target.platform)
        SignInTarget.YouTubeMusic -> YtmAccount.browserSignedIn()
    }

    fun finish(manual: Boolean) {
        if (finishing) return
        val ok = when (target) {
            is SignInTarget.Site -> Platforms.saveSignIn(context, target.platform)
            SignInTarget.YouTubeMusic -> YtmAccount.saveFromBrowser()
        }
        if (ok) {
            finishing = true
            if (target == SignInTarget.YouTubeMusic) scope.launch { YtmAccount.refreshInfo() }
            onSignedIn()
        } else if (manual) note = "Not signed in yet — finish signing in on the page first."
    }

    // Signed in? Checked after every page load and every couple of seconds (some sites sign in
    // without loading a new page).
    LaunchedEffect(pageVersion) {
        repeat(60) {
            if (signedInNow()) { finish(manual = false); return@LaunchedEffect }
            delay(2000)
        }
    }

    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    Column(
        Modifier.fillMaxSize()
            .background(if (pal.dark) Color.Black else Color.White)
            .statusBarsPadding()
            // The keyboard and the system bar never cover the page or its buttons.
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, "Close", tint = pal.text) }
            Column(Modifier.weight(1f)) {
                Text("Sign in to ${target.label}", color = pal.text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    note ?: when (target) {
                        SignInTarget.YouTubeMusic -> "For recommendations from your YouTube Music. It closes by itself once you're in."
                        else -> "Sign in as usual — it closes by itself once you're in."
                    },
                    color = pal.textDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (finishing) CircularProgressIndicator(color = pal.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            else Pill("Done", true, { finish(manual = true) })
            Spacer(Modifier.width(8.dp))
        }
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth().height(2.dp), color = pal.accent)
        else Spacer(Modifier.height(2.dp))
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.setSupportMultipleWindows(false)
                    // A regular phone browser: Google and others refuse sign-ins from "web views".
                    settings.userAgentString = Platforms.BROWSER_UA
                    isFocusable = true
                    isFocusableInTouchMode = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { note = null }
                        override fun onPageFinished(view: WebView?, url: String?) { pageVersion++ }
                    }
                    loadUrl(
                        when (target) {
                            SignInTarget.YouTubeMusic -> YtmAccount.LOGIN_URL
                            is SignInTarget.Site -> target.platform.loginUrl ?: "https://${target.platform.hosts.first()}"
                        }
                    )
                    requestFocus()
                    web = this
                }
            },
            onRelease = { it.stopLoading(); it.destroy() },
        )
    }
}

/**
 * Accounts: YouTube Music (recommendations on Home) and the sites the song finder can sign in
 * to, with sign in / sign out buttons.
 */
@Composable
fun FinderAccounts() {
    val context = LocalContext.current
    val pal = LocalPalette.current
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { YtmAccount.load() }
    val ytm by YtmAccount.account.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YouTube Music", color = pal.text)
                Text(
                    ytm?.let { "Signed in as ${it.name} · Home uses your YouTube Music" } ?: "Sign in so Home recommends from your YouTube Music too",
                    color = pal.textFaint, style = MaterialTheme.typography.labelSmall,
                )
            }
            if (ytm != null) Pill("Sign out", false, { YtmAccount.signOut(); com.ltots.app.recs.Recommender.invalidate() })
            else Pill("Sign in", false, { SignInHost.open(SignInTarget.YouTubeMusic) { com.ltots.app.recs.Recommender.invalidate() } })
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Some sites only show posts to signed-in people. Sign in here once and the song finder can open them. Your password goes only to the site.",
            color = pal.textDim, style = MaterialTheme.typography.bodySmall,
        )
        for (p in Platform.entries.filter { it.needsLoginSometimes }) {
            val signed = remember(version) { Platforms.isSignedIn(context, p) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, color = pal.text, modifier = Modifier.weight(1f))
                if (signed) {
                    Text("Signed in", color = pal.textFaint, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(8.dp))
                    Pill("Sign out", false, { Platforms.signOut(context, p); version++ })
                } else Pill("Sign in", false, { SignInHost.open(SignInTarget.Site(p)) { version++ } })
            }
        }
    }
}
