package app.filenza.drive

import app.filenza.crypto.DataKey
import app.filenza.crypto.MetaKey
import org.json.JSONObject
import java.io.IOException
import java.text.Normalizer
import java.util.UUID

/**
 * Writes to the own drive (research F-60..F-66), each under the drive-write lock. After a write that puts
 * items into a folder, [Sharing.propagate] adds them to the folder's shares and links (F-53).
 */
class Ops internal constructor(private val drive: Drive) {
    private val api get() = drive.api

    suspend fun createFolder(parent: String, name: String): Item = drive.guard {
        val clean = NameRules.clean(name)
        val ring = drive.keys()
        val now = System.currentTimeMillis()
        val uuid = UUID.randomUUID().toString()
        val meta = JSONObject().put("name", clean).put("creation", now).toString()
        val result = drive.lock.write {
            api.post(
                "/v3/dir/create",
                JSONObject().put("uuid", uuid).put("name", ring.encryptMeta(meta)).put("nameHashed", ring.hashName(clean)).put("parent", parent),
            )
        }
        // A folder with this name was there already: the server gives its UUID and makes no new folder.
        val made = result.optString("uuid").ifEmpty { uuid }
        val item = Item(uuid = made, parent = parent, name = clean, folder = true, modified = now, created = now, uploaded = now)
        if (made == uuid) drive.sharing.propagate(parent, listOf(item))
        drive.invalidate(Listing.Dir(parent))
        item
    }

    suspend fun rename(item: Item, name: String): Item = drive.guard {
        val clean = NameRules.clean(name)
        if (clean == item.name) return@guard item
        val ring = drive.keys()
        val hashed = ring.hashName(clean)
        val renamed = item.copy(name = clean)
        drive.lock.write {
            // Only another item with the same name blocks; a change of letter case is a rename of the same item.
            val clash = api.post(if (item.folder) "/v3/dir/exists" else "/v3/file/exists", JSONObject().put("nameHashed", hashed).put("parent", item.parent))
            if (clash.optBoolean("exists") && clash.optString("uuid") != item.uuid) throw IOException("An item with this name is in the folder")
            if (item.folder) {
                api.post(
                    "/v3/dir/metadata",
                    JSONObject().put("uuid", item.uuid).put("nameHashed", hashed).put("name", ring.encryptMeta(renamed.metaJson())),
                )
            } else {
                api.post(
                    "/v3/file/metadata",
                    JSONObject().put("uuid", item.uuid).put("name", fieldKey(item).encrypt(clean)).put("nameHashed", hashed)
                        .put("metadata", ring.encryptMeta(renamed.metaJson())),
                )
            }
        }
        drive.sharing.renamed(renamed)
        drive.invalidate(Listing.Dir(item.parent), Listing.Recents, Listing.Favorites, Listing.Links, Listing.SharedOut)
        renamed
    }

    /** Moves [items] into [to]. Items with a name that is in [to] already stay; the count of them is returned. */
    suspend fun move(items: List<Item>, to: String): Int = drive.guard {
        val ring = drive.keys()
        var skipped = 0
        val moved = ArrayList<Item>()
        drive.lock.write {
            for (item in items) {
                if (item.parent == to || item.uuid == to) continue
                val clash = api.post(if (item.folder) "/v3/dir/exists" else "/v3/file/exists", JSONObject().put("nameHashed", ring.hashName(item.name)).put("parent", to))
                if (clash.optBoolean("exists")) {
                    skipped++
                    continue
                }
                api.post(if (item.folder) "/v3/dir/move" else "/v3/file/move", JSONObject().put("uuid", item.uuid).put("to", to))
                moved += item.copy(parent = to)
            }
        }
        if (moved.isNotEmpty()) drive.sharing.propagate(to, moved)
        drive.invalidate(*(items.map { Listing.Dir(it.parent) } + Listing.Dir(to)).distinct().toTypedArray())
        skipped
    }

    suspend fun trash(items: List<Item>) = drive.guard {
        drive.lock.write {
            for (item in items) api.post(if (item.folder) "/v3/dir/trash" else "/v3/file/trash", JSONObject().put("uuid", item.uuid))
        }
        drive.invalidate(null)
    }

    /** Puts trashed items back where they were. */
    suspend fun restore(items: List<Item>) = drive.guard {
        drive.lock.write {
            for (item in items) api.post(if (item.folder) "/v3/dir/restore" else "/v3/file/restore", JSONObject().put("uuid", item.uuid))
        }
        // The answer has no parent: read each item again, so that shares and links of its folder get it (F-63).
        for (item in items) {
            val back = runCatching { drive.item(item.uuid, item.folder) }.getOrNull() ?: continue
            runCatching { drive.sharing.propagate(back.parent, listOf(back)) }
        }
        drive.invalidate(null)
    }

    suspend fun deleteForever(items: List<Item>) = drive.guard {
        drive.lock.write {
            for (item in items) {
                api.post(if (item.folder) "/v3/dir/delete/permanent" else "/v3/file/delete/permanent", JSONObject().put("uuid", item.uuid))
            }
        }
        drive.invalidate(Listing.Trash)
    }

    suspend fun emptyTrash() = drive.guard {
        drive.lock.write { api.post("/v3/trash/empty") }
        drive.invalidate(Listing.Trash)
    }

    suspend fun favorite(item: Item, value: Boolean) = drive.guard {
        drive.lock.write {
            api.post(
                "/v3/item/favorite",
                JSONObject().put("uuid", item.uuid).put("type", if (item.folder) "folder" else "file").put("value", if (value) 1 else 0),
            )
        }
        drive.invalidate(Listing.Dir(item.parent), Listing.Favorites, Listing.Recents)
    }

    companion object {
        /**
         * The key of the per-file name, size and MIME fields (research F-43): `003` with the key bytes for v3
         * files, `002` with the key text for older ones.
         */
        fun fieldKey(item: Item): MetaKey = fieldKey(item.dataKey(), item.version)

        fun fieldKey(key: DataKey, version: Int): MetaKey = if (version >= 3) key else key.asMasterKey()
    }
}

/** Name rules of the official clients (research F-44). */
object NameRules {
    private val FORBIDDEN = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
    private val RESERVED = Regex("^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$", RegexOption.IGNORE_CASE)

    /** The name in NFC, or an [IOException] with the reason. */
    fun clean(name: String): String {
        val n = Normalizer.normalize(name, Normalizer.Form.NFC)
        val problem = when {
            n.isEmpty() -> "Enter a name"
            n.encodeToByteArray().size > 255 -> "The name is too long"
            n == "." || n == ".." -> "This name is not allowed"
            n.any { it < ' ' || it == '\u007f' } -> "The name has a control character"
            n.any { it in FORBIDDEN } -> "A name cannot contain / \\ : * ? \" < > |"
            n.endsWith('.') || n.endsWith(' ') -> "A name cannot end with a dot or a space"
            n.startsWith(' ') -> "A name cannot start with a space"
            RESERVED.matches(n) -> "This name is reserved"
            else -> null
        }
        if (problem != null) throw IOException(problem)
        return n
    }

    /** Null when [name] is valid, else the reason. For the name dialog. */
    fun problem(name: String): String? = runCatching { clean(name) }.exceptionOrNull()?.message
}
