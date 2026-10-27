package com.ltots.app.identify

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.ltots.app.lyrics.LyricLine
import com.ltots.app.lyrics.Lyrics
import com.ltots.app.lyrics.LyricsDoc
import com.ltots.app.lyrics.SongQuery
import com.ltots.app.ytm.InnerTube
import com.ltots.app.ytm.YtSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * The lyrics card's AI button: works out which song is really playing — from its sound, not its
 * name — and fetches that song's lyrics, lined up with this file.
 *
 * Edits and re-uploads ("Rather Be (But it hits different)" by some channel) carry the wrong
 * name, a different length and often a different speed. Shazam recognises the record from a few
 * moments of the file and says where in the record each moment is, so the record's synced
 * lyrics can be shifted and stretched onto this version. When Shazam doesn't know it, the
 * on-device Whisper model listens for sung words and the song is looked up by them.
 */
object LyricsMatch {
    class Failed(message: String) : Exception(message)

    /** One recognised moment: [fileSec] in this file is [songSec] in the record. */
    internal data class Moment(val fileSec: Double, val songSec: Double?, val hit: ShazamHit, val speed: Double)

    /** How this file's time maps onto the record: songSec = intercept + rate × fileSec. */
    internal data class Timing(val rate: Double, val intercept: Double)

    private const val SEG = 12
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private var job: kotlinx.coroutines.Job? = null

