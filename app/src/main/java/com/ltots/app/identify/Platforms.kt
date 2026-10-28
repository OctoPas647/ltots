package com.ltots.app.identify

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

/**
 * The sites a song-finder link can come from, and what each needs: how its share links look,
 * whether it hides videos behind a sign-in, and where to sign in.
 */
enum class Platform(
    val label: String,
    /** Host names (without "www." / "m."), matched as the host or a sub-domain of it. */
    val hosts: List<String>,
    /** Where to sign in, for sites that often won't show videos without an account. */
    val loginUrl: String? = null,
    /** Cookie domains to save after signing in. */
    val cookieUrls: List<String> = emptyList(),
) {
    YOUTUBE_MUSIC("YouTube Music", listOf("music.youtube.com")),
    YOUTUBE("YouTube", listOf("youtube.com", "youtu.be", "youtube-nocookie.com")),
    TIKTOK("TikTok", listOf("tiktok.com", "vm.tiktok.com", "vt.tiktok.com", "tiktokv.com"),
        "https://www.tiktok.com/login", listOf("https://www.tiktok.com")),
    INSTAGRAM("Instagram", listOf("instagram.com", "instagr.am", "ig.me"),
        "https://www.instagram.com/accounts/login/", listOf("https://www.instagram.com")),
    THREADS("Threads", listOf("threads.net", "threads.com"),
        "https://www.threads.net/login", listOf("https://www.threads.net")),
    FACEBOOK("Facebook", listOf("facebook.com", "fb.watch", "fb.com", "fb.gg"),
        "https://m.facebook.com/login/", listOf("https://www.facebook.com", "https://m.facebook.com")),
    SNAPCHAT("Snapchat", listOf("snapchat.com", "t.snapchat.com", "story.snapchat.com"),
        "https://accounts.snapchat.com/accounts/v2/login", listOf("https://www.snapchat.com", "https://accounts.snapchat.com")),
    X("X (Twitter)", listOf("x.com", "twitter.com", "t.co", "fxtwitter.com", "vxtwitter.com"),
        "https://x.com/i/flow/login", listOf("https://x.com")),
    REDDIT("Reddit", listOf("reddit.com", "redd.it", "v.redd.it")),
    PINTEREST("Pinterest", listOf("pinterest.com", "pin.it")),
    TWITCH("Twitch", listOf("twitch.tv", "clips.twitch.tv")),
    VIMEO("Vimeo", listOf("vimeo.com")),
    SOUNDCLOUD("SoundCloud", listOf("soundcloud.com", "on.soundcloud.com")),
    DAILYMOTION("Dailymotion", listOf("dailymotion.com", "dai.ly")),
    BILIBILI("Bilibili", listOf("bilibili.com", "b23.tv", "bilibili.tv")),
    VK("VK", listOf("vk.com", "vkvideo.ru", "vk.ru")),
    LIKEE("Likee", listOf("likee.video", "l.likee.video")),
    KWAI("Kwai", listOf("kwai.com", "kw.ai")),
    TUMBLR("Tumblr", listOf("tumblr.com")),
    LINKEDIN("LinkedIn", listOf("linkedin.com", "lnkd.in")),
    OTHER("the site", emptyList());

    val needsLoginSometimes: Boolean get() = loginUrl != null

    companion object {
        fun of(url: String): Platform {
            val host = hostOf(url) ?: return OTHER
            return entries.firstOrNull { p -> p.hosts.any { host == it || host.endsWith(".$it") } } ?: OTHER
        }

        internal fun hostOf(url: String): String? = runCatching { URI(url.trim()).host }.getOrNull()
            ?.lowercase()?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("mobile.")
    }
}

object Platforms {
    /** Short-link hosts that only redirect to the real post. */
    private val SHORT = setOf(
        "vm.tiktok.com", "vt.tiktok.com", "fb.watch", "fb.gg", "t.snapchat.com", "pin.it", "redd.it", "t.co",
        "on.soundcloud.com", "dai.ly", "b23.tv", "l.likee.video", "kw.ai", "lnkd.in", "ig.me",
    )

    /** Query parameters that are only for tracking who shared what; everything else is kept. */
    private val TRACKING = Regex(
        "^(utm_.*|igsh|igshid|fbclid|si|feature|_r|_t|_d|is_from_webapp|sender_device|sender_web_id|share_.*|" +
            "mibextid|rdid|sh|ref|ref_src|ref_url|s|t_|checksum|sec_user_id|u_code|preview_pb|tt_from|user_id|web_id|" +
            "embed_source|from_page|share_app_id|share_link_id|social_share_type|timestamp|tt_from|source|xmt|slof|context)$",
    )

    /**
     * The clean, canonical address of a shared post: short links followed to the real post,
     * share-tracking parameters removed, and a few site quirks smoothed over (Instagram's
     * "/reels/", Facebook's mobile pages, Twitter mirrors…). A failure leaves the link as it was.
     */
    suspend fun canonical(url: String): String = withContext(Dispatchers.IO) {
        var u = url.trim().trimEnd('.', ',', ')', '!', '?')
        if (!u.startsWith("http")) u = "https://$u"
        val host = Platform.hostOf(u)
        if (host != null && (host in SHORT || host.endsWith(".page.link") || (host == "facebook.com" && "/share/" in u))) {
            runCatching { follow(u) }.getOrNull()?.let { u = it }
        }
        tidy(u)
    }

