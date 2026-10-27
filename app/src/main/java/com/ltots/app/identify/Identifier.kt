package com.ltots.app.identify

import com.ltots.app.ytm.InnerTube
import com.ltots.app.ytm.YtSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** The steps shown while finding a song. */
enum class Step(val label: String) {
    SOUND("Getting the sound"),
    TAG("Reading the video's music tag"),
    SEPARATE("AI: pulling the music out of voices & noise"),
    MATCH("Matching the sound"),
    SPEED("Trying sped-up & slowed versions"),
    LYRICS("AI: listening to the lyrics"),
    YOUTUBE("Finding it on YouTube Music"),
}

enum class StepState { WAITING, RUNNING, DONE, SKIPPED, FAILED }

data class IdentifyProgress(
    val steps: Map<Step, StepState> = Step.entries.associateWith { StepState.WAITING },
    val details: Map<Step, String> = emptyMap(),
    /** 0..1 within the running step, when known. */
    val fraction: Float? = null,
)

/** Where in the clip to listen. */
internal data class Window(val start: Int, val length: Int, val score: Double)

/**
 * Finds the song in a clip, Shazam-style, and ranks what it finds:
 *  1. the video's own music tag (TikTok's music label),
 *  2. AI separation of music from talking and noise — unless what it pulls out is really an
 *     instrument playing the tune (covers, someone playing along), when the untouched sound is used,
 *  3. Shazam fingerprints of the best moments — of the clip and of the separated music,
 *  4. if that fails, the same at the speeds TikTok edits use (sped up / slowed),
 *  5. if that fails too, AI lyrics listening and a lyrics search,
 * then looks each candidate up on YouTube Music (original and other versions).
 */
