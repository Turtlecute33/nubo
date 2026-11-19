package app.nubo.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Small byte helpers. One [SecureRandom] for the app. */
object Bytes {
    private val random = SecureRandom()
    private const val ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    fun random(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    /** [n] random characters from a-z, A-Z and 0-9: the Filen form of v2 keys, nonces and upload keys. */
    fun alnum(n: Int): String = buildString(n) { repeat(n) { append(ALNUM[random.nextInt(ALNUM.length)]) } }

    fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    fun b64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    fun unb64(text: String): ByteArray = Base64.getDecoder().decode(text)
}

private val HEX = "0123456789abcdef".toCharArray()

fun ByteArray.hex(): String {
    val out = CharArray(size * 2)
    forEachIndexed { i, b ->
        val v = b.toInt() and 0xff
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(out)
}

/** Throws [IllegalArgumentException] for text that is not even-length hex. */
fun String.unhex(): ByteArray {
    require(length % 2 == 0) { "Bad hex" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "Bad hex" }
        ((hi shl 4) or lo).toByte()
    }
}

fun String.isHex(): Boolean = isNotEmpty() && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
