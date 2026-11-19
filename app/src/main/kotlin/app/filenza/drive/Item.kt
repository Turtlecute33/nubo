package app.filenza.drive

import androidx.compose.runtime.Immutable
import app.filenza.crypto.DataKey
import app.filenza.util.FileType
import org.json.JSONObject

/**
 * A decrypted file or folder. For files, [key] is the file key text, so that the item can download itself.
 * [toString] leaves out the name and the key, so that no log can show them. DESIGN §7.
 */
@Immutable
data class Item(
    val uuid: String,
    val parent: String,
    val name: String,
    val folder: Boolean,
    val size: Long = 0,
    val mime: String = "",
    /** Last change in ms: the file's own time, or the folder's creation time. */
    val modified: Long = 0,
    val created: Long = 0,
    /** When the server got the item, in ms. Orders "Recents". */
    val uploaded: Long = 0,
    val favorite: Boolean = false,
    val color: String = "",
    val key: String = "",
    val region: String = "",
    val bucket: String = "",
    val chunks: Int = 0,
    val version: Int = 2,
    /** BLAKE3 of the plain content (hex), when the uploader wrote one. */
    val hash: String = "",
    /** Where the item comes from: own drive, shared with me, or a public link. */
    val source: Source = Source.Own,
) {
    val type: FileType = if (folder) FileType.FOLDER else FileType.of(name)

    fun dataKey(): DataKey = DataKey.parse(key)

    /** The metadata JSON that shares and links get (research F-META-FILE). Never for folders' children lists. */
    fun metaJson(): String = if (folder) {
        JSONObject().put("name", name).apply { if (created > 0) put("creation", created) }.toString()
    } else {
        JSONObject()
            .put("name", name)
            .put("size", size)
            .put("mime", mime)
            .put("key", key)
            .put("lastModified", modified)
            // No "creation" when it is not known: 0 would show as 1970 in the official apps.
            .apply { if (created > 0) put("creation", created) }
            .apply { if (hash.isNotEmpty()) put("blake3", hash) }
            .toString()
    }

    override fun toString() = "Item(${if (folder) "folder" else "file"} $uuid)"

    /** All fields, for the encrypted offline index. Only own files and files of shares or links. */
    fun toJson(): JSONObject = JSONObject()
        .put("uuid", uuid).put("parent", parent).put("name", name).put("folder", folder).put("size", size).put("mime", mime)
        .put("modified", modified).put("created", created).put("uploaded", uploaded).put("favorite", favorite)
        .put("key", key).put("region", region).put("bucket", bucket).put("chunks", chunks).put("version", version).put("hash", hash)
        .put(
            "source",
            when (val s = source) {
                Source.Own -> "own"
                is Source.SharedIn -> "in:" + s.owner
                is Source.SharedOut -> "out:" + s.with
                is Source.Link -> "link:" + s.linkUuid
            },
        )

    companion object {
        fun fromJson(o: JSONObject) = Item(
            uuid = o.getString("uuid"), parent = o.optString("parent"), name = o.getString("name"), folder = o.optBoolean("folder"),
            size = o.optLong("size"), mime = o.optString("mime"), modified = o.optLong("modified"), created = o.optLong("created"),
            uploaded = o.optLong("uploaded"), favorite = o.optBoolean("favorite"), key = o.optString("key"), region = o.optString("region"),
            bucket = o.optString("bucket"), chunks = o.optInt("chunks"), version = o.optInt("version", 2), hash = o.optString("hash"),
            source = o.optString("source", "own").let { s ->
                when {
                    s.startsWith("in:") -> Source.SharedIn(s.substring(3))
                    s.startsWith("out:") -> Source.SharedOut(s.substring(4))
                    s.startsWith("link:") -> Source.Link(s.substring(5))
                    else -> Source.Own
                }
            },
        )
    }
}

/** Who owns the item, and how the app reaches its content. */
@Immutable
sealed interface Source {
    data object Own : Source

    /** Shared with this account by another user; read only. */
    data class SharedIn(val owner: String) : Source

    /** Shared by this account with the users in [with] (email addresses, comma-separated). */
    data class SharedOut(val with: String) : Source

    /** Inside a public link. Read only; downloads need no account. */
    data class Link(val linkUuid: String) : Source
}
