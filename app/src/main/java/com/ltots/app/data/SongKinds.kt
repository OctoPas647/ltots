package com.ltots.app.data

import android.content.Context
import com.ltots.app.identify.AiModels
import com.ltots.app.identify.ClipLoader
import com.ltots.app.identify.MixedFft
import com.ltots.app.moods.Clap
import com.ltots.app.moods.MoodIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Tells songs apart from every other audio file on the phone — voice recordings, call
 * recordings, WhatsApp / Telegram voice notes, ringtones and notification sounds, podcasts —
 * for the Library's **Songs** view (**All** shows everything).
 *
 * Most files are settled at once by what they plainly are: where they live (Recordings,
 * WhatsApp Voice Notes, Ringtones…), what Android's media database says, their name
 * ("PTT-2026…", "Call recording…") and their length (a few seconds is a sound, not a song).
 * Properly tagged music and LToTS's own downloads are songs. The rest — say a stray "audio.mp3"
 * in Downloads — is **listened to by AI**: the on-device CLAP model (the same one that finds
 * moods) hears whether it's music, someone talking or just noise. Without that model a simpler
 * sound check decides (music keeps sounding; speech comes in syllables with pauses).
 */
object SongKinds {
    enum class Kind { SONG, NOT_SONG, UNSURE }

    /** What the listening decided for an unsure file. */
    data class Verdict(val song: Boolean, val byAi: Boolean, val what: String)

    /** Files shorter than this are sounds (notifications, ringtones, clips), not songs. */
    const val MIN_SONG_MS = 30_000L

    private val _verdicts = MutableStateFlow<Map<String, Verdict>>(emptyMap())
    /** By [Track.fileKey]. */
    val verdicts: StateFlow<Map<String, Verdict>> = _verdicts

    data class Progress(val done: Int, val total: Int)
    private val _progress = MutableStateFlow<Progress?>(null)
    /** Set while files are being listened to. */
    val progress: StateFlow<Progress?> = _progress

    private var loaded = false
    private val lock = Mutex()

    private fun file() = File(Store.rootDir, "song_kinds.json")

    // ---- rules ---------------------------------------------------------------------------

    private val notSongFolders = listOf(
        Regex("""^(call|calls|call[ _-]?recordings?|callrecord(er|ing)?s?|phone[ _-]?calls?)$""") to "Call recording",
        Regex("""^(recordings?|recorder|sound[ _-]?recorder|voice[ _-]?recorder|voice[ _-]?recordings?|audio[ _-]?recorder|records?|memos?|voice[ _-]?memos?|запис(и)?|диктофон)$""") to "Voice recording",
        Regex("""^(whatsapp voice notes|whatsapp business voice notes|voice notes|voice|voice messages|telegram voice|viber voice)$""") to "Voice note",
        Regex("""^(notifications|ringtones|alarms|ui|sounds|notification sounds)$""") to "Ringtone or notification sound",
        Regex("""^(podcasts|audiobooks)$""") to "Podcast or audiobook",
    )
    private val notSongNames = listOf(
        Regex("""^ptt-\d{8}-wa\d+""", RegexOption.IGNORE_CASE) to "Voice note",
        Regex("""^(audio_)?voice[ _-]?(message|note|\d)""", RegexOption.IGNORE_CASE) to "Voice note",
        Regex("""^(call|call[ _-]?recording|callrec|call_)""", RegexOption.IGNORE_CASE) to "Call recording",
        Regex("""^\+?\d[\d ()-]{6,}[_ -]""") to "Call recording",
        Regex("""^(recording|rec|new recording|voice recording|record|sound record|запис)[ _-]?\d*""", RegexOption.IGNORE_CASE) to "Voice recording",
        Regex("""^(screen[ _-]?record)""", RegexOption.IGNORE_CASE) to "Screen recording sound",
    )
    private val voiceCodecs = setOf("audio/amr", "audio/amr-wb", "audio/3gpp", "audio/3gpp2", "audio/qcelp", "audio/evrc")

