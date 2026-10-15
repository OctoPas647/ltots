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

/** Tiny cached image loader for covers in search results (no extra library needed). */
object NetImages {
    private val cache = object : LruCache<String, Bitmap>(40 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<String>()
    private val gate = Semaphore(6)

    /** Asks Google's image servers for roughly the size we draw, instead of the original. */
    private fun sized(url: String, px: Int): String =
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) "${url.substringBefore('=')}=w$px-h$px-l90-rj" else url

    suspend fun load(url: String, px: Int): Bitmap? {
        val u = sized(url, px)
        cache.get(u)?.let { return it }
        if (synchronized(failed) { u in failed }) return null
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                cache.get(u) ?: runCatching {
                    val c = (URL(u).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10_000; readTimeout = 15_000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }
                    val bytes = c.inputStream.use { it.readBytes() }
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var sample = 1
                    while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull().also { b ->
                    if (b != null) cache.put(u, b) else synchronized(failed) { failed += u }
                }
            }
        }
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
    val bmp by produceState<Bitmap?>(null, url) { value = url?.let { NetImages.load(it, px) } }
    Box(modifier.size(size).clip(shape).background(pal.text.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(remember(b) { b.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(placeholder, null, tint = pal.textFaint, modifier = Modifier.size(size / 2))
    }
}
