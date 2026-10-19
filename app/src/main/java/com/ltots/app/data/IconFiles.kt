package com.ltots.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pictures picked for icon mode. Each is copied into the app's own storage (the photo picker's
 * permission doesn't last), so a Look profile can point at its icon by file name.
 */
object IconFiles {
    /** Longest side a picture is decoded at (bigger photos are read scaled down). */
    private const val DECODE_MAX = 2048

    private fun dir(context: Context) = File(context.filesDir, "ltots/icons").apply { mkdirs() }

    fun file(context: Context, name: String): File? =
        File(dir(context), name).takeIf { it.isFile && it.parentFile == dir(context) }

    /** Copies the picked picture in; returns its file name, or null if it can't be read. */
    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val ext = when (context.contentResolver.getType(uri)) {
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                else -> "img"
            }
            val name = "icon-${System.currentTimeMillis()}.$ext"
            val out = File(dir(context), name)
            context.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
            // Only keep it if it really is a picture.
            if (decode(out) == null) { out.delete(); null } else name
        }.getOrNull()
    }

    /** Reads a picture, scaled down while decoding if it is very large. */
    fun decode(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DECODE_MAX) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }.getOrNull()

    /** Deletes icon files no look uses any more ([inUse]: the file names still referenced). */
    suspend fun cleanUp(context: Context, inUse: Set<String>) = withContext(Dispatchers.IO) {
        dir(context).listFiles()?.forEach { f -> if (f.name !in inUse) f.delete() }
    }
}
