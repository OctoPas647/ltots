package com.ltots.app.rooms

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The cryptography of joining a room (pure Java crypto, tested on the desktop).
 *
 * Each phone has its own key pair, made once. Two phones that meet agree on a shared secret
 * (ECDH, P-256). The first time, both show a 4-digit code made from that secret: if the codes
 * match, nobody sits in between, and each phone remembers the other's key. From then on they
 * prove they hold the remembered keys (an HMAC over fresh random numbers), with no code.
 */
object RoomCrypto {
    private val random = SecureRandom()

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"), random)
    }.generateKeyPair()

    fun nonce(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun randomLong(): Long = random.nextLong()

    fun publicFrom(encoded: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
    fun privateFrom(encoded: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

    /** A phone's id: a short fingerprint of its public key. */
    fun idOf(pub: ByteArray): String = sha256(pub).take(8).joinToString("") { "%02x".format(it) }

    fun shared(own: PrivateKey, peer: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(own)
        ka.doPhase(peer, true)
        return sha256("ltots-room-v1".toByteArray() + ka.generateSecret())
    }

    /** Both nonces in a fixed order, so both phones get the same bytes. */
    private fun both(a: ByteArray, b: ByteArray): ByteArray =
        if (compare(a, b) <= 0) a + b else b + a

    /** The 4-digit code both phones show the first time they meet. */
    fun pairingCode(shared: ByteArray, nonceA: ByteArray, nonceB: ByteArray): String {
        val h = sha256("pair".toByteArray() + shared + both(nonceA, nonceB))
        val v = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return "%04d".format(v % 10000)
    }

    /** Proof that [id] holds the key behind [shared], for this meeting only. */
    fun authMac(shared: ByteArray, id: String, nonceA: ByteArray, nonceB: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(shared, "HmacSHA256"))
        return mac.doFinal("auth|$id|".toByteArray() + both(nonceA, nonceB))
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}

/** A phone this one knows and trusts. */
data class Peer(val id: String, val name: String, val pub: ByteArray)

/**
 * This phone's identity and the phones it trusts, kept in the app's private files.
 */
class RoomIdentity internal constructor(private val dir: File, val name: String) {
    val keys: KeyPair
    val id: String
    private val trusted = LinkedHashMap<String, Peer>()

    init {
        dir.mkdirs()
        val f = File(dir, "room_key.json")
        val loaded = runCatching {
            val o = JSONObject(f.readText())
            KeyPair(RoomCrypto.publicFrom(RoomCrypto.unb64(o.getString("pub"))), RoomCrypto.privateFrom(RoomCrypto.unb64(o.getString("priv"))))
        }.getOrNull()
        keys = loaded ?: RoomCrypto.newKeyPair().also { kp ->
            f.writeText(JSONObject().put("pub", RoomCrypto.b64(kp.public.encoded)).put("priv", RoomCrypto.b64(kp.private.encoded)).toString())
        }
        id = RoomCrypto.idOf(keys.public.encoded)
        runCatching {
            val o = JSONObject(File(dir, "room_trusted.json").readText())
            for (k in o.keys()) {
                val p = o.getJSONObject(k)
                trusted[k] = Peer(k, p.optString("name", "Phone"), RoomCrypto.unb64(p.getString("pub")))
            }
        }
    }

    val publicKey: ByteArray get() = keys.public.encoded

    @Synchronized fun isTrusted(id: String, pub: ByteArray): Boolean = trusted[id]?.pub?.contentEquals(pub) == true

    @Synchronized fun trustedPeers(): List<Peer> = trusted.values.toList()

    @Synchronized fun trust(p: Peer) {
        if (p.id == id) return
        if (RoomCrypto.idOf(p.pub) != p.id) return
        val old = trusted[p.id]
        if (old != null && old.pub.contentEquals(p.pub) && old.name == p.name) return
        trusted[p.id] = p
        save()
    }

    @Synchronized fun forget(id: String) {
        if (trusted.remove(id) != null) save()
    }

    private fun save() {
        val o = JSONObject()
        for (p in trusted.values) o.put(p.id, JSONObject().put("name", p.name).put("pub", RoomCrypto.b64(p.pub)))
        runCatching { File(dir, "room_trusted.json").writeText(o.toString()) }
    }

    companion object {
        @Volatile private var instance: RoomIdentity? = null

        fun get(context: Context): RoomIdentity = instance ?: synchronized(this) {
            instance ?: RoomIdentity(File(context.filesDir, "rooms"), deviceName(context)).also { instance = it }
        }

        /** The phone's name as set in Settings (e.g. "Xiaomi 17 Ultra"), or its model. */
        fun deviceName(context: Context): String {
            val set = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
                ?: runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_name") }.getOrNull()
            val model = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }
                .let { parts -> if (parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true)) parts[1] else parts.joinToString(" ") }
            return set?.takeIf { it.isNotBlank() } ?: model.replaceFirstChar { it.uppercase() }.ifBlank { "Phone" }
        }
    }
}