    /** What a file plainly is, without listening to it, and why. */
    fun byRules(t: Track): Pair<Kind, String> {
        val f = t.mediaFlags
        when {
            f and Track.KIND_RECORDING != 0 -> return Kind.NOT_SONG to "Voice recording"
            f and (Track.KIND_RINGTONE or Track.KIND_NOTIFICATION or Track.KIND_ALARM) != 0 && f and Track.KIND_MUSIC == 0 ->
                return Kind.NOT_SONG to "Ringtone or notification sound"
            f and (Track.KIND_PODCAST or Track.KIND_AUDIOBOOK) != 0 -> return Kind.NOT_SONG to "Podcast or audiobook"
        }
        if (t.durationMs in 1 until MIN_SONG_MS) return Kind.NOT_SONG to "Short sound"
        val segments = t.folder.orEmpty().lowercase(Locale.ROOT).split('/').map { it.trim() }.filter { it.isNotEmpty() }
        // LToTS's own downloads are songs.
        if (segments.any { it == "ltots" }) return Kind.SONG to "Downloaded with LToTS"
        for (seg in segments) for ((re, why) in notSongFolders) if (re.matches(seg)) return Kind.NOT_SONG to why
        if (segments.any { it.contains("sound_recorder") || it.contains("soundrecorder") || it.contains("callrecord") }) return Kind.NOT_SONG to "Voice recording"
        val name = t.fileName.orEmpty()
        for ((re, why) in notSongNames) if (re.containsMatchIn(name)) return Kind.NOT_SONG to why
        if (t.mime?.lowercase(Locale.ROOT) in voiceCodecs) return Kind.NOT_SONG to "Voice recording"
        // Properly tagged music: a real artist and album, a song's length.
        // (Recorders don't write artist and album tags.)
        val tagged = t.artist.isNotBlank() && t.artist != Track.UNKNOWN && t.album.isNotBlank() &&
            !segments.any { it == t.album.lowercase(Locale.ROOT) } && t.durationMs in 60_000L..20 * 60_000L
        if (tagged) return Kind.SONG to "Tagged music"
        return Kind.UNSURE to "Not sure yet"
    }

    /** Whether [t] belongs in the Songs view. Unsure files the AI hasn't heard yet stay in when they look like music. */
    fun isSong(t: Track, verdicts: Map<String, Verdict>): Boolean = when (byRules(t).first) {
        Kind.SONG -> true
        Kind.NOT_SONG -> false
        Kind.UNSURE -> verdicts[t.fileKey]?.song ?: ((t.mediaFlags == 0 || t.mediaFlags and Track.KIND_MUSIC != 0) && t.durationMs >= 60_000L)
    }

    /** What [t] is, in a few words (for the All view). */
    fun describe(t: Track, verdicts: Map<String, Verdict>): String? {
        val (kind, why) = byRules(t)
        return when (kind) {
            Kind.NOT_SONG -> why
            Kind.SONG -> null
            Kind.UNSURE -> verdicts[t.fileKey]?.takeIf { !it.song }?.what
        }
    }

    fun songs(tracks: List<Track>, verdicts: Map<String, Verdict> = _verdicts.value): List<Track> = tracks.filter { isSong(it, verdicts) }

    // ---- listening -------------------------------------------------------------------------

    fun load() {
        synchronized(this) {
            if (loaded) return
            loaded = true
        }
        val map = runCatching {
            val o = JSONObject(file().readText())
            o.keys().asSequence().associateWith { k ->
                val v = o.getJSONObject(k)
                Verdict(v.getBoolean("song"), v.optBoolean("ai"), v.optString("what"))
            }
        }.getOrDefault(emptyMap())
        _verdicts.value = map
    }

    private fun save() {
        val o = JSONObject()
        _verdicts.value.forEach { (k, v) -> o.put(k, JSONObject().put("song", v.song).put("ai", v.byAi).put("what", v.what)) }
        Store.writeLater(file()) { o.toString() }
    }

