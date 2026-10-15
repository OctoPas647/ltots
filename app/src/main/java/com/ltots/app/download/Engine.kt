package com.ltots.app.download

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class EngineState(
    val ready: Boolean = false,
    val preparing: Boolean = false,
    val updating: Boolean = false,
    val version: String? = null,
    val lastCheck: Long = 0L,
    val message: String? = null,
    val error: String? = null,
)

/**
 * yt-dlp (with Python and ffmpeg) running on the phone — the same engine Seal uses.
 * The app ships a current yt-dlp and can update it in place when YouTube changes something.
 */
object Engine {
    /** yt-dlp version bundled in res/raw/ytdlp. Bump when replacing that file. */
    const val BUNDLED_YTDLP = "2026.08.19"
    private const val WEEK = 7L * 24 * 3600_000L

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state
    private val mutex = Mutex()
    private lateinit var app: Context

    private fun prefsFile() = File(app.filesDir, "ltots/engine.json")
    private fun readPrefs(): JSONObject = runCatching { JSONObject(prefsFile().readText()) }.getOrDefault(JSONObject())
    private fun writePrefs(o: JSONObject) = runCatching { prefsFile().parentFile?.mkdirs(); prefsFile().writeText(o.toString()) }

    fun init(context: Context) {
        app = context.applicationContext
        val p = readPrefs()
        _state.value = _state.value.copy(version = p.optString("installed").ifBlank { null }, lastCheck = p.optLong("lastCheck"))
    }

    /** Unpacks Python/ffmpeg on first use (a few seconds) and installs the bundled yt-dlp. */
    suspend fun ensureReady(): Boolean = mutex.withLock {
        if (_state.value.ready) return true
        _state.value = _state.value.copy(preparing = true, error = null, message = "Preparing the download engine…")
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val prefs = readPrefs()
                val installed = prefs.optString("installed").ifBlank { null }
                val binary = File(app.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}/${YoutubeDL.ytdlpBin}")
                // A newer app version may bundle a newer yt-dlp than the one on disk.
                if (installed == null || installed < BUNDLED_YTDLP) binary.delete()
                YoutubeDL.init(app)
                FFmpeg.init(app)
                val now = if (installed == null || installed < BUNDLED_YTDLP) BUNDLED_YTDLP else installed
                prefs.put("installed", now)
                writePrefs(prefs)
                _state.value = _state.value.copy(version = now)
            }
        }
        _state.value = _state.value.copy(
            ready = ok.isSuccess, preparing = false, message = null,
            error = ok.exceptionOrNull()?.let { "The download engine couldn't start: ${it.message?.take(200)}" },
        )
        ok.isSuccess
    }

    /** Fetches the newest yt-dlp from its GitHub releases. Returns a message for the user. */
    suspend fun update(): String {
        if (!ensureReady()) return _state.value.error ?: "Engine not ready"
        _state.value = _state.value.copy(updating = true, error = null)
        val result = withContext(Dispatchers.IO) {
            runCatching { YoutubeDL.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) }
        }
        val prefs = readPrefs().put("lastCheck", System.currentTimeMillis())
        val msg = result.fold(
            onSuccess = { status ->
                if (status == YoutubeDL.UpdateStatus.DONE) {
                    YoutubeDL.version(app)?.let { prefs.put("installed", it) }
                    "Updated to yt-dlp ${YoutubeDL.version(app) ?: ""}".trim()
                } else "Already up to date"
            },
            onFailure = { "Update failed: ${it.message?.take(160) ?: "no connection?"}" },
        )
        writePrefs(prefs)
        _state.value = _state.value.copy(
            updating = false, version = prefs.optString("installed").ifBlank { null },
            lastCheck = prefs.optLong("lastCheck"), message = msg,
        )
        return msg
    }

    /** Quietly checks for a newer yt-dlp once a week. */
    suspend fun autoUpdateIfDue() {
        if (System.currentTimeMillis() - readPrefs().optLong("lastCheck") < WEEK) return
        update()
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    /**
     * Checks the whole engine on the phone: starts Python, runs yt-dlp, and asks YouTube for a
     * well-known song's audio (without downloading it). Returns a report the user can copy.
     */
    suspend fun selfTest(): String {
        val head = "LToTS engine test · Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.MODEL}) · ABI ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}\n"
        if (!ensureReady()) return head + "FAILED to start: ${_state.value.error}"
        return withContext(Dispatchers.IO) {
            val sb = StringBuilder(head)
            runCatching {
                val v = YoutubeDL.execute(com.yausername.youtubedl_android.YoutubeDLRequest(emptyList()).addOption("--version"), "selftest-version")
                sb.append("yt-dlp ").append(v.out.trim()).append(" (").append(v.elapsedTime).append(" ms)\n")
                val req = com.yausername.youtubedl_android.YoutubeDLRequest("https://music.youtube.com/watch?v=dQw4w9WgXcQ")
                    .addOption("--simulate").addOption("--no-playlist")
                    .addOption("-f", Downloader.FORMAT)
                    .addOption("--print", "%(title)s · format %(format_id)s · %(acodec)s · %(abr)s kbps")
                val r = YoutubeDL.execute(req, "selftest")
                sb.append("OK: ").append(r.out.trim()).append(" (").append(r.elapsedTime / 1000).append(" s)\n")
                if (r.err.isNotBlank()) sb.append(r.err.trim().takeLast(1500)).append('\n')
            }.onFailure {
                sb.append("FAILED: ").append(it.javaClass.simpleName).append(": ").append(it.message?.takeLast(5000))
            }
            sb.toString()
        }
    }
}
