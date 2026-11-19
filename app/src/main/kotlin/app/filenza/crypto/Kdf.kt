package app.filenza.crypto

import org.bouncycastle.crypto.digests.MD2Digest
import org.bouncycastle.crypto.digests.MD4Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Key derivation of the Filen login (research F-AUTH). The password never leaves the phone: the server gets
 * only [Derived.password]; [Derived.key] decrypts the account keys.
 */
object Kdf {
    /** What a password gives: the text that the server checks, and the key for the account keys. */
    class Derived(val password: String, val key: ByteArray)

    /** Auth version 2: PBKDF2-SHA512, 200 000 rounds. Key = the first half of the hex output, as text bytes. */
    fun v2(password: String, salt: String): Derived {
        val derived = pbkdf2Sha512(password.encodeToByteArray(), salt.encodeToByteArray(), 200_000, 64).hex()
        val key = derived.substring(0, 64).encodeToByteArray()
        val auth = Bytes.sha512(derived.substring(64).encodeToByteArray()).hex()
        return Derived(auth, key)
    }

    /** Auth version 3: Argon2id (t=3, m=64 MiB, p=4) over the hex salt. Key = the KEK, 32 raw bytes. */
    fun v3(password: String, saltHex: String): Derived {
        val derived = argon2id(password.encodeToByteArray(), saltHex.unhex()).hex()
        return Derived(derived.substring(64), derived.substring(0, 64).unhex())
    }

    /** Argon2id with the Filen parameters: t=3, m=64 MiB, p=4, version 0x13, 64 bytes. */
    fun argon2id(password: ByteArray, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(3)
            .withMemoryAsKB(65_536)
            .withParallelism(4)
            .withSalt(salt)
            .build()
        val out = ByteArray(64)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(password, out)
        return out
    }

    /** Auth version 1 (very old accounts): chained hashes, and the v2 name hash as the master key. */
    fun v1(password: String): Derived {
        val p = password.encodeToByteArray()
        fun chain(first: ByteArray, vararg next: String): String =
            next.fold(first.hex()) { acc, alg -> MessageDigest.getInstance(alg).digest(acc.encodeToByteArray()).hex() }
        val part1 = chain(Bytes.sha1(p), "SHA-256", "SHA-384", "SHA-512")
        val md2 = digest(MD2Digest(), p)
        val md4 = digest(MD4Digest(), md2.hex().encodeToByteArray())
        val part2 = chain(MessageDigest.getInstance("MD5").digest(md4.hex().encodeToByteArray()), "SHA-512")
        return Derived(part1 + part2, Names.v2Hash(p).encodeToByteArray())
    }

    /** PBKDF2 with HMAC-SHA512 over raw bytes (the platform version takes chars, which breaks raw keys). */
    fun pbkdf2Sha512(password: ByteArray, salt: ByteArray, rounds: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(password, "HmacSHA512")) }
        val out = ByteArray(length)
        var block = 1
        var offset = 0
        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(rounds - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(t.size, length - offset)
            t.copyInto(out, offset, 0, n)
            offset += n
            block++
        }
        return out
    }

    /** HKDF-SHA256 (RFC 5869) with no salt, for one block of output (at most 32 bytes). */
    fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 32)
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        expand.update(info)
        expand.update(1)
        return expand.doFinal().copyOf(length)
    }

    private fun digest(d: org.bouncycastle.crypto.Digest, data: ByteArray): ByteArray {
        d.update(data, 0, data.size)
        return ByteArray(d.digestSize).also { d.doFinal(it, 0) }
    }
}
