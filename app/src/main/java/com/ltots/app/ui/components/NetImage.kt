package com.ltots.app.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.ltots.app.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tiny cached image loader for covers (no extra library needed): memory first, then a disk copy
 * (so covers show at once after the app restarts, also offline), then the network. A cover that
 * failed to download is tried again after a minute, not never again for the whole run.
 */
object NetImages {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** url → when it last failed. */
    private val failed = HashMap<String, Long>()
    private val gate = Semaphore(8)
    private const val RETRY_MS = 60_000L
    private const val DISK_MAX = 120L * 1024 * 1024
    @Volatile private var diskDir: java.io.File? = null

    /** Called once at start-up: where the downloaded covers are kept. */
    fun init(context: android.content.Context) {
        diskDir = java.io.File(context.cacheDir, "covers").apply { mkdirs() }
    }

    /** Asks Google's image servers for roughly the size we draw, instead of the original. */
    private fun sized(url: String, px: Int): String =
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) "${url.substringBefore('=')}=w$px-h$px-l90-rj" else url

    /** The cover if it's already in memory (to draw it in the very first frame, no flash). */
    fun peek(url: String?, px: Int): Bitmap? = url?.let { cache.get(sized(it, px)) }

    private fun diskFile(u: String): java.io.File? = diskDir?.let { java.io.File(it, Integer.toHexString(u.hashCode()) + "_" + u.length + ".img") }

    private fun decode(bytes: ByteArray, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.also { it.prepareToDraw() }
    }

    suspend fun load(url: String, px: Int): Bitmap? {
        val u = sized(url, px)
        cache.get(u)?.let { return it }
        synchronized(failed) { failed[u]?.let { if (System.currentTimeMillis() - it < RETRY_MS) return null } }
        return withContext(Dispatchers.IO) {
            // From disk: no network, no waiting for a free download slot.
            diskFile(u)?.takeIf { it.isFile }?.let { f ->
                runCatching { decode(f.readBytes(), px) }.getOrNull()?.let { b -> cache.put(u, b); return@withContext b }
            }
            gate.withPermit {
                cache.get(u) ?: runCatching {
                    val c = (URL(u).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8_000; readTimeout = 12_000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    val bytes = c.inputStream.use { it.readBytes() }
                    decode(bytes, px)?.also { runCatching { diskFile(u)?.writeBytes(bytes); trimDisk() } }
                }.getOrNull().also { b ->
                    if (b != null) { cache.put(u, b); synchronized(failed) { failed.remove(u) } }
                    else synchronized(failed) { failed[u] = System.currentTimeMillis() }
                }
            }
        }
    }

    private var writes = 0
    /** Keeps the disk copies under [DISK_MAX], oldest first out (checked every 50 covers). */
    private fun trimDisk() {
        if (++writes % 50 != 0) return
        val files = diskDir?.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= DISK_MAX) break; total -= f.length(); f.delete() }
    }
}

@Composable
fun NetImage(
    url: String?,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier,
    placeholder: ImageVector = Icons.Rounded.MusicNote,
    px: Int = 256,
) {
    val pal = LocalPalette.current
    // Already in memory: drawn straight away, without the placeholder showing first.
    val bmp by produceState(NetImages.peek(url, px), url) {
        val now = NetImages.peek(url, px)
        value = now
        if (now == null) value = url?.let { NetImages.load(it, px) }
    }
    Box(modifier.size(size).clip(shape).background(pal.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(remember(b) { b.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(placeholder, null, tint = pal.textFaint, modifier = Modifier.size(size / 2))
    }
}
