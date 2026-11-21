package com.ltots.app.accounts

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ltots.app.elsewhere.Service
import com.ltots.app.identify.Platforms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Spotify's, Apple Music's and SoundCloud's web players sign their own requests with a short
 * session token they work out in the page; there's no stable way to ask for one from outside.
 * So LToTS opens the service's web player out of sight (with the session from signing in), and
 * a small script that runs before the site's own notes the token the player puts on its first
 * request to its API. Nothing else is read; the page is closed at once.
 */
internal object TokenGrabber {
    /** What a service's web player signs its requests with. */
    data class Captured(val bearer: String?, val extra: Map<String, String>, val at: Long = System.currentTimeMillis())

    private val cache = HashMap<Service, Captured>()

    /** How long a captured token is used before a fresh one is fetched. */
    private fun maxAge(s: Service): Long = when (s) {
        Service.SPOTIFY -> 40 * 60_000L // Spotify's last an hour
        else -> 12 * 3600_000L
    }

    private val HOOK = """
        (function(){
          if (window.__ltotsHook) return; window.__ltotsHook = 1;
          function rep(u, h){ try { LToTS.report(String(u || ''), JSON.stringify(h || {})); } catch (e) {} }
          function hdrs(x){ var h = {}; try { if (!x) return h;
            if (typeof x.forEach === 'function') x.forEach(function(v, k){ h[String(k).toLowerCase()] = String(v); });
            else for (var k in x) h[String(k).toLowerCase()] = String(x[k]); } catch (e) {} return h; }
          var of = window.fetch;
          if (of) window.fetch = function(input, init){
            try { var u = (typeof input === 'string') ? input : (input && input.url);
              var h = hdrs(init && init.headers); var h2 = hdrs(input && input.headers);
              for (var k in h2) if (!(k in h)) h[k] = h2[k];
              if (h['authorization'] || h['media-user-token'] || /client_id=/.test(u || '')) rep(u, h); } catch (e) {}
            return of.apply(this, arguments); };
          var oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.setRequestHeader, osend = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function(m, u){ this.__u = u; this.__h = {}; return oo.apply(this, arguments); };
          XMLHttpRequest.prototype.setRequestHeader = function(k, v){ try { this.__h[String(k).toLowerCase()] = String(v); } catch (e) {} return os.apply(this, arguments); };
          XMLHttpRequest.prototype.send = function(){ try { if (this.__h && (this.__h['authorization'] || /client_id=/.test(this.__u || ''))) rep(this.__u, this.__h); } catch (e) {} return osend.apply(this, arguments); };
        })();
    """.trimIndent()

    private fun startUrl(s: Service): String? = when (s) {
        Service.SPOTIFY -> "https://open.spotify.com/collection/tracks"
        Service.APPLE_MUSIC -> "https://music.apple.com/library/recently-added"
        Service.SOUNDCLOUD -> "https://soundcloud.com/you/history"
        else -> null
    }

    /** Whether a request the page made carries what [s] needs; returns it if so. */
    internal fun pick(s: Service, url: String, headers: JSONObject): Captured? {
        val auth = headers.optString("authorization").takeIf { it.isNotBlank() }
        return when (s) {
            Service.SPOTIFY -> if (auth?.startsWith("Bearer ", true) == true &&
                (url.contains("spotify.com") && (url.contains("api") || url.contains("spclient")))
            ) Captured(auth.substringAfter(' ').trim(), emptyMap()) else null
            Service.APPLE_MUSIC -> {
                val user = headers.optString("media-user-token").takeIf { it.isNotBlank() }
                if (auth?.startsWith("Bearer ", true) == true && user != null && url.contains("music.apple.com")) {
                    Captured(auth.substringAfter(' ').trim(), mapOf("media-user-token" to user))
                } else null
            }
            Service.SOUNDCLOUD -> {
                if (!url.contains("api-v2.soundcloud.com")) return null
                val clientId = Regex("""[?&]client_id=([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return null
                val oauth = auth?.takeIf { it.startsWith("OAuth ", true) }?.substringAfter(' ')?.trim()
                    ?: cookie("https://soundcloud.com", "oauth_token")
                Captured(oauth, mapOf("client_id" to clientId))
            }
            else -> null
        }
    }

    fun cookie(url: String, name: String): String? = runCatching {
        CookieManager.getInstance().getCookie(url)?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun forget(s: Service) = synchronized(cache) { cache.remove(s) }

    /** A token for [s]: the one in hand if it's fresh, else read from its web player (up to 30 s). */
    suspend fun token(context: Context, s: Service, fresh: Boolean = false): Captured? {
        if (!fresh) synchronized(cache) { cache[s]?.takeIf { System.currentTimeMillis() - it.at < maxAge(s) }?.let { return it } }
        val url = startUrl(s) ?: return null
        val got = withTimeoutOrNull(30_000L) { grab(context.applicationContext, s, url) }
        if (got != null) synchronized(cache) { cache[s] = got }
        return got
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun grab(context: Context, s: Service, url: String): Captured? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val main = Handler(Looper.getMainLooper())
            var web: WebView? = null
            fun done(c: Captured?) {
                main.post {
                    if (cont.isActive) cont.resume(c)
                    web?.let { w -> runCatching { w.stopLoading(); w.destroy() } }
                    web = null
                }
            }
            val ok = runCatching {
                val w = WebView(context)
                web = w
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.userAgentString = Platforms.BROWSER_UA
                w.settings.mediaPlaybackRequiresUserGesture = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                w.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun report(u: String, headers: String) {
                        val c = runCatching { pick(s, u, JSONObject(headers)) }.getOrNull() ?: return
                        done(c)
                    }
                }, "LToTS")
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(w, HOOK, setOf("*"))
                }
                w.webViewClient = object : WebViewClient() {
                    // Where the document-start script isn't supported: as early as possible, and again.
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { view?.evaluateJavascript(HOOK, null) }
                    override fun onPageFinished(view: WebView?, url: String?) { view?.evaluateJavascript(HOOK, null) }
                }
                w.loadUrl(url)
            }.isSuccess
            if (!ok) done(null)
            cont.invokeOnCancellation { main.post { web?.let { w -> runCatching { w.stopLoading(); w.destroy() } }; web = null } }
        }
    }
}