    /**
     * Listens to the unsure files among [tracks] that haven't been heard yet (each file once).
     * With the mood model downloaded, CLAP decides; files heard only by the simple check are
     * heard again by the AI once it's there.
     */
    suspend fun classify(context: Context, tracks: List<Track>) = withContext(Dispatchers.Default) {
        load()
        lock.withLock {
            val ai = AiModels.isInstalled(AiModels.Model.MOODS)
            val todo = tracks.filter { byRules(it).first == Kind.UNSURE }
                .filter { t -> _verdicts.value[t.fileKey].let { it == null || (ai && !it.byAi) } }
                .distinctBy { it.fileKey }
            if (todo.isEmpty()) return@withLock
            _progress.value = Progress(0, todo.size)
            val text = if (ai) MoodIndex.text(context).kinds else emptyList()
            val clap = if (ai && text.size >= 2) runCatching { Clap(AiModels.file(AiModels.Model.MOODS)) }.getOrNull() else null
            try {
                todo.forEachIndexed { i, t ->
                    currentCoroutineContext().ensureActive()
                    val v = runCatching { if (clap != null) listenAi(context, clap, text, t) else listenSimple(context, t) }.getOrNull()
                    if (v != null) _verdicts.value = _verdicts.value + (t.fileKey to v)
                    _progress.value = Progress(i + 1, todo.size)
                    if (i % 10 == 9) save()
                }
            } finally {
                clap?.close()
                save()
                _progress.value = null
            }
        }
    }

    /** CLAP: is it closer to "a song", "people talking" or "noise"? */
    private suspend fun listenAi(context: Context, clap: Clap, kinds: List<com.ltots.app.moods.Prompt>, t: Track): Verdict? {
        val emb = MoodIndex.embeddings.value[t.profileKey]?.takeIf { it.isNotEmpty() } ?: run {
            val dur = t.durationMs / 1000.0
            val points = if (dur > 40) listOf(0.3, 0.6).map { dur * it - 5 } else listOf(0.0)
            val sum = FloatArray(512)
            var n = 0
            for (start in points) {
                val pcm = ClipLoader.decode(context, t.uri, maxSeconds = 10, startSec = start.coerceAtLeast(0.0), outRate = Clap.RATE, normalized = false)
                if (pcm.size < Clap.RATE * 2) continue
                val e = clap.embed(if (pcm.size > Clap.SAMPLES) pcm.copyOf(Clap.SAMPLES) else pcm)
                for (j in e.indices) sum[j] += e[j]
                n++
            }
            if (n == 0) return null
            Clap.normalize(sum)
        }
        val scores = kinds.associate { it.id to Clap.dot(emb, it.vec) }
        return decideAi(scores)
    }

    /** From CLAP's similarity to each kind: a song unless speech or noise is clearly closer. */
    internal fun decideAi(scores: Map<String, Float>): Verdict {
        val song = scores["song"] ?: 0f
        val speech = scores["speech"] ?: -1f
        val noise = scores["noise"] ?: -1f
        // Measured on real recordings: music of every kind (rap, piano, opera, EDM, pop) sits at
        // 0.22–0.47 for "song", talking — even phone-quality — at or below 0.10.
        return when {
            noise > song && noise > speech -> Verdict(false, true, "Not music (AI)")
            song >= SONG_MIN && song - speech >= SONG_MARGIN -> Verdict(true, true, "Music (AI)")
            else -> Verdict(false, true, "Speech (AI)")
        }
    }
    private const val SONG_MIN = 0.17f
    private const val SONG_MARGIN = 0.06f

