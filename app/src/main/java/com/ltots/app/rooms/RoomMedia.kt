package com.ltots.app.rooms

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.ltots.app.CrashReport
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Lends this phone's songs to the phone that took over as main (Make this main): the queue's
 * files stay where they are and are read over the Wi-Fi as they play, with seeking.
 *
 * A tiny HTTP-style server (one request per connection, byte ranges), only for the songs in
 * the handed-over queue, each behind a random token. The other side reads it with
 * [RoomDataSource] — LToTS's own client, so Android's ban on plain-HTTP traffic stays on for
 * everything else in the app.
 */
object RoomMediaServer {
    private val shared = ConcurrentHashMap<String, Uri>()
    private var server: ServerSocket? = null
    private lateinit var app: Context

    val port: Int get() = server?.localPort ?: 0

    @Synchronized fun start(context: Context): Int {
        app = context.applicationContext
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0)
        server = s
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                Thread({ serve(c) }, "LToTS-room-media").apply { isDaemon = true; start() }
            }
        }, "LToTS-room-media-accept").apply { isDaemon = true; start() }
        return s.localPort
    }

    /** Makes [uri] readable by the room; returns the path to ask for. */
    fun share(uri: Uri, ext: String): String {
        val token = RoomCrypto.b64(RoomCrypto.nonce()).replace(Regex("[^A-Za-z0-9]"), "").take(20)
        shared[token] = uri
        return "/m/$token${if (ext.isNotBlank()) ".$ext" else ""}"
    }

    @Synchronized fun stop() {
        runCatching { server?.close() }
        server = null
        shared.clear()
    }

    private fun serve(c: Socket) {
        c.use { sock ->
            try {
                sock.soTimeout = 20_000
                val input = BufferedInputStream(sock.getInputStream())
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val path = lines.first().split(" ").getOrNull(1) ?: return
                val token = path.removePrefix("/m/").substringBefore('.')
                val uri = shared[token]
                val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
                if (uri == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush(); return
                }
                val range = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }
                    ?.substringAfter("bytes=")?.trim()
                val from = range?.substringBefore('-')?.toLongOrNull() ?: 0L
                app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val total = afd.length.takeIf { it >= 0 } ?: afd.parcelFileDescriptor.statSize
                    val stream = afd.createInputStream()
                    var skipped = 0L
                    while (skipped < from) {
                        val s = stream.skip(from - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    val len = (total - from).coerceAtLeast(0)
                    val status = if (range != null) "206 Partial Content" else "200 OK"
                    out.write(
                        ("HTTP/1.1 $status\r\nContent-Length: $len\r\n" +
                            (if (range != null) "Content-Range: bytes $from-${total - 1}/$total\r\n" else "") +
                            "Accept-Ranges: bytes\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    val buf = ByteArray(64 * 1024)
                    var left = len
                    while (left > 0) {
                        val n = stream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                    out.flush()
                }
            } catch (_: IOException) {
            } catch (e: Throwable) {
                CrashReport.handled("RoomMediaServer.serve", e)
            }
        }
    }

    internal fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 8192) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.substring(0, sb.length - 4)
        }
        return null
    }
}

/**
 * Reads a song lent by another phone ("ltotsroom://host:port/m/token.ext"), with seeking.
 */
@UnstableApi
class RoomDataSource : BaseDataSource(true) {
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var uri: Uri? = null
    private var left = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val host = dataSpec.uri.host ?: throw IOException("No host")
        val port = dataSpec.uri.port
        val s = Socket()
        s.connect(java.net.InetSocketAddress(host, port), 5_000)
        s.soTimeout = 15_000
        socket = s
        val out = s.getOutputStream()
        out.write("GET ${dataSpec.uri.path} HTTP/1.1\r\nHost: $host\r\nRange: bytes=${dataSpec.position}-\r\n\r\n".toByteArray())
        out.flush()
        val inp = BufferedInputStream(s.getInputStream(), 64 * 1024)
        val head = RoomMediaServer.readHead(inp) ?: throw IOException("No answer")
        val status = head.substringBefore("\r\n")
        if (!status.contains(" 200") && !status.contains(" 206")) throw IOException("Song not available: $status")
        val length = head.split("\r\n").firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
        input = inp
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong() && length != C.LENGTH_UNSET.toLong()) minOf(length, dataSpec.length)
        else if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else length
        transferStarted(dataSpec)
        return left
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val want = if (left > 0) minOf(left, length.toLong()).toInt() else length
        val n = input?.read(buffer, offset, want) ?: return C.RESULT_END_OF_INPUT
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (left > 0) left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val had = socket != null
        runCatching { input?.close() }
        runCatching { socket?.close() }
        input = null
        socket = null
        uri = null
        if (had) transferEnded()
    }

    /** Room songs through [RoomDataSource], everything else as usual. */
    class Factory(private val fallback: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = Switching(fallback.createDataSource())
    }

    private class Switching(private val other: DataSource) : DataSource {
        private var current: DataSource? = null
        private val room = RoomDataSource()
        override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
            other.addTransferListener(transferListener)
            room.addTransferListener(transferListener)
        }
        override fun open(dataSpec: DataSpec): Long {
            val d = if (dataSpec.uri.scheme == SCHEME) room else other
            current = d
            return d.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = current?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()
        override fun close() {
            current?.close()
            current = null
        }
    }

    companion object {
        const val SCHEME = "ltotsroom"
    }
}
