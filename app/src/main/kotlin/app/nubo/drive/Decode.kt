package app.nubo.drive

import android.util.JsonReader
import app.nubo.api.eachObject
import app.nubo.api.optText
import app.nubo.crypto.DataKey
import org.json.JSONObject
import java.io.IOException

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
        // A negative size would pass the size limits of the viewers and wrap in toInt().
        val size = meta.optLongLoose("size").takeIf { it >= 0 } ?: o.optLong("size").takeIf { it >= 0 } ?: return null
        Item(
            uuid = uuid(o),
            parent = o.optText("parent"),
            name = name,
            folder = false,
            size = size,
            mime = mime(meta.optText("mime")),
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

    /**
     * The `data` of `/v3/dir/download` (research F-65) from [r], one entry at a time: folders first, then files,
     * with no root folder. More than [MAX_TREE] items fail, before they take all the memory of the app.
     */
    fun tree(r: JsonReader, decrypt: (String) -> String): List<Item> {
        val dirs = ArrayList<Item>()
        val files = ArrayList<Item>()
        fun add(list: MutableList<Item>, item: Item?) {
            if (item == null) return
            if (dirs.size + files.size >= MAX_TREE) throw IOException("This folder has too many items")
            list += item
        }
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "files" -> r.eachObject { add(files, file(it, Source.Own, decrypt)) }
                "folders" -> r.eachObject { if (it.optString("parent") != "base") add(dirs, folder(it, Source.Own, "name", decrypt)) }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return dirs + files
    }

    const val MAX_TREE = 200_000

    /** True for a UUID: only hex digits and dashes, so it is safe in a file path (`offline/<uuid>`). */
    fun isUuid(text: String): Boolean = UUID.matches(text)

    /** The item's UUID. It goes into file paths, so a bad server cannot send ".." (review 60). */
    private fun uuid(o: JSONObject): String = o.getString("uuid").also { require(isUuid(it)) }

    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * A name that is safe as a file name on the phone and on the screen: no path parts, no control characters,
     * and no bidi controls: a right-to-left override (U+202E) can make an ".apk" name read as a ".png" one.
     */
    fun validName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.length <= 1024 &&
            name.none { it == '/' || it == '\\' || it.isISOControl() || it in BIDI_CONTROLS }

    /** U+202A..U+202E (embeddings, overrides) and U+2066..U+2069 (isolates). */
    private val BIDI_CONTROLS = ('\u202A'..'\u202E') + ('\u2066'..'\u2069')

    /** A MIME type as other apps take it, or "" (then the type comes from the name). A sharer could send megabytes. */
    fun mime(text: String): String = text.takeIf { MIME.matches(it) }.orEmpty()

    private val MIME = Regex("^[A-Za-z0-9][\\w!#$&^.+-]{0,126}/[A-Za-z0-9][\\w!#$&^.+-]{0,126}$")

    /** Filen mixes seconds and milliseconds. Like the official SDKs: the reading that is nearer to now wins. */
    fun ms(t: Long): Long {
        if (t <= 0) return 0
        if (t > Long.MAX_VALUE / 1000) return t // no reading in seconds: t * 1000 would wrap
        val now = System.currentTimeMillis()
        return if (kotlin.math.abs(now - t) < kotlin.math.abs(now - t * 1000)) t else t * 1000
    }

}

/** Numbers can come as numbers or as text. */
internal fun JSONObject.optLongLoose(name: String): Long {
    val v: Any? = opt(name)
    if (v is Number) return v.toLong()
    if (v is String) return v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong() ?: -1L
    return -1L
}

/** Booleans can come as `true`, `1` or text. */
internal fun JSONObject.optBoolLoose(name: String): Boolean {
    val v: Any? = opt(name)
    if (v is Boolean) return v
    if (v is Number) return v.toInt() == 1
    return v is String && (v == "1" || v == "true")
}
