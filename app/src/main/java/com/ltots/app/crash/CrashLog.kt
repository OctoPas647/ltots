package com.ltots.app.crash

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every crash report LToTS has written, kept as plain text files on the phone
 * (app storage, "crash-reports") and copied to Download/LToTS/Crash reports, where any file
 * manager can open them. Profile > Crash reports lists them.
 *
 * Plain java.io only: this runs inside a crashing process too.
 */
object CrashLog {
    /** What kind of problem a report is about. */
    enum class Kind(val label: String, val title: String, val opensScreen: Boolean) {
        CRASH("Crash", "LToTS crashed", true),
        NATIVE("Native crash", "LToTS crashed (native code)", true),
        FREEZE("Freeze → closed", "LToTS froze and Android closed it", true),
        FREEZE_RECOVERED("Freeze", "LToTS froze for a moment", false),
        KILLED("Stopped by Android", "Android stopped LToTS", false),
        /** Not a freeze: the screen was free, but Android held back LToTS's ordinary work (see Watchdog). */
        STALL("Held back", "Android held back LToTS's work", false),
        ;

        companion object {
            fun of(name: String): Kind = entries.firstOrNull { it.name == name } ?: CRASH
        }
    }

    /** One saved report. */
    data class Entry(val file: File, val kind: Kind, val at: Long, val summary: String)

    private const val DIR = "crash-reports"
    private const val KEEP = 40
    const val EXPORT_FOLDER = "LToTS/Crash reports"

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun prefs(context: Context) = context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)

    /**
     * Saves a report and returns its file. The first line of [text] is its summary. Safe to call
     * from the uncaught-exception handler.
     */
    @Synchronized
    fun save(context: Context, kind: Kind, text: String, at: Long = System.currentTimeMillis()): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date(at))
        var f = File(dir(context), "${stamp}_${kind.name}.txt")
        var i = 2
        while (f.exists()) f = File(dir(context), "${stamp}_${kind.name}_${i++}.txt")
        f.writeText(text)
        // Not seen yet: a crash opens its report the next time LToTS opens.
        if (kind.opensScreen) runCatching { prefs(context).edit().putString("unseen", f.name).commit() }
        trim(context)
        return f
    }

    /** Newest first. */
    fun list(context: Context): List<Entry> =
        (dir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: emptyArray())
            .mapNotNull { entry(it) }
            .sortedByDescending { it.at }

    fun entry(f: File): Entry? = runCatching {
        val base = f.name.removeSuffix(".txt")
        val parts = base.split('_', limit = 3)
        val at = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).parse("${parts[0]}_${parts[1]}")?.time ?: f.lastModified()
        // "FREEZE_RECOVERED", maybe with "_2" after it: the longest kind name that fits.
        val rest = parts.getOrNull(2) ?: ""
        val kind = Kind.entries.sortedByDescending { it.name.length }
            .firstOrNull { rest == it.name || rest.startsWith(it.name + "_") } ?: Kind.CRASH
        // Line 1 is "LToTS 0.1.x crashed"; the summary is the line marked "What:" (or the first one).
        val head = f.useLines { lines -> lines.take(40).toList() }
        val summary = head.firstOrNull { it.startsWith("What: ") }?.removePrefix("What: ")
            ?: head.firstOrNull { it.isNotBlank() } ?: ""
        Entry(f, kind, at, summary)
    }.getOrNull()

    fun delete(context: Context, f: File) {
        f.delete()
        if (prefs(context).getString("unseen", null) == f.name) prefs(context).edit().remove("unseen").apply()
    }

    fun deleteAll(context: Context) {
        dir(context).listFiles()?.forEach { if (it.isFile && it.name.endsWith(".txt")) it.delete() }
        prefs(context).edit().remove("unseen").apply()
    }

    /** The crash report not looked at yet, if any (and clears it: it's shown once). */
    fun takeUnseen(context: Context): File? {
        val name = prefs(context).getString("unseen", null) ?: return null
        prefs(context).edit().remove("unseen").apply()
        return File(dir(context), name).takeIf { it.exists() }
    }

    fun markSeen(context: Context, f: File) {
        if (prefs(context).getString("unseen", null) == f.name) prefs(context).edit().remove("unseen").apply()
    }

    private fun trim(context: Context) {
        val all = (dir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") } ?: return).sortedByDescending { it.name }
        all.drop(KEEP).forEach { it.delete() }
    }

    // ---- Download/LToTS/Crash reports ------------------------------------------------------------

    /**
     * Copies every report not copied yet to Download/LToTS/Crash reports (MediaStore: no
     * permission needed for the app's own files). Returns how many were copied.
     */
    @Synchronized
    fun exportPending(context: Context): Int {
        val p = prefs(context)
        val done = p.getStringSet("exported", emptySet())!!.toMutableSet()
        var n = 0
        for (e in list(context)) {
            if (e.file.name in done) continue
            if (exportOne(context, e.file)) { done += e.file.name; n++ }
        }
        // Forget names of reports that no longer exist here.
        val here = dir(context).list()?.toSet().orEmpty()
        done.retainAll(here)
        p.edit().putStringSet("exported", done).apply()
        return n
    }

    private fun exportOne(context: Context, f: File): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "LToTS_" + f.name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_FOLDER)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
}
