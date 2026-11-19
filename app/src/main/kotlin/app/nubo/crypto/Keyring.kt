package app.nubo.crypto

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * The keys of one account (research F-KEYS). v1/v2 accounts: a list of master keys (the newest first; a
 * password change adds one). v3 accounts: one data key (DEK). Both: an RSA key pair for shares, and an HMAC
 * key from the private key for name hashes. Lives only in memory and in the encrypted session (DESIGN §7).
 */
class Keyring(
    val authVersion: Int,
    val masterKeys: List<MasterKey>,
    val dek: DataKey?,
    val privateKey: RSAPrivateKey,
    /** Base64 SPKI DER, as the server gives it. */
    val publicKey: String,
) {
    init {
        require(if (authVersion >= 3) dek != null else masterKeys.isNotEmpty()) { "Missing account key" }
    }

    /** The format of new metadata and new file keys: 3 for v3 accounts, 2 for older ones. */
    val version: Int = if (authVersion >= 3) 3 else 2

    val hmacKey: ByteArray by lazy {
        val d = (privateKey as? RSAPrivateCrtKey)?.privateExponent ?: privateKey.privateExponent
        Kdf.hkdfSha256(d.unsigned(), "hmac-sha256-key".encodeToByteArray(), 32)
    }

    fun encryptMeta(text: String): String = dek?.encrypt(text) ?: masterKeys.first().encrypt(text)

    /** Tries the key that the format names; for `002` and v1, every master key, the newest first. */
    fun decryptMeta(text: String): String {
        if (text.startsWith("003")) return (dek ?: throw GeneralSecurityException("No data key")).decrypt(text)
        var last: GeneralSecurityException? = null
        for (key in masterKeys) {
            try {
                return key.decrypt(text)
            } catch (e: GeneralSecurityException) {
                last = e
            }
        }
        throw last ?: GeneralSecurityException("No master key")
    }

    /** The server-side hash of a file or folder name, so that it can find name clashes. */
    fun hashName(name: String): String {
        val lower = name.lowercase().encodeToByteArray()
        return if (authVersion >= 3) Names.hmac(hmacKey, lower) else Names.v2Hash(lower)
    }

    /** Metadata that another user shared with this account: base64 RSA-OAEP-SHA512. */
    fun decryptShared(text: String): String = decryptSharedBytes(text).decodeToString()

    fun decryptSharedBytes(text: String): ByteArray = try {
        Cipher.getInstance(RSA).apply { init(Cipher.DECRYPT_MODE, privateKey, OAEP) }.doFinal(Bytes.unb64(text))
    } catch (e: IllegalArgumentException) {
        throw GeneralSecurityException("Damaged data", e)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("authVersion", authVersion)
        .put("masterKeys", JSONArray(masterKeys.map { Bytes.b64(it.raw) }))
        .put("dek", dek?.bytes?.hex() ?: "")
        .put("privateKey", Bytes.b64(privateKey.encoded))
        .put("publicKey", publicKey)

    companion object {
        private const val RSA = "RSA/ECB/OAEPPadding"
        private val OAEP = OAEPParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, PSource.PSpecified.DEFAULT)

        fun fromJson(o: JSONObject): Keyring {
            val masters = o.getJSONArray("masterKeys").let { a -> (0 until a.length()).map { MasterKey(Bytes.unb64(a.getString(it))) } }
            return Keyring(
                authVersion = o.getInt("authVersion"),
                masterKeys = masters,
                dek = o.optString("dek").takeIf { it.isNotEmpty() }?.let { DataKey(it.unhex()) },
                privateKey = privateKey(Bytes.unb64(o.getString("privateKey"))),
                publicKey = o.getString("publicKey"),
            )
        }

        fun privateKey(pkcs8: ByteArray): RSAPrivateKey =
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8)) as RSAPrivateKey

        /** Encrypts [text] for the owner of [publicKey] (base64 SPKI): shares with other users. */
        fun encryptFor(publicKey: String, text: String): String {
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Bytes.unb64(publicKey)))
            return Bytes.b64(Cipher.getInstance(RSA).apply { init(Cipher.ENCRYPT_MODE, key, OAEP) }.doFinal(text.encodeToByteArray()))
        }

        /**
         * The master key list that the server returns, `key1|key2|…`, with [current] first and no duplicate.
         * [current] is the key from the password.
         */
        fun masterList(current: MasterKey, decrypted: String): List<MasterKey> =
            listOf(current) + decrypted.split('|').filter { it.isNotEmpty() }.map { MasterKey(it.encodeToByteArray()) }.filterNot { it.sameAs(current) }

        /** Big-endian bytes with no sign byte, like Go's big.Int.Bytes(). */
        private fun BigInteger.unsigned(): ByteArray {
            val b = toByteArray()
            return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
        }
    }
}
