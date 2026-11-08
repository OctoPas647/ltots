package com.ltots.app.ytm

import android.webkit.CookieManager
import com.ltots.app.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.security.MessageDigest

/**
 * The user's own YouTube Music (Google) account, signed in once inside the app. Only the Home
 * tab's recommendations use it — YouTube Music's home feed ("Quick picks", "Mixed for you"…) and
 * the user's YouTube Music history become extra seeds and shelves. Search and downloads stay
 * signed out, so the account is never involved in downloading.
 *
 * The browser session (cookies) is kept in the app's private storage, per LToTS user; the
 * password itself only ever goes to Google's own sign-in page.
 */
object YtmAccount {
    data class Account(val name: String, val handle: String?, val photoUrl: String?)

    /** Where the sign-in page starts; it ends on music.youtube.com once signed in. */
    const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
    private const val ORIGIN = "https://music.youtube.com"
    private val COOKIE_URLS = listOf("https://music.youtube.com", "https://www.youtube.com", "https://accounts.google.com", "https://www.google.com")

    private val _account = MutableStateFlow<Account?>(null)
    /** Null when not signed in. */
    val account: StateFlow<Account?> = _account

    @Volatile private var cookie: String? = null
    private var loadedFor: String? = null

    private fun file() = Store.userFile("ytm_account.json")

    /** Loads the saved session of the LToTS user in use (again after switching user). */
    @Synchronized
    fun load() {
        val f = file()
        if (loadedFor == f.path) return
        loadedFor = f.path
        val o = runCatching { JSONObject(f.readText()) }.getOrNull()
        cookie = o?.optString("cookie")?.takeIf { sapisid(it) != null }
        _account.value = if (cookie == null) null else Account(
            o!!.optString("name").ifBlank { "YouTube Music" },
            o.optString("handle").ifBlank { null },
            o.optString("photo").ifBlank { null },
        )
    }

    val signedIn: Boolean get() { load(); return cookie != null }

    /** The Google sign-in cookie YouTube derives its request signature from. */
    internal fun sapisid(cookie: String): String? {
        val parts = cookie.split(';').map { it.trim() }
        fun get(name: String) = parts.firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }
        return get("SAPISID") ?: get("__Secure-3PAPISID")
    }

    /** Whether the browser is signed in to YouTube right now (the sign-in page checks this). */
    fun browserSignedIn(): Boolean {
        val c = CookieManager.getInstance().getCookie(ORIGIN) ?: return false
        return sapisid(c) != null
    }

    /** Keeps the browser's YouTube session after a sign-in. False when it isn't signed in yet. */
    @Synchronized
    fun saveFromBrowser(): Boolean {
        val cm = CookieManager.getInstance()
        cm.flush()
        val c = cm.getCookie(ORIGIN) ?: return false
        if (sapisid(c) == null) return false
        cookie = c
        loadedFor = file().path
        _account.value = _account.value ?: Account("YouTube Music", null, null)
        persist()
        return true
    }

    /** Fills in the account's name and picture (after signing in). */
    suspend fun refreshInfo() {
        if (!signedIn) return
        val info = runCatching { InnerTube.accountInfo() }.getOrNull() ?: return
        synchronized(this) {
            _account.value = info
            persist()
        }
    }

    private fun persist() {
        val a = _account.value
        val o = JSONObject().put("cookie", cookie ?: "")
        if (a != null) o.put("name", a.name).put("handle", a.handle ?: "").put("photo", a.photoUrl ?: "")
        Store.writeFileNow(file(), o.toString())
    }

    @Synchronized
    fun signOut() {
        cookie = null
        _account.value = null
        runCatching { file().delete() }
        val cm = CookieManager.getInstance()
        for (u in COOKIE_URLS) {
            val host = u.removePrefix("https://")
            cm.getCookie(u)?.split(';')?.forEach { part ->
                val name = part.substringBefore('=').trim()
                if (name.isEmpty()) return@forEach
                for (domain in listOf(host, "." + host.substringAfter('.'))) {
                    cm.setCookie(u, "$name=; Domain=$domain; Path=/; Max-Age=0")
                }
            }
        }
        cm.flush()
    }

    /**
     * Headers that make a request as the signed-in user: the session cookie and YouTube's
     * SAPISIDHASH signature (SHA-1 of "time SAPISID origin"). Null when not signed in.
     */
    fun authHeaders(nowSec: Long = System.currentTimeMillis() / 1000): Map<String, String>? {
        load()
        val c = cookie ?: return null
        val sid = sapisid(c) ?: return null
        return mapOf(
            "Cookie" to c,
            "Authorization" to "SAPISIDHASH ${nowSec}_${sha1("$nowSec $sid $ORIGIN")}",
            "X-Goog-AuthUser" to "0",
            "X-Origin" to ORIGIN,
        )
    }

    internal fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The session stopped working (signed out elsewhere, expired): forget it. */
    fun expired() {
        synchronized(this) {
            cookie = null
            _account.value = null
            runCatching { file().delete() }
        }
    }
}
