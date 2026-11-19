package app.filenza.api

import app.filenza.crypto.DataKey
import app.filenza.crypto.Kdf
import app.filenza.crypto.Keyring
import app.filenza.crypto.MasterKey
import app.filenza.crypto.unhex
import app.filenza.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.GeneralSecurityException

/**
 * The login (research F-AUTH): read the salt and auth version, derive on the phone, send only the derived
 * password, then read and decrypt the account keys. The password itself never leaves the phone and is not kept.
 */
class Auth(private val api: Api) {
    /**
     * Logs in. Throws [ApiException] with [ApiException.needs2fa] when the account needs a code: call again
     * with [code]. Does not change [Api.key] until the login is complete.
     */
    suspend fun login(email: String, password: String, code: String?): Session {
        val mail = email.trim() // as typed, like the official SDKs: the server decides about letter case
        val info = api.post("/v3/auth/info", JSONObject().put("email", mail))
        val version = info.optInt("authVersion", 2)
        val salt = info.optString("salt")
        // Argon2id takes about one second and 64 MiB: never on the main thread.
        val derived = withContext(Dispatchers.Default) {
            when (version) {
                1 -> Kdf.v1(password)
                2 -> Kdf.v2(password, salt)
                3 -> Kdf.v3(password, salt)
                else -> throw ApiException("unsupported_auth_version", 0)
            }
        }
        val login = api.post(
            "/v3/login",
            JSONObject()
                .put("email", mail)
                .put("password", derived.password)
                .put("twoFactorCode", code?.filter(Char::isLetterOrDigit)?.ifEmpty { null } ?: NO_CODE)
                .put("authVersion", version),
            // Logins are rate limited (research F-24, F-87): never send one again by itself.
            retry = false,
        )
        val key = login.getString("apiKey")
        val authed = api.withKey(key)
        val ring = keyring(authed, version, derived.key)
        val base = authed.get("/v3/user/baseFolder").getString("uuid")
        val user = runCatching { authed.get("/v3/user/info") }.getOrNull()
        return Session(
            apiKey = key,
            email = user?.optString("email")?.ifEmpty { null } ?: mail,
            userId = user?.optLong("id") ?: 0,
            baseFolder = base,
            keys = ring,
            cacheKey = Session.newCacheKey(),
        )
    }

    private suspend fun keyring(api: Api, version: Int, key: ByteArray): Keyring = withContext(Dispatchers.Default) {
        val masters: List<MasterKey>
        val dek: DataKey?
        if (version >= 3) {
            val kek = DataKey(key)
            val text = kek.decrypt(api.get("/v3/user/dek").getString("dek"))
            dek = DataKey(text.unhex())
            masters = emptyList()
        } else {
            val current = MasterKey(key)
            val sent = current.encrypt(key.decodeToString())
            val all = api.post("/v3/user/masterKeys", JSONObject().put("masterKeys", sent)).getString("keys")
            masters = Keyring.masterList(current, current.decrypt(all))
            dek = null
        }
        val pair = api.get("/v3/user/keyPair/info")
        val privateText = if (dek != null) dek.decrypt(pair.getString("privateKey"))
        else masters.firstNotNullOfOrNull { runCatching { it.decrypt(pair.getString("privateKey")) }.getOrNull() }
            ?: throw GeneralSecurityException("Cannot decrypt the private key")
        Keyring(version, masters, dek, Keyring.privateKey(app.filenza.crypto.Bytes.unb64(privateText)), pair.getString("publicKey"))
    }

    private companion object {
        /** What the official clients send when the account has no two-factor login. */
        const val NO_CODE = "XXXXXX"
    }
}
