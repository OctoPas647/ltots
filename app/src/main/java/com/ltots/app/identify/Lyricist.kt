package com.ltots.app.identify

import java.io.Closeable
import java.io.File

/** whisper.cpp, compiled into the app (see src/main/cpp/whisper). */
internal object WhisperNative {
    @JvmStatic external fun init(modelPath: String): Long
    @JvmStatic external fun free(handle: Long)
    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, threads: Int, language: String): String?

    /** The library is built for ARMv8.2 with dot-product instructions; older chips can't run it. */
    val supported: Boolean by lazy {
        runCatching {
            val info = File("/proc/cpuinfo").readText()
            Regex("""Features\s*:.*\basimddp\b""").containsMatchIn(info)
        }.getOrDefault(false)
    }

    private var loaded = false
    @Synchronized fun load(): Boolean {
        if (loaded) return true
        // Desktop tests load a build for the computer they run on.
        System.getProperty("ltots.whisperlib")?.let { lib -> loaded = runCatching { System.load(lib) }.isSuccess; return loaded }
        if (!supported) return false
        loaded = runCatching { System.loadLibrary("whisper-jni") }.isSuccess
        return loaded
    }
}

/** What the lyrics listener heard. */
data class Heard(val language: String, val text: String)

/**
 * The lyrics listener: Whisper (OpenAI's speech recognition model, run by whisper.cpp)
 * writes down the words being sung, so the song can be found by its lyrics when its sound
 * can't be matched (covers, people singing along, live versions).
 */
class Lyricist private constructor(private val handle: Long) : Closeable {
    fun listen(pcm16k: FloatArray, threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)): Heard? {
        val raw = WhisperNative.transcribe(handle, pcm16k, threads, "auto") ?: return null
        val lang = raw.substringBefore('\n')
        val lines = raw.substringAfter('\n', "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        val text = cleanup(lines)
        return if (text.isBlank()) null else Heard(lang, text)
    }

    override fun close() = WhisperNative.free(handle)

    companion object {
        fun open(model: File): Lyricist? {
            if (!model.isFile || !WhisperNative.load()) return null
            val h = WhisperNative.init(model.absolutePath)
            return if (h == 0L) null else Lyricist(h)
        }

        /** Drops Whisper's usual hallucinations on music ("Thanks for watching", repeats, symbols). */
        internal fun cleanup(lines: List<String>): String {
            val junk = Regex("""(?i)thank(s| you) for watching|subscribe|subtitles by|amara\.org|♪+|\[.*?]|\(.*?music.*?\)""")
            val seen = LinkedHashSet<String>()
            for (l in lines) {
                val t = l.replace(junk, " ").replace(Regex("\\s+"), " ").trim().trim('-', '.', ',', ' ')
                if (t.length < 3) continue
                if (seen.any { it.equals(t, true) }) continue
                seen += t
            }
            return seen.joinToString(" / ")
        }

        /** The best few words to search for: the longest distinct sung line(s), not too long. */
        fun searchPhrase(text: String): String? {
            val parts = text.split(" / ").map { it.trim() }.filter { it.split(' ').size >= 3 }
            if (parts.isEmpty()) return text.split(' ').takeIf { it.size >= 3 }?.joinToString(" ")
            val best = parts.sortedByDescending { it.split(' ').size.coerceAtMost(12) }.first()
            return best.split(' ').take(12).joinToString(" ")
        }
    }
}
