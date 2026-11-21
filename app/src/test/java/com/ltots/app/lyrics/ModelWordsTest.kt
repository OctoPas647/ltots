package com.ltots.app.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ModelWordsTest {
    private class Le {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = apply { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
        fun long(v: Long) = apply { out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()) }
        fun str(s: String) = apply { val b = s.toByteArray(); int(b.size); out.write(b) }
        fun symbols(vararg s: String) = apply {
            int(2125658996); str("words"); long(s.size.toLong()); long(s.size.toLong())
            s.forEachIndexed { i, w -> str(w); long(i.toLong()) }
        }
    }

    /** An OpenFst header as Vosk's Gr.fst has it, with the given symbol tables. */
    private fun fst(flags: Int, build: Le.() -> Unit): ByteArray = Le().apply {
        int(2125659606); str("const"); str("standard"); int(2); int(flags)
        long(0); long(0); long(3); long(5)
        build()
        out.write(ByteArray(64)) // the graph itself (not read)
    }.out.toByteArray()

    @Test fun readsOutputSymbols() {
        val words = ModelWords.fstSymbols(ByteArrayInputStream(fst(0x3) {
            symbols("<eps>", "a", "b")
            symbols("<eps>", "Hello", "world", "#0", "don't")
        }))!!
        assertEquals(setOf("hello", "world", "don't"), words)
    }

    @Test fun inputSymbolsWhenNoOutput() {
        val words = ModelWords.fstSymbols(ByteArrayInputStream(fst(0x1) { symbols("<eps>", "love") }))
        assertEquals(setOf("love"), words)
    }

    @Test fun noSymbolsOrNotAnFst() {
        assertNull(ModelWords.fstSymbols(ByteArrayInputStream(fst(0x0) {})))
        assertNull(ModelWords.fstSymbols(ByteArrayInputStream(ByteArray(40))))
    }

    @Test fun grammarOnlyHoldsKnownWords() {
        val known = setOf("i", "love", "you", "baby", "tonight", "dance", "with", "me")
        val en = LyricAlign.words("I love you baby\nDance with me tonight\nI love you")
        assertEquals(listOf("i", "love", "you", "baby", "dance", "with", "me", "tonight"), ModelWords.knownOnly(en, known))
        // Mostly unknown words (another language): nothing to listen for, Vosk isn't started.
        val bg = LyricAlign.words("Обичам те бейби\nТанцувай с мен тази нощ\nI love you")
        assertTrue(ModelWords.knownOnly(bg, known).isEmpty())
        assertTrue(ModelWords.knownOnly(emptyList(), known).isEmpty())
    }
}