    /** Without the AI: a sound check on two 10-second moments. */
    private suspend fun listenSimple(context: Context, t: Track): Verdict? {
        val dur = t.durationMs / 1000.0
        val points = if (dur > 40) listOf(0.3, 0.6).map { dur * it - 5 } else listOf(0.0)
        val feats = points.mapNotNull { start ->
            val pcm = ClipLoader.decode(context, t.uri, maxSeconds = 10, startSec = start.coerceAtLeast(0.0), normalized = true)
            if (pcm.size < 16_000 * 3) null else soundFeatures(pcm, 16_000)
        }
        if (feats.isEmpty()) return null
        val f = SoundFeatures(feats.map { it.lowEnergy }.average(), feats.map { it.silence }.average(), feats.map { it.sustain }.average())
        return if (musicLike(f)) Verdict(true, false, "Music") else Verdict(false, false, "Speech or recording")
    }

    /**
     * [lowEnergy]: share of 20 ms frames much quieter than the second around them (pauses
     * between words); [silence]: share of near-silent frames; [sustain]: share of strong
     * spectral peaks that hold for a while (notes, chords).
     */
    internal data class SoundFeatures(val lowEnergy: Double, val silence: Double, val sustain: Double)

    internal fun musicLike(f: SoundFeatures): Boolean {
        // Speech: lots of short dips and gaps, few held tones. Music: steady sound, held notes.
        // Measured on real recordings: music (rap, piano, opera, EDM, pop) scores −1.3…−0.1,
        // talking +0.6…+1.0 (more for phone-quality calls).
        val speechScore = 2.2 * f.lowEnergy + 1.5 * f.silence - 1.6 * f.sustain
        return speechScore < 0.2
    }

    internal fun soundFeatures(pcm: FloatArray, rate: Int): SoundFeatures {
        val frame = rate / 50 // 20 ms
        val n = pcm.size / frame
        val rms = DoubleArray(n) { i ->
            var s = 0.0
            for (j in i * frame until (i + 1) * frame) s += pcm[j] * pcm[j]
            sqrt(s / frame)
        }
        val peak = rms.maxOrNull()?.takeIf { it > 0 } ?: return SoundFeatures(1.0, 1.0, 0.0)
        val win = 50 // 1 s
        var low = 0
        for (i in 0 until n) {
            val a = max(0, i - win / 2); val b = minOf(n, i + win / 2)
            var m = 0.0
            for (j in a until b) m += rms[j]
            m /= (b - a)
            if (rms[i] < 0.5 * m) low++
        }
        val silent = rms.count { it < 0.03 * peak }

        // Held tones: peaks in a 1024-point spectrum (64 ms at 16 kHz) that stay put for 3+ frames.
        val size = 1024
        val hop = 512
        val fft = MixedFft(size)
        val window = DoubleArray(size) { 0.5 - 0.5 * cos(2 * PI * it / size) }
        val re = DoubleArray(size); val im = DoubleArray(size)
        var prev: Set<Int> = emptySet()
        var prev2: Set<Int> = emptySet()
        var held = 0; var peaks = 0
        var s = 0
        while (s + size <= pcm.size) {
            for (i in 0 until size) { re[i] = pcm[s + i] * window[i]; im[i] = 0.0 }
            fft.forward(re, im)
            val mag = DoubleArray(size / 2) { ln(1e-9 + re[it] * re[it] + im[it] * im[it]) }
            val top = mag.maxOrNull() ?: 0.0
            val now = HashSet<Int>()
            // 100 Hz – 4 kHz, local maxima within 25 dB (≈5.8 in ln power) of the loudest.
            for (b in 7 until 256) if (mag[b] > mag[b - 1] && mag[b] >= mag[b + 1] && mag[b] > top - 5.8) now += b
            peaks += now.size
            held += now.count { b -> (b - 1..b + 1).any { it in prev } && (b - 1..b + 1).any { it in prev2 } }
            prev2 = prev; prev = now
            s += hop
        }
        return SoundFeatures(low / n.toDouble(), silent / n.toDouble(), if (peaks == 0) 0.0 else held / peaks.toDouble())
    }
}
