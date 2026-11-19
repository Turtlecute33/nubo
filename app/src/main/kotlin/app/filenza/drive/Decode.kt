package app.filenza.drive

import app.filenza.crypto.DataKey
import org.json.JSONObject

/**
 * Turns server entries into [Item]s (research F-LIST). [decrypt] opens the metadata: the account keys for own
 * items, the private key for items shared with me, the link key for public links. An entry that does not
 * decrypt or has bad fields gives null: the list shows the rest. Never logs names or keys.
 */
object Decode {
    fun file(o: JSONObject, source: Source, decrypt: (String) -> String): Item? = runCatching {
        val meta = JSONObject(decrypt(o.getString("metadata")))
        val key = meta.getString("key")
        DataKey.parse(key) // reject a bad key now, not at download time
        val name = meta.getString("name")
        if (!validName(name)) return null
        Item(
            uuid = o.getString("uuid"),
            parent = o.optString("parent"),
            name = name,
            folder = false,
            size = meta.optLongLoose("size").takeIf { it >= 0 } ?: o.optLong("size"),
            mime = meta.optString("mime"),
            modified = ms(meta.optLongLoose("lastModified")).takeIf { it > 0 } ?: ms(o.optLong("timestamp")),
            created = ms(meta.optLongLoose("creation")),
            uploaded = ms(o.optLong("timestamp")),
            favorite = o.optBoolLoose("favorited"),
            key = key,
            region = o.optString("region"),
            bucket = o.optString("bucket"),
            chunks = o.optInt("chunks"),
            version = o.optInt("version", 2),
            hash = meta.optString("blake3").takeIf { it.length == 64 }.orEmpty(),
            source = source,
        )
    }.getOrNull()

    /** [nameField]: "name" in own listings, "metadata" in shares and links. */
    fun folder(o: JSONObject, source: Source, nameField: String, decrypt: (String) -> String): Item? = runCatching {
        val meta = JSONObject(decrypt(o.getString(nameField)))
        val name = meta.getString("name")
        if (!validName(name)) return null
        val created = ms(meta.optLongLoose("creation")).takeIf { it > 0 } ?: ms(o.optLong("timestamp"))
        Item(
            uuid = o.getString("uuid"),
            parent = o.optString("parent"),
            name = name,
            folder = true,
            modified = created,
            created = created,
            uploaded = ms(o.optLong("timestamp")),
            favorite = o.optBoolLoose("favorited"),
            color = o.optString("color").takeIf { it != "null" }.orEmpty(),
            source = source,
        )
    }.getOrNull()

    /** A name that is safe as a file name on the phone: no path parts, no control characters. */
    fun validName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.length <= 1024 && name.none { it == '/' || it == '\\' || it < ' ' }

    /** Filen mixes seconds and milliseconds. Like the official SDKs: the reading that is nearer to now wins. */
    fun ms(t: Long): Long {
        if (t <= 0) return 0
        val now = System.currentTimeMillis()
        return if (kotlin.math.abs(now - t) < kotlin.math.abs(now - t * 1000)) t else t * 1000
    }

    /** Numbers can come as numbers or as text. */
    private fun JSONObject.optLongLoose(name: String): Long {
        val v: Any? = opt(name)
        if (v is Number) return v.toLong()
        if (v is String) return v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong() ?: -1L
        return -1L
    }

    private fun JSONObject.optBoolLoose(name: String): Boolean {
        val v: Any? = opt(name)
        if (v is Boolean) return v
        if (v is Number) return v.toInt() == 1
        return v is String && (v == "1" || v == "true")
    }
}
