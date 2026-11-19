package app.filenza.crypto

import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts small text (names, metadata JSON, keys). Three formats (research F-META):
 * - `002` + 12-character nonce + base64(AES-256-GCM): key = PBKDF2-SHA512(k, k, 1 round, 32 bytes).
 * - `003` + 24 hex nonce + base64(AES-256-GCM): key = 32 raw bytes.
 * - `U2FsdGVk…` (OpenSSL "Salted__", AES-256-CBC, MD5 key derivation): read only, very old data.
 */
sealed interface MetaKey {
    fun encrypt(text: String): String

    /** Throws [GeneralSecurityException] for a wrong key, a damaged text or an unknown format. */
    fun decrypt(text: String): String
}

/** A v1/v2 master key (or the meta key of a file): the key text as bytes, and its derived AES key. */
class MasterKey(val raw: ByteArray) : MetaKey {
    private val aes = SecretKeySpec(Kdf.pbkdf2Sha512(raw, raw, 1, 32), "AES")

    override fun encrypt(text: String): String {
        val nonce = Bytes.alnum(12)
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, nonce.encodeToByteArray())) }
        return "002" + nonce + Bytes.b64(cipher.doFinal(text.encodeToByteArray()))
    }

    override fun decrypt(text: String): String = guard {
        when {
            text.startsWith("U2FsdGVk") -> MetaV1.decrypt(raw, text)
            text.startsWith("002") && text.length > 15 -> {
                val nonce = text.substring(3, 15).encodeToByteArray()
                val cipher = Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, nonce)) }
                cipher.doFinal(Bytes.unb64(text.substring(15))).decodeToString()
            }
            else -> throw GeneralSecurityException("Unknown format")
        }
    }

    /** Same key: the derived AES key is equal. */
    fun sameAs(other: MasterKey): Boolean = MessageDigest.isEqual(aes.encoded, other.aes.encoded)
}

/**
 * A 32-byte AES key: the v3 data key (DEK), the v3 key-encryption key, and every file key. Text uses `003`;
 * file chunks use nonce(12) + GCM output.
 */
class DataKey(val bytes: ByteArray) : MetaKey {
    init {
        require(bytes.size == 32) { "Bad key" }
    }

    private val aes = SecretKeySpec(bytes, "AES")

    override fun encrypt(text: String): String {
        val nonce = Bytes.random(12)
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, nonce)) }
        return "003" + nonce.hex() + Bytes.b64(cipher.doFinal(text.encodeToByteArray()))
    }

    override fun decrypt(text: String): String = guard {
        if (!text.startsWith("003") || text.length < 27) throw GeneralSecurityException("Unknown format")
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, text.substring(3, 27).unhex())) }
        cipher.doFinal(Bytes.unb64(text.substring(27))).decodeToString()
    }

    /** One file chunk: a fresh nonce, then the GCM output (ciphertext and tag). */
    fun encryptChunk(plain: ByteArray, length: Int = plain.size): ByteArray {
        val nonce = Bytes.random(12)
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(128, nonce)) }
        val out = ByteArray(12 + cipher.getOutputSize(length))
        nonce.copyInto(out)
        cipher.doFinal(plain, 0, length, out, 12)
        return out
    }

    fun decryptChunk(data: ByteArray): ByteArray = guard {
        if (data.size < 28) throw GeneralSecurityException("Chunk too short")
        val cipher = Cipher.getInstance(GCM).apply { init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, data, 0, 12)) }
        cipher.doFinal(data, 12, data.size - 12)
    }

    /** The key as Filen writes it in file metadata: hex for v3, the 32 characters for v2. */
    fun text(version: Int): String = if (version >= 3) bytes.hex() else bytes.decodeToString()

    /** The `002` key for the name, size and MIME fields of an upload (PBKDF2 over the raw key bytes). */
    fun asMasterKey(): MasterKey = MasterKey(bytes)

    companion object {
        /** A key from metadata: 32 characters (v1, v2) or 64 hex characters (v3). */
        fun parse(text: String): DataKey {
            val bytes = when {
                text.length == 64 && text.isHex() -> text.unhex()
                text.length == 32 -> text.encodeToByteArray()
                else -> null
            }
            if (bytes == null || bytes.size != 32) throw GeneralSecurityException("Bad file key")
            return DataKey(bytes)
        }

        /** A new file key in the account's format. */
        fun newFileKey(version: Int): DataKey =
            if (version >= 3) DataKey(Bytes.random(32)) else DataKey(Bytes.alnum(32).encodeToByteArray())
    }
}

private const val GCM = "AES/GCM/NoPadding"

/** Every decrypt failure is a [GeneralSecurityException], also bad base64 or hex. */
private inline fun <T> guard(block: () -> T): T = try {
    block()
} catch (e: GeneralSecurityException) {
    throw e
} catch (e: IllegalArgumentException) {
    throw GeneralSecurityException("Damaged data", e)
} catch (e: IndexOutOfBoundsException) {
    throw GeneralSecurityException("Damaged data", e)
}

/** OpenSSL-compatible AES-256-CBC with EVP_BytesToKey(MD5): Filen v1 metadata and v1 file chunks. Read only. */
internal object MetaV1 {
    fun decrypt(key: ByteArray, text: String): String {
        val data = Bytes.unb64(text)
        return salted(key, data).decodeToString()
    }

    /** "Salted__" + 8-byte salt + ciphertext. */
    fun salted(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size < 32) throw GeneralSecurityException("Too short")
        val (k, iv) = evpBytesToKey(key, data.copyOfRange(8, 16))
        return cbc(k, iv, data, 16)
    }

    /** A v1 file chunk. The checks follow the official SDKs, which guess the form from the first bytes. */
    fun decryptChunk(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size < 16) throw GeneralSecurityException("Too short")
        val first = data.copyOf(16)
        val ascii = first.decodeToString()
        val b64 = Bytes.b64(first)
        val salted = ascii.startsWith("Salted_") || b64.startsWith("Salted_")
        val opensslForm = salted || ascii.startsWith("U2FsdGVk") || b64.startsWith("U2FsdGVk")
        return when {
            opensslForm && !salted -> salted(key, Bytes.unb64(data.decodeToString()))
            opensslForm -> salted(key, data)
            else -> cbc(key, key.copyOf(16), data, 0)
        }
    }

    private fun cbc(key: ByteArray, iv: ByteArray, data: ByteArray, offset: Int): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding")
            .apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
            .doFinal(data, offset, data.size - offset)

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val out = ByteArray(48)
        var prev = ByteArray(0)
        var offset = 0
        val md5 = MessageDigest.getInstance("MD5")
        while (offset < out.size) {
            md5.update(prev)
            md5.update(password)
            md5.update(salt)
            prev = md5.digest()
            val n = minOf(prev.size, out.size - offset)
            prev.copyInto(out, offset, 0, n)
            offset += n
        }
        return out.copyOf(32) to out.copyOfRange(32, 48)
    }
}

/** Name hashes: the server finds items by name without reading the name (research F-HASH). */
object Names {
    /** v1 and v2 accounts: SHA-1 over the hex of SHA-512, as hex. */
    fun v2Hash(data: ByteArray): String = Bytes.sha1(Bytes.sha512(data).hex().encodeToByteArray()).hex()

    /** v3 accounts: HMAC-SHA256 with the key from the RSA private key ([Keyring.hmacKey]). */
    fun hmac(key: ByteArray, data: ByteArray): String =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data).hex()
}