    /** Runs the match for the lyrics card; progress and the result show there. */
    fun start(context: Context, q: SongQuery, trackId: Long) {
        job?.cancel()
        val app = context.applicationContext
        job = scope.launch {
            Lyrics.showMatching(q.key, "Starting…")
            try {
                val doc = match(app, q, trackId) { Lyrics.showMatching(q.key, it) }
                Lyrics.saveMatched(q.key, doc)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Failed) {
                Lyrics.showMatchFailed(q.key, e.message ?: "The AI match didn't work.")
            } catch (e: Exception) {
                Lyrics.showMatchFailed(q.key, "The AI match failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    suspend fun match(context: Context, q: SongQuery, trackId: Long, onStatus: (String) -> Unit): LyricsDoc = withContext(Dispatchers.Default) {
        if (trackId < 0) throw Failed("The AI match works on songs saved on the phone.")
        onStatus("Listening to the song…")
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), trackId)
        val maxSec = ((q.durationMs / 1000).toInt() + 5).coerceIn(30, 900)
        val pcm = ClipLoader.decode(context, uri, maxSec)
        if (pcm.size < 5 * ClipAudio.RATE || Dsp.rms(pcm) < 1e-4) throw Failed("Couldn't hear the song.")

        onStatus("Recognising it from its sound…")
        val moments = recognise(pcm, onStatus)
        if (moments.isNotEmpty()) {
            val song = moments.groupBy { Ranking.key(it.hit.title, it.hit.artist) }.values
                .maxWith(compareBy<List<Moment>> { it.size }.thenBy { g -> -g.minOf { abs(it.hit.timeSkew) } })
            val hit = song.first().hit
            onStatus("It's ${hit.artist} — ${hit.title}. Getting its lyrics…")
            val doc = lyricsFor(q.key, hit.title, hit.artist, hit.album)
                ?: throw Failed("It's ${hit.artist} — ${hit.title}, but no lyrics database has it.")
            return@withContext lineUp(doc, song, pcm.size / ClipAudio.RATE.toDouble(), hit)
        }

        // Not a known record: listen for the words instead.
        val whisper = AiModels.file(AiModels.Model.LYRICS).takeIf { AiModels.isInstalled(AiModels.Model.LYRICS) }
            ?: throw Failed("Shazam didn't recognise it. Download the AI lyrics listener on the Get tab to find songs by their words too.")
        onStatus("AI: listening for the words…")
        val heard = Lyricist.open(whisper)?.use { it.listen(loudest(pcm, 28)) }
            ?: throw Failed("Couldn't recognise it or make out any words.")
        val phrase = Lyricist.searchPhrase(heard.text) ?: throw Failed("Heard only “${heard.text.take(40)}” — not enough to find the song.")
        onStatus("Heard “${phrase.take(50)}” — looking it up…")
        val song = runCatching { searchSongs(phrase) }.getOrNull().orEmpty().firstOrNull()
            ?: throw Failed("Heard “${phrase.take(50)}”, but no song has those words.")
        val doc = lyricsFor(q.key, song.title, song.artistText, song.album?.name)
            ?: throw Failed("It sounds like ${song.artistText} — ${song.title}, but no lyrics database has it.")
        // No timing from the words alone: keep the record's timing only if the lengths agree.
        val sameLength = song.durationSec?.let { abs(it - q.durationMs / 1000.0) <= 4 } == true
        doc.copy(
            synced = doc.synced.takeIf { sameLength },
            plain = doc.plain ?: doc.synced?.joinToString("\n") { it.text },
            matchedAs = "${song.artistText} — ${song.title}",
            note = "AI matched it by the words it heard",
        )
    }

    /** Fingerprints four moments across the song; if none is known, tries the speeds edits use. */
    private suspend fun recognise(pcm: FloatArray, onStatus: (String) -> Unit): List<Moment> {
        val rate = ClipAudio.RATE
        val len = minOf(pcm.size, SEG * rate)
        val starts = listOf(0.2, 0.4, 0.6, 0.8).map { ((pcm.size - len) * it).toInt() }.distinct()
        val first = ask(pcm, starts.map { it to 1.0 })
        if (first.isNotEmpty()) return first
        for (batch in Identifier.SPEEDS.chunked(3)) {
            onStatus("Trying sped-up & slowed versions… ${batch.joinToString(", ") { "%.2f×".format(it) }}")
            val found = ask(pcm, batch.map { starts[starts.size / 2] to it })
            if (found.isNotEmpty()) {
                val sp = found.first().speed
                return found + ask(pcm, starts.filter { it != starts[starts.size / 2] }.map { it to sp })
            }
        }
        return emptyList()
    }

    private suspend fun ask(pcm: FloatArray, what: List<Pair<Int, Double>>): List<Moment> = coroutineScope {
        val gate = Semaphore(3)
        what.map { (start, speed) ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val seg = pcm.copyOfRange(start, minOf(pcm.size, start + SEG * ClipAudio.RATE))
                    val audio = if (speed == 1.0) seg else Dsp.resample(seg, speed)
                    val clipped = if (audio.size > SEG * ClipAudio.RATE) audio.copyOf(SEG * ClipAudio.RATE) else audio
                    runCatching { Shazam.recognize(ShazamSignature.from(ClipLoader.normalize(clipped))) }.getOrNull()
                        ?.let { Moment(start / ClipAudio.RATE.toDouble(), it.offsetSec, it, speed) }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun lyricsFor(key: String, title: String, artist: String, album: String?): LyricsDoc? =
        // No length: the record's length isn't this file's, so the name and artist decide.
        Lyrics.lookup(SongQuery(key, title, artist, album, 0L))

    /** Shifts and stretches the record's synced lyrics onto this file. */
    private fun lineUp(doc: LyricsDoc, moments: List<Moment>, fileSec: Double, hit: ShazamHit): LyricsDoc {
        val asWhat = "${hit.artist} — ${hit.title}"
        val t = timing(moments)
        val synced = doc.synced
        if (synced == null || t == null) {
            return doc.copy(matchedAs = asWhat, note = "AI recognised the song from its sound")
        }
        val mapped = mapLines(synced, t, fileSec)
        val speedNote = if (abs(t.rate - 1) > 0.02) " · this version plays at %.2f×".format(t.rate) else ""
        return doc.copy(
            synced = mapped.takeIf { it.size >= 2 },
            plain = doc.plain ?: synced.joinToString("\n") { it.text },
            matchedAs = asWhat,
            note = "AI recognised the song and lined the lyrics up$speedNote",
        )
    }

    /**
     * The file→record timing from the recognised moments: a straight line through them when
     * there are several, else the one moment and the speed it was found at.
     */
    internal fun timing(moments: List<Moment>): Timing? {
        val pts = moments.mapNotNull { m -> m.songSec?.let { m.fileSec to it } }.distinctBy { it.first }
        if (pts.isEmpty()) return null
        val fallback = moments.first { it.songSec != null }.speed
        if (pts.size >= 2) {
            val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
            val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
            if (sxx > 1e-6) {
                val slope = pts.sumOf { (it.first - mx) * (it.second - my) } / sxx
                // Several moments that don't sit on one line: an edit with cuts. Trust the speed.
                if (slope in 0.6..1.7) {
                    val worst = pts.maxOf { abs(my + slope * (it.first - mx) - it.second) }
                    if (worst < 1.5) return Timing(slope, my - slope * mx)
                }
            }
        }
        // Median moment, at the speed it was recognised at.
        val (fx, sy) = pts.sortedBy { it.second - fallback * it.first }[pts.size / 2]
        return Timing(fallback, sy - fallback * fx)
    }

    /** Record line times → this file's times; lines cut from this version are dropped. */
    internal fun mapLines(lines: List<LyricLine>, t: Timing, fileSec: Double): List<LyricLine> =
        lines.mapNotNull { l ->
            val sec = (l.timeMs / 1000.0 - t.intercept) / t.rate
            when {
                sec < -1.0 || sec > fileSec + 0.5 -> null
                else -> LyricLine((sec.coerceAtLeast(0.0) * 1000).toLong(), l.text)
            }
        }

    private fun loudest(pcm: FloatArray, seconds: Int): FloatArray {
        val len = minOf(pcm.size, seconds * ClipAudio.RATE)
        var best = -1.0; var start = 0; var s = 0
        while (s + len <= pcm.size) {
            val sc = Dsp.rms(pcm, s, s + len)
            if (sc > best) { best = sc; start = s }
            s += 3 * ClipAudio.RATE
        }
        return pcm.copyOfRange(start, start + len)
    }

    private suspend fun searchSongs(q: String): List<YtSong> {
        val r = InnerTube.search(q)
        val songsChip = r.chips.firstOrNull { it.text.equals("Songs", true) }?.params
        val songs = songsChip?.let { p -> runCatching { InnerTube.search(q, p).sections.flatMap { it.items }.filterIsInstance<YtSong>() }.getOrNull() }.orEmpty()
        val first = r.sections.flatMap { it.items }.filterIsInstance<YtSong>()
        return (songs + first).filter { !it.isVideo }.ifEmpty { songs + first }
    }
}
