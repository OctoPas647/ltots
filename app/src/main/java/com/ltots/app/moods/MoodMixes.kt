package com.ltots.app.moods

import com.ltots.app.data.Mix
import com.ltots.app.data.Track
import kotlin.math.sqrt
import kotlin.random.Random

/** A described direction in CLAP's space: a theme ("Sad") or an album name ("Night drive"). */
data class Prompt(val id: String, val label: String, val vec: FloatArray)

/** The words' side of CLAP, computed once off the phone and shipped with the app. */
data class ClapText(val themes: List<Prompt>, val albums: List<Prompt>)

/**
 * Themes and AI albums from what CLAP heard in each song.
 *
 * Raw CLAP scores lean towards a few words for everything ("happy" scores high on most music),
 * so each theme is judged *within the user's library*: a song is Sad when it's clearly sadder
 * than the user's other songs, and that's one of the two things that stand out most about it.
 */
object MoodMixes {
    /** A theme or AI album needs this many songs. */
    const val MIN_SONGS = 5
    private const val MAX_SONGS = 60
    private const val THEME_Z = 0.6f

    fun themes(tracks: List<Track>, emb: Map<String, FloatArray>, text: ClapText): List<Mix> {
        val songs = tracks.filter { it.profileKey in emb }.distinctBy { it.profileKey }
        if (songs.size < 2 * MIN_SONGS || text.themes.isEmpty()) return emptyList()
        val z = zScores(songs.map { emb.getValue(it.profileKey) }, text.themes.map { it.vec })
        val members = text.themes.indices.associateWith { ArrayList<Pair<Track, Float>>() }
        songs.forEachIndexed { i, t ->
            val top = text.themes.indices.sortedByDescending { z[i][it] }.take(2)
            for (k in top) if (z[i][k] >= THEME_Z) members.getValue(k) += t to z[i][k]
        }
        return text.themes.indices.mapNotNull { k ->
            val m = members.getValue(k)
            if (m.size < MIN_SONGS) null
            else Mix("theme:${text.themes[k].id}", Mix.Kind.THEME, text.themes[k].label,
                m.sortedByDescending { it.second }.take(MAX_SONGS).map { it.first })
        }
    }

    /**
     * Albums the AI puts together from the user's own songs: songs that sound alike are grouped
     * (k-means on CLAP's embeddings), each group is named by the description that fits it best
     * compared with the rest of the library, and its songs are ordered so each one flows into
     * the next most similar one.
     */
    fun albums(tracks: List<Track>, emb: Map<String, FloatArray>, text: ClapText): List<Mix> {
        val songs = tracks.filter { it.profileKey in emb }.distinctBy { it.profileKey }
        if (songs.size < 3 * MIN_SONGS || text.albums.isEmpty()) return emptyList()
        val vecs = songs.map { emb.getValue(it.profileKey) }
        val k = (songs.size / 18.0).toInt().coerceIn(2, 12)
        val (assign, centroids) = kMeans(vecs, k)
        val groups = (0 until k).map { c -> songs.indices.filter { assign[it] == c } }
            .withIndex().filter { it.value.size >= MIN_SONGS }
        if (groups.isEmpty()) return emptyList()

        // How well each name fits each group, compared with how it fits the library as a whole.
        val avg = text.albums.map { p -> vecs.map { Clap.dot(it, p.vec) }.average().toFloat() }
        val fit = groups.map { g -> text.albums.mapIndexed { a, p -> Clap.dot(centroids[g.index], p.vec) - avg[a] } }
        // Best names first, each name used once.
        val names = HashMap<Int, Int>()
        val pairs = groups.indices.flatMap { gi -> text.albums.indices.map { a -> Triple(gi, a, fit[gi][a]) } }.sortedByDescending { it.third }
        val usedNames = HashSet<Int>()
        for ((gi, a, _) in pairs) if (gi !in names && a !in usedNames) { names[gi] = a; usedNames += a }

        return groups.mapIndexed { gi, g ->
            val c = centroids[g.index]
            val members = g.value.sortedByDescending { Clap.dot(vecs[it], c) }.take(MAX_SONGS)
            val ordered = flow(members, vecs).map { songs[it] }
            val p = text.albums[names.getValue(gi)]
            Mix("ai:${p.id}", Mix.Kind.AI_ALBUM, p.label, ordered)
        }.sortedByDescending { it.tracks.size }
    }

    /** For each song and prompt: how far above the library's average the song scores, in SDs. */
    internal fun zScores(vecs: List<FloatArray>, prompts: List<FloatArray>): Array<FloatArray> {
        val raw = Array(vecs.size) { i -> FloatArray(prompts.size) { k -> Clap.dot(vecs[i], prompts[k]) } }
        for (k in prompts.indices) {
            val col = DoubleArray(vecs.size) { raw[it][k].toDouble() }
            val mean = col.average()
            val sd = sqrt(col.sumOf { (it - mean) * (it - mean) } / col.size).takeIf { it > 1e-6 } ?: 1.0
            for (i in vecs.indices) raw[i][k] = ((raw[i][k] - mean) / sd).toFloat()
        }
        return raw
    }

    /** Spherical k-means (cosine), k-means++ start with a fixed seed so albums stay put. */
    internal fun kMeans(vecs: List<FloatArray>, k: Int, iterations: Int = 30): Pair<IntArray, List<FloatArray>> {
        val rnd = Random(7)
        val dim = vecs.first().size
        val centroids = ArrayList<FloatArray>()
        centroids += vecs[rnd.nextInt(vecs.size)]
        while (centroids.size < k) {
            val d = vecs.map { v -> centroids.minOf { 1f - Clap.dot(v, it) }.coerceAtLeast(0f).let { it * it } }
            val total = d.sum()
            var r = rnd.nextFloat() * total
            var pick = vecs.lastIndex
            for (i in d.indices) { r -= d[i]; if (r <= 0f) { pick = i; break } }
            centroids += vecs[pick]
        }
        val assign = IntArray(vecs.size) { -1 }
        repeat(iterations) {
            var changed = false
            for (i in vecs.indices) {
                var best = 0; var bestS = -2f
                for (c in centroids.indices) { val s = Clap.dot(vecs[i], centroids[c]); if (s > bestS) { bestS = s; best = c } }
                if (assign[i] != best) { assign[i] = best; changed = true }
            }
            for (c in centroids.indices) {
                val sum = FloatArray(dim)
                var n = 0
                for (i in vecs.indices) if (assign[i] == c) { n++; val v = vecs[i]; for (j in 0 until dim) sum[j] += v[j] }
                if (n > 0) centroids[c] = Clap.normalize(sum)
            }
            if (!changed) return assign to centroids
        }
        return assign to centroids
    }

    /** A play order where each song is followed by the most similar one not played yet. */
    private fun flow(members: List<Int>, vecs: List<FloatArray>): List<Int> {
        if (members.size <= 2) return members
        val left = members.toMutableList()
        val out = ArrayList<Int>()
        var cur = left.removeAt(0)
        out += cur
        while (left.isNotEmpty()) {
            val next = left.maxBy { Clap.dot(vecs[cur], vecs[it]) }
            left.remove(next); out += next; cur = next
        }
        return out
    }
}