    /** Site quirks and tracking parameters, without touching the network. */
    internal fun tidy(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = Platform.hostOf(url) ?: return url
        var path = uri.rawPath ?: ""
        var h = uri.host
        when (Platform.of(url)) {
            Platform.INSTAGRAM -> path = path.replace(Regex("^/reels/"), "/reel/").replace(Regex("^/[^/]+/(reel|p)/"), "/$1/")
            Platform.X -> if (host != "x.com" && host != "twitter.com" && host != "t.co") h = "x.com"
            Platform.FACEBOOK -> if (h.startsWith("m.") || h.startsWith("mbasic.") || h.startsWith("web.")) h = "www.facebook.com"
            else -> Unit
        }
        val keep = (uri.rawQuery ?: "").split('&').filter { it.isNotBlank() }.filter { kv ->
            val k = URLDecoder.decode(kv.substringBefore('='), "UTF-8").lowercase()
            !TRACKING.matches(k) || (Platform.of(url) == Platform.YOUTUBE && k == "t")
        }
        return buildString {
            append(uri.scheme ?: "https").append("://").append(h)
            if (uri.port != -1) append(':').append(uri.port)
            append(path)
            if (keep.isNotEmpty()) append('?').append(keep.joinToString("&"))
        }
    }

    /** Follows redirects (up to 8) and returns where they end. */
    private fun follow(start: String): String {
        var url = start
        repeat(8) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000; readTimeout = 10_000
                setRequestProperty("User-Agent", BROWSER_UA)
            }
            try {
                val code = c.responseCode
                val loc = c.getHeaderField("Location")
                if (code !in 300..399 || loc.isNullOrBlank()) return url
                url = URL(URL(url), loc).toString()
            } finally {
                c.disconnect()
            }
        }
        return url
    }

    const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"

    // ---- sign-ins ---------------------------------------------------------------------------

    private fun cookieDir(context: Context) = File(context.filesDir, "cookies").apply { mkdirs() }
    private fun cookieFile(context: Context, p: Platform) = File(cookieDir(context), "${p.name.lowercase()}.txt")

    fun isSignedIn(context: Context, p: Platform) = cookieFile(context, p).let { it.isFile && it.length() > 0 }

    fun signedIn(context: Context): List<Platform> = Platform.entries.filter { it.needsLoginSometimes && isSignedIn(context, it) }

    /**
     * Saves the cookies the sign-in page left in the app's web view as a cookies.txt file for
     * the download engine. Returns false when there's nothing that looks like a session.
     */
    fun saveSignIn(context: Context, p: Platform): Boolean {
        val cm = CookieManager.getInstance()
        cm.flush()
        val lines = ArrayList<String>()
        for (u in p.cookieUrls) {
            val domain = "." + (Platform.hostOf(u) ?: continue)
            val raw = cm.getCookie(u) ?: continue
            for (part in raw.split(';')) {
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isEmpty()) continue
                // domain, include subdomains, path, secure, expiry (a year), name, value
                lines += listOf(domain, "TRUE", "/", "TRUE", (System.currentTimeMillis() / 1000 + 365L * 86400).toString(), name, value).joinToString("\t")
            }
        }
        if (lines.isEmpty()) return false
        cookieFile(context, p).writeText("# Netscape HTTP Cookie File\n" + lines.distinct().joinToString("\n") + "\n")
        return true
    }

    fun signOut(context: Context, p: Platform) {
        cookieFile(context, p).delete()
        val cm = CookieManager.getInstance()
        for (u in p.cookieUrls) {
            cm.getCookie(u)?.split(';')?.forEach { part ->
                val name = part.substringBefore('=').trim()
                if (name.isNotEmpty()) cm.setCookie(u, "$name=; Max-Age=0; Path=/")
            }
        }
        cm.flush()
    }

    /** One cookies.txt with every site the user signed in to, for one download; null if none. */
    fun cookiesFor(context: Context, into: File): File? {
        val files = Platform.entries.map { cookieFile(context, it) }.filter { it.isFile && it.length() > 0 }
        if (files.isEmpty()) return null
        into.writeText("# Netscape HTTP Cookie File\n" + files.joinToString("\n") { f -> f.readLines().filter { !it.startsWith("#") }.joinToString("\n") } + "\n")
        return into
    }

    /** The cookie header to send with a page request to [url], from a saved sign-in. */
    fun cookieHeader(context: Context, url: String): String? {
        val f = cookieFile(context, Platform.of(url))
        if (!f.isFile) return null
        return f.readLines().filter { !it.startsWith("#") && it.isNotBlank() }
            .mapNotNull { l -> l.split('\t').takeIf { it.size >= 7 }?.let { "${it[5]}=${it[6]}" } }
            .joinToString("; ").ifBlank { null }
    }

    /** Whether a download engine error means the site wants the user signed in. */
    fun wantsLogin(error: String): Boolean = Regex(
        "log ?in|sign ?in|cookies|rate-?limit|empty media response|requires authentication|private|" +
            "not available|unable to extract|checkpoint|age-restricted|confirm your age|you need to",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(error)
}
