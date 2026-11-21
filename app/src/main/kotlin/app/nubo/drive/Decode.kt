package app.nubo.drive

import android.util.JsonReader
import app.nubo.api.eachObject
import app.nubo.api.optText
import app.nubo.crypto.DataKey
import app.nubo.util.Parallel
import kotlinx.coroutines.coroutineScope
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
     * Each entry of [list], decoded on all cores in batches (review P-01). The order stays; an entry that does not
     * decode is left out. Shares need one RSA decrypt for each entry, so a large share gains the most.
     */
    suspend fun <T> all(list: List<T>, decode: (T) -> Item?): List<Item> = coroutineScope {
        val parts = Parallel<List<T>, List<Item>>(this) { batch -> batch.mapNotNull(decode) }
        for (batch in list.chunked(BATCH)) parts.add(batch)
        parts.results().flatten()
    }

    /**
     * The arrays named in [sections] of the object at [r] (a listing or the `data` of `/v3/dir/download`, research
     * F-65), read one entry at a time. Batches decode on all cores while the answer still comes in (review P-01).
     * The result has the sections in the order of [sections]. More than [MAX_TREE] entries fail, before they take
     * all the memory of the app.
     */
    suspend fun stream(r: JsonReader, sections: Map<String, (JSONObject) -> Item?>): List<Item> = coroutineScope {
        val parts = sections.mapValues { (_, decode) -> Parallel<List<JSONObject>, List<Item>>(this) { batch -> batch.mapNotNull(decode) } }
        var count = 0
        r.beginObject()
        while (r.hasNext()) {
            val part = parts[r.nextName()]
            if (part == null) {
                r.skipValue()
                continue
            }
            var batch = ArrayList<JSONObject>(BATCH)
            r.eachObject {
                if (++count > MAX_TREE) throw IOException("This folder has too many items")
                batch += it
                if (batch.size == BATCH) {
                    part.add(batch)
                    batch = ArrayList(BATCH)
                }
            }
            if (batch.isNotEmpty()) part.add(batch)
        }
        r.endObject()
        parts.values.flatMap { it.results().flatten() }
    }

    /** Entries for one task of [all] and [stream]: about 20 ms of work on a phone core (estimate). */
    private const val BATCH = 256

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
