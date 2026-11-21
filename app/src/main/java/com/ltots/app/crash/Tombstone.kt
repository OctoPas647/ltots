package com.ltots.app.crash

/**
 * Reads the parts of an Android tombstone (a native crash record, protobuf, as
 * ApplicationExitInfo hands it over) that say what happened: the signal, the abort message and
 * the crashing thread's backtrace (library + function for each frame).
 *
 * A tiny protobuf reader, no library: field numbers from AOSP system/core/debuggerd/proto/tombstone.proto.
 */
object Tombstone {
    private class Reader(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        fun more() = pos < end
        fun varint(): Long {
            var r = 0L
            var shift = 0
            while (pos < end) {
                val x = b[pos++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
                if (shift > 63) break
            }
            return r
        }
        /** (field, wire type), or null at the end. */
        fun tag(): Pair<Int, Int>? = if (!more()) null else varint().let { (it ushr 3).toInt() to (it and 7).toInt() }
        fun bytes(): Reader { val n = varint().toInt(); val r = Reader(b, pos, minOf(end, pos + n)); pos = minOf(end, pos + n); return r }
        fun string(): String { val r = bytes(); return String(b, r.pos, r.end - r.pos, Charsets.UTF_8) }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> { val n = varint().toInt(); pos += n }
                5 -> pos += 4
                else -> pos = end
            }
        }
    }

    private class Frame(var pc: Long = 0, var fn: String = "", var off: Long = 0, var file: String = "")
    private class Thread(var id: Int = 0, var name: String = "", val frames: MutableList<Frame> = ArrayList())

    /** A readable summary, or null if [data] isn't a tombstone. */
    fun describe(data: ByteArray): String? = runCatching {
        val r = Reader(data)
        var tid = 0
        var signal = ""
        var fault = ""
        var abort = ""
        val causes = ArrayList<String>()
        val threads = ArrayList<Thread>()
        val cmd = ArrayList<String>()
        while (true) {
            val (field, wire) = r.tag() ?: break
            when {
                field == 6 && wire == 0 -> tid = r.varint().toInt()
                field == 9 && wire == 2 -> cmd += r.string()
                field == 10 && wire == 2 -> {
                    val s = r.bytes()
                    var name = ""; var codeName = ""; var hasFault = false; var addr = 0L
                    while (true) {
                        val (f, w) = s.tag() ?: break
                        when {
                            f == 2 && w == 2 -> name = s.string()
                            f == 4 && w == 2 -> codeName = s.string()
                            f == 8 && w == 0 -> hasFault = s.varint() != 0L
                            f == 9 && w == 0 -> addr = s.varint()
                            else -> s.skip(w)
                        }
                    }
                    signal = listOf(name, codeName).filter { it.isNotBlank() }.joinToString(" / ")
                    if (hasFault) fault = "0x" + java.lang.Long.toHexString(addr)
                }
                field == 14 && wire == 2 -> abort = r.string()
                field == 15 && wire == 2 -> {
                    val c = r.bytes()
                    while (true) {
                        val (f, w) = c.tag() ?: break
                        if (f == 1 && w == 2) causes += c.string() else c.skip(w)
                    }
                }
                field == 16 && wire == 2 -> {
                    // map<uint32, Thread>: an entry with key 1, value 2.
                    val e = r.bytes()
                    while (true) {
                        val (f, w) = e.tag() ?: break
                        if (f == 2 && w == 2) threads += thread(e.bytes()) else e.skip(w)
                    }
                }
                else -> r.skip(wire)
            }
        }
        if (signal.isBlank() && threads.isEmpty()) return null
        buildString {
            appendLine("Signal: ${signal.ifBlank { "?" }}${if (fault.isNotBlank()) " at $fault" else ""}")
            if (abort.isNotBlank()) appendLine("Abort message: $abort")
            causes.forEach { appendLine("Cause: $it") }
            if (cmd.isNotEmpty()) appendLine("Process: ${cmd.joinToString(" ")}")
            val crashing = threads.firstOrNull { it.id == tid } ?: threads.firstOrNull()
            if (crashing != null) {
                appendLine()
                appendLine("Crashing thread: \"${crashing.name}\" (tid ${crashing.id})")
                crashing.frames.take(48).forEachIndexed { i, fr -> appendLine(frameLine(i, fr)) }
            }
            // Other LToTS threads that were in native code, briefly: often the other side of a race.
            val others = threads.filter { it !== crashing && it.frames.any { f -> "ltots" in f.file || "rubberband" in f.file } }
            for (t in others.take(6)) {
                appendLine()
                appendLine("Thread \"${t.name}\" (tid ${t.id})")
                t.frames.take(12).forEachIndexed { i, fr -> appendLine(frameLine(i, fr)) }
            }
        }
    }.getOrNull()

    private fun frameLine(i: Int, f: Frame): String {
        val lib = f.file.substringAfterLast('/').ifBlank { "?" }
        val fn = if (f.fn.isNotBlank()) " ${f.fn}+${f.off}" else ""
        return "  #%02d pc %08x  %s%s".format(i, f.pc, lib, fn)
    }

    private fun thread(r: Reader): Thread {
        val t = Thread()
        while (true) {
            val (f, w) = r.tag() ?: break
            when {
                f == 1 && w == 0 -> t.id = r.varint().toInt()
                f == 2 && w == 2 -> t.name = r.string()
                f == 4 && w == 2 -> {
                    val b = r.bytes()
                    val fr = Frame()
                    while (true) {
                        val (ff, ww) = b.tag() ?: break
                        when {
                            ff == 1 && ww == 0 -> fr.pc = b.varint() // rel_pc
                            ff == 4 && ww == 2 -> fr.fn = b.string()
                            ff == 5 && ww == 0 -> fr.off = b.varint()
                            ff == 6 && ww == 2 -> fr.file = b.string()
                            else -> b.skip(ww)
                        }
                    }
                    t.frames += fr
                }
                else -> r.skip(w)
            }
        }
        return t
    }
}
