package app.nubo.drive

import app.nubo.api.optText
import app.nubo.crypto.DataKey
import org.json.JSONObject

/**
 * Turns server entries into [Item]s (research F-LIST). [decrypt] opens the metadata: the account keys for own
 * items, the private key for items shared with me, the link key for public links. An entry that does not
 * decrypt or has bad fields gives null: the list shows the rest. Never logs names or keys. A JSON null in a text
 * field gives "" ([optText], review 61).
 */
object Decode {
    fun file(o: JSONObject, source: Source, decrypt: (String) -> String): Item? = runCatching {
        val meta = JSONObject(decrypt(o.getString("metadata")))
        val key = meta.getString("key")
        DataKey.parse(key) // reject a bad key now, not at download time
        val name = meta.getString("name")
        if (!validName(name)) return null
        Item(
            uuid = uuid(o),
            parent = o.optText("parent"),
            name = name,
            folder = false,
            size = meta.optLongLoose("size").takeIf { it >= 0 } ?: o.optLong("size"),
            mime = meta.optText("mime"),
            modified = ms(meta.optLongLoose("lastModified")).takeIf { it > 0 } ?: ms(o.optLong("timestamp")),
            created = ms(meta.optLongLoose("creation")),
            uploaded = ms(o.optLong("timestamp")),
            favorite = o.optBoolLoose("favorited"),
            key = key,
            region = o.optText("region"),
            bucket = o.optText("bucket"),
            chunks = o.optInt("chunks"),
            version = o.optInt("version", 2),
            hash = meta.optText("blake3").takeIf { it.length == 64 }.orEmpty(),
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
            uuid = uuid(o),
            parent = o.optText("parent"),
            name = name,
            folder = true,
            modified = created,
            created = created,
            uploaded = ms(o.optLong("timestamp")),
            favorite = o.optBoolLoose("favorited"),
            color = o.optText("color"),
            source = source,
        )
    }.getOrNull()

    /** True for a UUID: only hex digits and dashes, so it is safe in a file path (`offline/<uuid>`). */
    fun isUuid(text: String): Boolean = UUID.matches(text)

    /** The item's UUID. It goes into file paths, so a bad server cannot send ".." (review 60). */
    private fun uuid(o: JSONObject): String = o.getString("uuid").also { require(isUuid(it)) }

    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

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
