package com.ltots.app.cast

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * A small web server on the phone that lets a Chromecast fetch the songs it's told to play
 * (a Cast device can only play from a web address). Only files LToTS put on the list are
 * served, each under a random address that stops working when casting ends, and only on the
 * local network. Supports byte ranges, so the speaker can seek.
 */
internal object LocalStream {
    private class Entry(val uri: Uri, val mime: String)

    private val files = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "LToTS-cast-http").apply { isDaemon = true } }
    @Volatile private var app: Context? = null

    /** Starts the server if needed; returns its port, or null when it can't run. */
    @Synchronized
    fun start(context: Context): Int? {
        app = context.applicationContext
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = runCatching { ServerSocket(0, 16) }.getOrNull() ?: return null
        server = s
        pool.execute {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { runCatching { serve(c) }; runCatching { c.close() } }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        files.clear()
    }

    /** Puts a song on the list; returns its address for the speaker, or null. */
    fun publish(context: Context, uri: Uri, mime: String?): String? {
        val port = start(context) ?: return null
        val host = localAddress() ?: return null
        val bytes = ByteArray(12).also { random.nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        val type = mime ?: runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "audio/mpeg"
        files[token] = Entry(uri, type)
        return "http://${host.hostAddress}:$port/s/$token"
    }

    /** The phone's address on the Wi-Fi (or Ethernet): a private IPv4 address. */
    fun localAddress(): InetAddress? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    }.getOrNull()

    private fun serve(c: Socket) {
        c.soTimeout = 20_000
        val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
        val request = reader.readLine() ?: return
        var range: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
        }
        val parts = request.split(' ')
        val method = parts.getOrNull(0) ?: ""
        val path = parts.getOrNull(1) ?: ""
        val out = c.getOutputStream()
        val e = path.removePrefix("/s/").takeIf { path.startsWith("/s/") }?.let { files[it] }
        val ctx = app
        if (e == null || ctx == null || (method != "GET" && method != "HEAD")) {
            head(out, "404 Not Found", "text/plain", 0, emptyMap()); return
        }
        val pfd = ctx.contentResolver.openFileDescriptor(e.uri, "r") ?: run { head(out, "404 Not Found", "text/plain", 0, emptyMap()); return }
        pfd.use { fd ->
            val size = fd.statSize
            var from = 0L
            var to = size - 1
            val partial = range?.startsWith("bytes=") == true && size > 0
            if (partial) {
                val spec = range!!.removePrefix("bytes=").substringBefore(',')
                val a = spec.substringBefore('-').toLongOrNull()
                val b = spec.substringAfter('-').toLongOrNull()
                when {
                    a != null -> { from = a; if (b != null) to = minOf(b, size - 1) }
                    b != null -> { from = (size - b).coerceAtLeast(0) } // the last b bytes
                }
                if (from >= size) { head(out, "416 Range Not Satisfiable", e.mime, 0, mapOf("Content-Range" to "bytes */$size")); return }
            }
            val len = to - from + 1
            val extra = mutableMapOf("Accept-Ranges" to "bytes")
            if (partial) extra["Content-Range"] = "bytes $from-$to/$size"
            head(out, if (partial) "206 Partial Content" else "200 OK", e.mime, len, extra)
            if (method == "HEAD") return
            java.io.FileInputStream(fd.fileDescriptor).use { input ->
                input.channel.position(from)
                val buf = ByteArray(64 * 1024)
                var left = len
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
                out.flush()
            }
        }
    }

    private fun head(out: OutputStream, status: String, mime: String, len: Long, extra: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status\r\n")
        sb.append("Content-Type: $mime\r\nContent-Length: $len\r\nConnection: close\r\n")
        // The Cast receiver is a web page on another origin.
        sb.append("Access-Control-Allow-Origin: *\r\n")
        extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }
}