class Identifier(
    private val separatorModel: File?,
    private val lyricsModel: File?,
    private val onProgress: (IdentifyProgress) -> Unit,
) {
    private var progress = IdentifyProgress()

    private fun set(step: Step, state: StepState, detail: String? = null, fraction: Float? = null) {
        progress = progress.copy(
            steps = progress.steps + (step to state),
            details = if (detail != null) progress.details + (step to detail) else progress.details,
            fraction = if (state == StepState.RUNNING) fraction else progress.fraction,
        )
        onProgress(progress)
    }

    fun soundRunning(detail: String) = set(Step.SOUND, StepState.RUNNING, detail)

    /** Hits Shazam made, with the window/source/speed they came from. */
    private data class SoundHit(val hit: ShazamHit, val window: Int, val ai: Boolean, val speed: Double)

    suspend fun run(clip: ClipAudio): List<Suggestion> = withContext(Dispatchers.Default) {
        set(Step.SOUND, StepState.DONE, "%.0f s of sound".format(clip.seconds))
        val identities = ArrayList<Identity>()

        // 1. Tag
        if (clip.tag != null) {
            identities += Identity(
                clip.tag.title, clip.tag.artist, album = clip.tag.album,
                clues = listOf(Clue(Clue.Kind.TAG, 3.0, "The video's music tag says so")),
            )
            set(Step.TAG, StepState.DONE, "${clip.tag.title} — ${clip.tag.artist}")
        } else set(Step.TAG, StepState.SKIPPED, "No song tag on this video")

        val pcm = clip.pcm
        if (Dsp.rms(pcm) < 1e-4) throw IllegalStateException("The clip is silent")

        // 2. Separation
        var music: FloatArray? = null
        var voice: FloatArray? = null
        var voiceHeavy = false
        var instrument = false
        if (separatorModel != null && separatorModel.isFile) {
            set(Step.SEPARATE, StepState.RUNNING, "Separating…", 0f)
            runCatching {
                val limit = minOf(pcm.size, SEPARATE_SECONDS * ClipAudio.RATE)
                MusicSeparator(separatorModel).use { sep ->
                    sep.separate(pcm.copyOf(limit)) { f -> set(Step.SEPARATE, StepState.RUNNING, null, f) }
                }
            }.onSuccess { stems ->
                music = stems.music; voice = stems.voice
                val ratio = Dsp.rms(stems.voice) / max(1e-6, Dsp.rms(stems.music))
                // A guitar, piano, violin or sax melody often ends up in the "voice" part.
                // Then it isn't talking over the song: it *is* the song, so keep it in.
                instrument = ratio > 0.5 && instrumentLike(stems.voice, stems.music)
                voiceHeavy = ratio > 0.5 && !instrument
                set(Step.SEPARATE, StepState.DONE, when {
                    instrument -> "An instrument plays the tune — kept it in"
                    ratio > 1.5 -> "Lots of talking over the music — removed it"
                    ratio > 0.5 -> "Voices over the music — separated them"
                    else -> "Mostly music"
                })
            }.onFailure { set(Step.SEPARATE, StepState.FAILED, it.message ?: "The separator failed") }
        } else set(Step.SEPARATE, StepState.SKIPPED, "AI separator not downloaded")
        currentCoroutineContext().ensureActive()

        val musicStem: FloatArray? = music
        val voiceStem: FloatArray? = voice
        // Windows of the untouched sound: where talking is quietest, or simply the loudest
        // moments when the "voice" is an instrument. A loudest-moment window of the untouched
        // sound is always tried as well, in case the separator mistook the tune for a voice.
        val origWindows = if (instrument) pickWindows(pcm, null, null, forStem = false)
            else pickWindows(pcm, musicStem, voiceStem, forStem = false)
        val rawWindows = if (instrument || voiceStem == null) emptyList()
            else pickWindows(pcm, null, null, forStem = false).filter { w -> origWindows.take(2).all { abs(it.start - w.start) >= 6 * ClipAudio.RATE } }
        val stemWindows = musicStem?.let { pickWindows(it, it, voiceStem, forStem = true) }.orEmpty()

        // 3. Shazam at normal speed
        set(Step.MATCH, StepState.RUNNING, "Asking Shazam…")
        val hits = ArrayList<SoundHit>()
        val plan = ArrayList<Triple<Int, Src, Double>>() // window index, source, speed
        origWindows.take(2).indices.forEach { plan += Triple(it, Src.ORIG, 1.0) }
        rawWindows.take(1).indices.forEach { plan += Triple(it, Src.RAW, 1.0) }
        stemWindows.take(2).indices.forEach { plan += Triple(it, Src.STEM, 1.0) }
        val errorCount = java.util.concurrent.atomic.AtomicInteger()
        val lastErr = java.util.concurrent.atomic.AtomicReference<String?>(null)
        fun windowsOf(src: Src) = when (src) { Src.ORIG -> origWindows; Src.RAW -> rawWindows; Src.STEM -> stemWindows }
        suspend fun ask(q: List<Triple<Int, Src, Double>>) = coroutineScope {
            val gate = Semaphore(3)
            q.map { (w, from, speed) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val ai = from == Src.STEM
                        val src = if (ai) musicStem!! else pcm
                        val win = windowsOf(from)[w]
                        val seg = src.copyOfRange(win.start, win.start + win.length)
                        val audio = if (speed == 1.0) seg else Dsp.resample(seg, speed)
                        val clipped = if (audio.size > SEG * ClipAudio.RATE) audio.copyOf(SEG * ClipAudio.RATE) else audio
                        runCatching { Shazam.recognize(ShazamSignature.from(ClipLoader.normalize(clipped))) }
                            .onFailure { errorCount.incrementAndGet(); lastErr.set(it.message) }
                            .getOrNull()?.let { SoundHit(it, w, ai, speed) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        hits += ask(plan)
        val errors = errorCount.get()
        set(Step.MATCH, if (hits.isEmpty() && errors == plan.size) StepState.FAILED else StepState.DONE,
            when {
                hits.isNotEmpty() -> "${hits.distinctBy { Ranking.key(it.hit.title, it.hit.artist) }.size} match(es) from ${hits.size} of ${plan.size} listens"
                errors == plan.size -> lastErr.get() ?: "Couldn't reach Shazam"
                else -> "No match at normal speed"
            })

        // 4. Sped-up / slowed
        val onlyEdits = hits.isNotEmpty() && hits.all { Ranking.versionOf(it.hit.title) in setOf("Sped up", "Slowed") }
        if ((hits.isEmpty() || onlyEdits) && errors < plan.size) {
            // When voices cover the song, sweep the separated music and the untouched sound
            // (the separator may have taken out an instrument playing the tune); otherwise
            // just the untouched sound.
            val sources = if (stemWindows.isNotEmpty() && voiceHeavy) listOf(Src.STEM, Src.ORIG) else listOf(Src.ORIG)
            var tried = 0
            for (batch in SPEEDS.chunked(3)) {
                currentCoroutineContext().ensureActive()
                set(Step.SPEED, StepState.RUNNING, batch.joinToString(", ") { "%.2f×".format(it) }, tried / SPEEDS.size.toFloat())
                val found = ask(batch.flatMap { sp -> sources.map { Triple(0, it, sp) } })
                tried += batch.size
                if (found.isNotEmpty()) {
                    hits += found
                    // A second listen at that speed, elsewhere in the clip, to confirm it.
                    val first = found.first()
                    val from = if (first.ai) Src.STEM else Src.ORIG
                    if (windowsOf(from).size > 1) hits += ask(listOf(Triple(1, from, first.speed)))
                    break
                }
            }
            val sped = hits.firstOrNull { it.speed != 1.0 }
            set(Step.SPEED, StepState.DONE, sped?.let { "Found it at %.2f× — the video ${if (it.speed > 1) "speeds it up" else "slows it down"}".format(it.speed) } ?: "No match at other speeds either")
        } else set(Step.SPEED, StepState.SKIPPED, if (hits.isNotEmpty()) "Not needed" else "Shazam unreachable")

        for (h in hits) {
            val kind = when { h.speed != 1.0 -> Clue.Kind.SOUND_SPEED; h.ai -> Clue.Kind.SOUND_AI; else -> Clue.Kind.SOUND }
            val note = when (kind) {
                Clue.Kind.SOUND -> "Sound match (Shazam)"
                Clue.Kind.SOUND_AI -> "Sound match after AI removed the voices"
                else -> "Sound match at %.2f× speed".format(h.speed)
            }
            identities += Identity(
                h.hit.title, h.hit.artist, album = h.hit.album, year = h.hit.year, coverUrl = h.hit.coverUrl,
                previewUrl = h.hit.previewUrl, speed = h.speed.takeIf { it != 1.0 },
                clues = listOf(Clue(kind, (if (kind == Clue.Kind.SOUND_SPEED) 1.7 else 2.0) * skewFactor(h.hit), note)),
            )
        }

        // 5. Lyrics
        if (hits.isEmpty()) {
            if (lyricsModel != null && lyricsModel.isFile) {
                set(Step.LYRICS, StepState.RUNNING, "Listening…")
                // Whisper always listens to the untouched sound; with an instrument playing the
                // tune, it listens where the clip is loudest rather than where the "voice" is.
                val heard = runCatching { listenToLyrics(pcm, musicStem.takeIf { !instrument }, voiceStem.takeIf { !instrument }) }.getOrNull()
                if (heard != null) {
                    val phrase = Lyricist.searchPhrase(heard.text)
                    set(Step.LYRICS, StepState.RUNNING, "Heard: “${heard.text.take(80)}”")
                    val songs = phrase?.let { runCatching { searchSongs(it) }.getOrNull() }.orEmpty()
                    songs.filter { !it.isVideo }.ifEmpty { songs }.take(3).forEachIndexed { i, s ->
                        identities += Identity(
                            s.title, s.artistText, album = s.album?.name, coverUrl = s.thumbUrl, ytSong = s,
                            clues = listOf(Clue(Clue.Kind.LYRICS, listOf(1.3, 0.8, 0.5)[i], "Lyrics match: “${phrase!!.take(60)}”")),
                        )
                    }
                    set(Step.LYRICS, StepState.DONE, if (songs.isEmpty()) "Heard “${heard.text.take(60)}”, but no song has those words" else "Heard “${heard.text.take(60)}”")
                } else set(Step.LYRICS, StepState.DONE, "Couldn't make out any sung words")
            } else set(Step.LYRICS, StepState.SKIPPED, "AI lyrics listener not downloaded")
        } else set(Step.LYRICS, StepState.SKIPPED, "Not needed")

        // 6. YouTube Music
        val merged = Ranking.merge(identities).take(6)
        if (merged.isEmpty()) {
            set(Step.YOUTUBE, StepState.SKIPPED)
            return@withContext emptyList()
        }
        set(Step.YOUTUBE, StepState.RUNNING, "Looking up ${merged.size} song(s)…")
        val found = coroutineScope {
            merged.map { id ->
                async(Dispatchers.IO) { id.key to runCatching { searchSongs("${Ranking.baseTitle(id.title)} ${id.artist}") }.getOrDefault(emptyList()) }
            }.awaitAll().toMap()
        }
        val rows = Ranking.suggestions(merged, found)
        set(Step.YOUTUBE, StepState.DONE, "${rows.count { it.song != null }} ready to download")
        rows
    }

    private suspend fun searchSongs(q: String): List<YtSong> {
        val r = InnerTube.search(q)
        val first = r.sections.flatMap { it.items }.filterIsInstance<YtSong>()
        val songsChip = r.chips.firstOrNull { it.text.equals("Songs", true) }?.params
        val songs = songsChip?.let { p -> runCatching { InnerTube.search(q, p).sections.flatMap { it.items }.filterIsInstance<YtSong>() }.getOrNull() }.orEmpty()
        return (first + songs).distinctBy { it.videoId }
    }

    private fun listenToLyrics(pcm: FloatArray, music: FloatArray?, voice: FloatArray?): Heard? {
        val lyricist = Lyricist.open(lyricsModel!!) ?: return null
        lyricist.use {
            // Where the voice is strongest while music plays: most likely singing.
            val src = pcm
            val len = minOf(src.size, LYRICS_SECONDS * ClipAudio.RATE)
            var start = 0
            if ((voice == null || music == null) && src.size > len) {
                // No usable voice part: the loudest stretch.
                var best = -1.0
                var s = 0
                while (s + len <= src.size) {
                    val sc = Dsp.rms(src, s, s + len)
                    if (sc > best) { best = sc; start = s }
                    s += ClipAudio.RATE * 3
                }
            } else if (voice != null && music != null && src.size > len) {
                var best = -1.0
                var s = 0
                val n = minOf(voice.size, music.size)
                while (s + len <= n) {
                    val v = Dsp.rms(voice, s, s + len); val m = Dsp.rms(music, s, s + len)
                    val sc = v * minOf(1.0, m / max(1e-6, v))
                    if (sc > best) { best = sc; start = s }
                    s += ClipAudio.RATE * 3
                }
            }
            return it.listen(src.copyOfRange(start, start + len))
        }
    }

    /** Which sound a fingerprint is taken from. */
    private enum class Src {
        /** The untouched sound, at the moments with the least talking over the music. */
        ORIG,
        /** The untouched sound at its loudest moment, voices and all. */
        RAW,
        /** The music the AI separated out. */
        STEM,
    }

    companion object {
        const val SEG = 12

        /**
         * Whether the separator's "voice" part is really an instrument playing the tune — a
         * guitar or piano melody, a violin, a sax — rather than someone talking or singing.
         * Speech comes in syllables with gaps between words; an instrument plays on steadily and
         * swells and fades together with the rest of the music.
         */
        internal fun instrumentLike(voice: FloatArray, music: FloatArray): Boolean {
            val frame = ClipAudio.RATE / 20 // 50 ms
            val n = minOf(voice.size, music.size) / frame
            if (n < 40) return false
            val v = DoubleArray(n) { Dsp.rms(voice, it * frame, (it + 1) * frame) }
            val m = DoubleArray(n) { Dsp.rms(music, it * frame, (it + 1) * frame) }
            // Only where something is playing at all.
            val loud = (v.indices).map { v[it] + m[it] }.sorted()[(n * 0.9).toInt()]
            val live = v.indices.filter { v[it] + m[it] > 0.1 * loud }
            if (live.size < 40) return false
            val vTop = live.map { v[it] }.sorted()[(live.size * 0.9).toInt()]
            if (vTop < 1e-6) return false
            // Share of the time the "voice" keeps sounding (talking is ~55–75 %, an instrument 85 %+).
            val steady = live.count { v[it] > 0.15 * vTop } / live.size.toDouble()
            // How closely its loudness follows the music's, in log scale.
            val lv = live.map { kotlin.math.ln(v[it] + 1e-6) }
            val lm = live.map { kotlin.math.ln(m[it] + 1e-6) }
            val av = lv.average(); val am = lm.average()
            var cov = 0.0; var sv = 0.0; var sm = 0.0
            for (i in lv.indices) { val a = lv[i] - av; val b = lm[i] - am; cov += a * b; sv += a * a; sm += b * b }
            val corr = if (sv > 0 && sm > 0) cov / kotlin.math.sqrt(sv * sm) else 0.0
            return steady >= 0.9 || (steady >= 0.8 && corr >= 0.3)
        }

        /** Shazam reports how far off the speed is; a closer fit is a more believable match. */
        internal fun skewFactor(h: ShazamHit): Double =
            1.0 - minOf(0.4, (abs(h.frequencySkew) + abs(h.timeSkew)) * 4.0)
        const val SEPARATE_SECONDS = 75
        const val LYRICS_SECONDS = 28
        /** Clip speed vs. the record, in the order TikTok edits are common. Shazam tolerates ±5 %. */
        val SPEEDS = listOf(1.26, 0.86, 1.17, 1.36, 0.93, 0.79, 1.08, 1.47, 0.73)

        /**
         * The best 12-second moments to fingerprint: loud music with little talking over it,
         * at least 6 s apart. Short clips are used whole.
         */
        internal fun pickWindows(src: FloatArray, music: FloatArray?, voice: FloatArray?, forStem: Boolean): List<Window> {
            val rate = ClipAudio.RATE
            val len = minOf(src.size, SEG * rate)
            if (src.size <= len + rate) return listOf(Window(0, src.size, 1.0))
            val cands = ArrayList<Window>()
            var s = 0
            while (s + len <= src.size) {
                val e = s + len
                val sc = if (music != null && voice != null && e <= minOf(music.size, voice.size)) {
                    val m = Dsp.rms(music, s, e); val v = Dsp.rms(voice, s, e)
                    if (forStem) m else m / (v + 0.3 * m + 1e-6) * m
                } else Dsp.rms(src, s, e)
                cands += Window(s, len, sc)
                s += 3 * rate
            }
            val picked = ArrayList<Window>()
            for (w in cands.sortedByDescending { it.score }) {
                if (picked.all { abs(it.start - w.start) >= 6 * rate }) picked += w
                if (picked.size == 3) break
            }
            return picked
        }
    }
}
