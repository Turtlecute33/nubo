package app.filenza.data

import android.util.AtomicFile
import app.filenza.crypto.Bytes
import app.filenza.crypto.DataKey
import app.filenza.crypto.Keyring
import app.filenza.crypto.hex
import app.filenza.crypto.unhex
import org.json.JSONObject
import java.io.File

/**
 * A logged-in account: the API key, the account keys, the root folder, and [cacheKey], a random key that
 * encrypts the local caches (thumbnails, offline index). DESIGN §7.
 */
class Session(
    val apiKey: String,
    val email: String,
    val userId: Long,
    val baseFolder: String,
    val keys: Keyring,
    val cacheKey: DataKey,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("v", 1)
        .put("apiKey", apiKey)
        .put("email", email)
        .put("userId", userId)
        .put("baseFolder", baseFolder)
        .put("keys", keys.toJson())
        .put("cacheKey", cacheKey.bytes.hex())

    override fun toString() = "Session(user=$userId)"

    companion object {
        fun fromJson(o: JSONObject) = Session(
            apiKey = o.getString("apiKey"),
            email = o.getString("email"),
            userId = o.optLong("userId"),
            baseFolder = o.getString("baseFolder"),
            keys = Keyring.fromJson(o.getJSONObject("keys")),
            cacheKey = DataKey(o.getString("cacheKey").unhex()),
        )

        fun newCacheKey() = DataKey(Bytes.random(32))
    }
}

/**
 * The session on disk: JSON encrypted with the [Vault] key, in no-backup storage. A file that cannot be read
 * (a new Keystore key after a restore, damage) counts as logged out.
 */
class SessionStore(dir: File, private val vault: Vault) {
    private val file = AtomicFile(File(dir, "session.bin"))

    fun load(): Session? = runCatching {
        if (!file.baseFile.exists()) return null
        Session.fromJson(JSONObject(vault.decryptString(file.readFully())))
    }.getOrNull()

    fun save(session: Session) {
        val blob = vault.encryptString(session.toJson().toString())
        val out = file.startWrite()
        try {
            out.write(blob)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    fun clear() = file.delete()
}
