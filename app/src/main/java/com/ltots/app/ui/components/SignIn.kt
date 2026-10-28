package com.ltots.app.ui.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ltots.app.identify.Platform
import com.ltots.app.identify.Platforms
import com.ltots.app.ui.theme.LocalPalette

/**
 * Signing in to a site (Instagram, Facebook, TikTok…) so the song finder can open posts it
 * hides from guests. The site's own sign-in page opens in the app; the password goes only to
 * the site. Afterwards the session is kept on the phone for the download engine.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignInDialog(platform: Platform, onDismiss: () -> Unit, onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    var note by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(if (pal.dark) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sign in to ${platform.label}", color = pal.text, style = MaterialTheme.typography.titleMedium)
                    Text(note ?: "Sign in as usual, then tap Done.", color = pal.textDim, style = MaterialTheme.typography.bodySmall)
                }
                Pill("Cancel", false, onDismiss)
                Spacer(Modifier.width(8.dp))
                Pill("Done", true, {
                    if (Platforms.saveSignIn(context, platform)) onSignedIn()
                    else note = "Not signed in yet — finish signing in first."
                })
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = Platforms.BROWSER_UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        loadUrl(platform.loginUrl ?: "https://${platform.hosts.first()}")
                    }
                },
            )
        }
    }
}

/** The sites the song finder can sign in to, with sign in / sign out buttons. */
@Composable
fun FinderAccounts() {
    val context = LocalContext.current
    val pal = LocalPalette.current
    var version by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf<Platform?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                } else Pill("Sign in", false, { open = p })
            }
        }
    }
    open?.let { p -> SignInDialog(p, onDismiss = { open = null }, onSignedIn = { open = null; version++ }) }
}
