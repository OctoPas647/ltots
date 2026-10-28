package com.ltots.app.identify

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The song finder's AI models. They're too big to put in the APK, so each is downloaded once
 * (on first use, with the user's OK) into the app's own storage and kept there.
 */
object AiModels {
    enum class Model(
        val fileName: String,
        val url: String,
        val bytes: Long,
        val title: String,
        val purpose: String,
        /** Used by the song finder (shown on its model card). */
        val forFinder: Boolean = true,
    ) {
        SEPARATOR(
            "UVR-MDX-NET-Inst_HQ_3.onnx",
            "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/UVR-MDX-NET-Inst_HQ_3.onnx",
            66_759_214L,
            "Music separator",
            "Pulls the song out from talking, voice-overs and noise in the video",
        ),
        LYRICS(
            "ggml-small-q5_1.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            190_085_487L,
            "Lyrics listener (Whisper small)",
            "Writes down the sung words, to find covers and songs the sound match can't",
        ),
        MOODS(
            "clap-htsat-unfused-audio-q8.onnx",
            "https://huggingface.co/Xenova/clap-htsat-unfused/resolve/main/onnx/audio_model_quantized.onnx",
            34_301_667L,
            "Music mood listener (CLAP)",
            "Listens to each of your songs once to find its mood — for themes like Sad, Happy or Chill and AI-made albums",
            forFinder = false,
        ),
        BEAT_NET(
            "beat_this_final0.onnx",
            "https://huggingface.co/tuneperfect/beat-this-onnx/resolve/main/beat_this.onnx",
            81_935_641L,
            "Beat AI (Beat This!)",
            "Finds every beat of each song before it plays, so the cover moves exactly on the beat",
            forFinder = false,
        ),
        BEAT_MEL(
            "beat_this_logmel.onnx",
            "https://huggingface.co/tuneperfect/beat-this-onnx/resolve/main/logmel.onnx",
            4_469_774L,
            "Beat AI: sound reader",
            "Turns the song into the picture of its sound the beat AI reads",
            forFinder = false,
        ),
    }

    data class Status(val installed: Set<Model> = emptySet(), val downloading: Model? = null, val progress: Float = 0f, val error: String? = null)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    private lateinit var dir: File

    fun init(context: Context) {
        dir = File(context.filesDir, "ai").apply { mkdirs() }
        refresh()
    }

    fun file(m: Model) = File(dir, m.fileName)
    fun isInstalled(m: Model) = file(m).let { it.isFile && it.length() == m.bytes }

    private fun refresh() {
        _status.value = _status.value.copy(installed = Model.entries.filter(::isInstalled).toSet())
    }

    fun remove(m: Model) { file(m).delete(); refresh() }

    /** Downloads [m] if it isn't there yet. Resumes a broken download. */
    suspend fun download(m: Model) = withContext(Dispatchers.IO) {
        if (isInstalled(m)) return@withContext
        val part = File(dir, m.fileName + ".part")
        _status.value = _status.value.copy(downloading = m, progress = 0f, error = null)
        try {
            var url = m.url
            var c: HttpURLConnection
            var redirects = 0
            while (true) {
                c = URL(url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                if (part.exists() && part.length() in 1 until m.bytes) c.setRequestProperty("Range", "bytes=${part.length()}-")
                val code = c.responseCode
                if (code in 300..399 && redirects++ < 8) {
                    url = URL(URL(url), c.getHeaderField("Location")).toString()
                    c.disconnect(); continue
                }
                if (code !in 200..299) throw IllegalStateException("Download failed (HTTP $code)")
                break
            }
            val append = c.responseCode == 206
            if (!append) part.delete()
            var have = if (append) part.length() else 0L
            c.inputStream.use { input ->
                java.io.FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - lastReport > 512 * 1024) {
                            lastReport = have
                            _status.value = _status.value.copy(progress = (have.toFloat() / m.bytes).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            c.disconnect()
            if (part.length() != m.bytes) throw IllegalStateException("The download was incomplete (${part.length()} of ${m.bytes} bytes)")
            if (!part.renameTo(file(m))) throw IllegalStateException("Couldn't save the model")
        } catch (e: Exception) {
            _status.value = _status.value.copy(error = "${m.title}: ${e.message ?: e.javaClass.simpleName}")
            throw e
        } finally {
            _status.value = _status.value.copy(downloading = null)
            refresh()
        }
    }
}
