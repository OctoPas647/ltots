package com.ltots.app.lyrics

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream

/**
 * The words the speech model knows. The small English model has no words.txt: its words are the
 * symbol table stored inside graph/Gr.fst (OpenFst binary), read here once and cached as text.
 *
 * Telling Vosk to listen for words it doesn't know leaves it a broken grammar (all words dropped,
 * or almost all): its look-ahead search then reads outside its memory and the whole app dies with
 * a native crash (SIGSEGV in LabelReachable::LowerBound), which Kotlin can't catch. So the
 * grammar only ever holds words from this list.
 */
internal object ModelWords {
    private const val FST_MAGIC = 2125659606
    private const val SYMBOLS_MAGIC = 2125658996
    private const val HAS_ISYMBOLS = 0x1
    private const val HAS_OSYMBOLS = 0x2

    /** The model's words (lower case), or null when they can't be read. */
    fun load(modelDir: File, cache: File): Set<String>? {
        if (cache.isFile) runCatching { cache.readLines().filter { it.isNotBlank() }.toHashSet() }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        val words = File(modelDir, "graph/words.txt").takeIf { it.isFile }?.let { f ->
            runCatching { f.useLines { s -> s.mapNotNull { it.substringBefore(' ').trim().takeIf(String::isNotEmpty) }.toHashSet() } }.getOrNull()
        } ?: File(modelDir, "graph/Gr.fst").takeIf { it.isFile }?.let { f ->
            runCatching { f.inputStream().use { fstSymbols(it) } }.getOrNull()
        } ?: return null
        if (words.isEmpty()) return null
        runCatching { cache.parentFile?.mkdirs(); cache.writeText(words.joinToString("\n")) }
        return words
    }

    /**
     * Of the lyrics' [lyricWords], the distinct ones in [known]; empty when under half the sung
     * words are known (another language: the English model would only hear noise).
     */
    fun knownOnly(lyricWords: List<String>, known: Set<String>): List<String> {
        if (lyricWords.isEmpty()) return emptyList()
        val hits = lyricWords.count { it in known }
        if (hits < 5 || hits * 2 < lyricWords.size) return emptyList()
        return lyricWords.filter { it in known }.distinct()
    }

    /**
     * The word symbols stored in an OpenFst binary file (output symbols, else input symbols),
     * or null when it has none. Reads only the header, not the graph.
     */
    fun fstSymbols(input: InputStream): Set<String>? {
        val d = LeInput(input)
        if (d.int() != FST_MAGIC) return null
        d.string(); d.string() // fst type, arc type
        d.int() // version
        val flags = d.int()
        d.long(); d.long(); d.long(); d.long() // properties, start, states, arcs
        val isyms = if (flags and HAS_ISYMBOLS != 0) symbolTable(d) else null
        val osyms = if (flags and HAS_OSYMBOLS != 0) symbolTable(d) else null
        return (osyms ?: isyms)?.takeIf { it.isNotEmpty() }
    }

    private fun symbolTable(d: LeInput): Set<String> {
        require(d.int() == SYMBOLS_MAGIC) { "not a symbol table" }
        d.string() // name
        d.long() // available key
        val size = d.long()
        require(size in 0..5_000_000) { "symbol table size $size" }
        val out = HashSet<String>(size.toInt() * 2)
        repeat(size.toInt()) {
            val s = d.string(); d.long()
            if (!s.startsWith("<") && !s.startsWith("#")) out += s.lowercase() // <eps>, #0 …
        }
        return out
    }

    /** Little-endian reads, as OpenFst writes them. */
    private class LeInput(input: InputStream) {
        private val s = DataInputStream(if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024))
        fun int(): Int = Integer.reverseBytes(s.readInt())
        fun long(): Long = java.lang.Long.reverseBytes(s.readLong())
        fun string(): String {
            val n = int()
            if (n < 0 || n > 1 shl 20) throw EOFException("string length $n")
            val b = ByteArray(n); s.readFully(b)
            return String(b, Charsets.UTF_8)
        }
    }
}
