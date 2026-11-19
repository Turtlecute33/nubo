package app.filenza.drive

import app.filenza.api.ApiException
import app.filenza.crypto.Bytes
import app.filenza.crypto.Keyring
import app.filenza.crypto.hex
import app.filenza.drive.Drive.Companion.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** The state of the public link of one item. [url] holds the key: show it, never log it. */
data class LinkState(
    val enabled: Boolean,
    val linkUuid: String = "",
    val url: String = "",
    val expiration: String = "never",
    val hasPassword: Boolean = false,
) {
    override fun toString() = "LinkState($enabled)"
}

/** A user that an item is shared with. */
data class ShareUser(val id: Long, val email: String)

/**
 * Public links and shares with other Filen users (research F-53, F-72..F-79). A folder share or link holds a
 * copy of the metadata of every item inside, encrypted for the receiver or with the link key: so each new,
 * moved or renamed item must be added there too ([propagate], [renamed]).
 */
class Sharing internal constructor(private val drive: Drive) {
    private val api get() = drive.api

    // ---- propagation (F-53) ----

    /** Adds [items] (and everything inside the folders among them) to the shares and links of [parent]. */
    suspend fun propagate(parent: String, items: List<Item>) = coroutineScope {
        val links = async { api.post("/v3/item/linked", JSONObject().put("uuid", parent)) }
        val shared = async { api.post("/v3/item/shared", JSONObject().put("uuid", parent)) }
        val linkRows = links.await().optJSONArray("links").objects()
        val userRows = shared.await().optJSONArray("users").objects()
        if (linkRows.isEmpty() && userRows.isEmpty()) return@coroutineScope
        val all = withContext(Dispatchers.IO) { items + items.filter { it.folder }.flatMap { drive.tree(it.uuid) } }
        val ring = drive.keys()
        for (link in linkRows) addToLink(link.getString("linkUUID"), link.getString("linkKey"), ring, all, rootParent = null)
        for (user in userRows) shareTo(user.getString("email"), user.getString("publicKey"), all, root = null)
    }

    /** After a rename: the new name goes to every share and link that holds the item. */
    suspend fun renamed(item: Item) = coroutineScope {
        val ring = drive.keys()
        val links = async { api.post("/v3/item/linked", JSONObject().put("uuid", item.uuid)) }
        val shared = async { api.post("/v3/item/shared", JSONObject().put("uuid", item.uuid)) }
        val meta = item.metaJson()
        for (link in links.await().optJSONArray("links").objects()) {
            val key = Links.metaKey(ring.decryptMeta(link.getString("linkKey")))
            api.post(
                "/v3/item/linked/rename",
                JSONObject().put("uuid", item.uuid).put("linkUUID", link.getString("linkUUID")).put("metadata", key.encrypt(meta)),
            )
        }
        for (user in shared.await().optJSONArray("users").objects()) {
            api.post(
                "/v3/item/shared/rename",
                JSONObject().put("uuid", item.uuid).put("receiverId", user.optLong("id"))
                    .put("metadata", Keyring.encryptFor(user.getString("publicKey"), meta)),
            )
        }
    }

    // ---- public links, owner side (F-72, F-74) ----

    suspend fun linkState(item: Item): LinkState = drive.guard {
        if (item.folder) {
            val o = api.post("/v3/dir/link/status", JSONObject().put("uuid", item.uuid))
            if (!o.optBoolean("exists")) return@guard LinkState(false)
            val key = drive.keys().decryptMeta(o.getString("key"))
            LinkState(true, o.getString("uuid"), Links.url(true, o.getString("uuid"), key), o.optString("expirationText", "never"), hasPassword(o))
        } else {
            val o = api.post("/v3/file/link/status", JSONObject().put("uuid", item.uuid))
            if (!o.optBoolean("enabled")) return@guard LinkState(false)
            LinkState(true, o.getString("uuid"), Links.url(false, o.getString("uuid"), item.key), o.optString("expirationText", "never"), hasPassword(o))
        }
    }

    /** v3 file keys do not fit the link URL (research F-77): no file links for them. */
    fun canLink(item: Item): Boolean = item.folder || item.key.length == 32

    /**
     * Makes or updates the link of [item]. [expiration] is one of [EXPIRATIONS]; an empty [password] means
     * none. A folder link copies the metadata of every item inside for the link key.
     */
    suspend fun setLink(item: Item, expiration: String, password: String?): LinkState = drive.guard {
        require(expiration in EXPIRATIONS)
        if (!canLink(item)) throw IOException("Links for this file are not available")
        val salt = Links.newSalt()
        val hashed = withContext(Dispatchers.Default) { Links.hashPassword(password, salt) }
        val pw = if (password.isNullOrEmpty()) "empty" else "notempty"
        val current = linkState(item)
        if (item.folder) {
            if (!current.enabled) createFolderLink(item)
            api.post(
                "/v3/dir/link/edit",
                JSONObject().put("uuid", item.uuid).put("expiration", expiration).put("password", pw)
                    .put("passwordHashed", hashed).put("salt", salt).put("downloadBtn", true),
            )
        } else {
            val linkUuid = current.linkUuid.ifEmpty { UUID.randomUUID().toString() }
            drive.lock.write {
                api.post(
                    "/v3/file/link/edit",
                    JSONObject().put("uuid", linkUuid).put("fileUUID", item.uuid).put("expiration", expiration)
                        .put("password", pw).put("passwordHashed", hashed).put("salt", salt).put("downloadBtn", true).put("type", "enable"),
                )
            }
        }
        drive.invalidate(Listing.Links)
        linkState(item)
    }

    suspend fun removeLink(item: Item) = drive.guard {
        val state = linkState(item)
        if (!state.enabled) return@guard
        drive.lock.write {
            if (item.folder) {
                api.post("/v3/dir/link/remove", JSONObject().put("uuid", item.uuid))
            } else {
                api.post(
                    "/v3/file/link/edit",
                    JSONObject().put("uuid", state.linkUuid).put("fileUUID", item.uuid).put("expiration", "never").put("password", "empty")
                        .put("passwordHashed", "empty").put("salt", Links.newSalt()).put("downloadBtn", false).put("type", "disable"),
                )
            }
        }
        drive.invalidate(Listing.Links)
    }

    private suspend fun createFolderLink(folder: Item) {
        val ring = drive.keys()
        val linkUuid = UUID.randomUUID().toString()
        val key = if (ring.version >= 3) Bytes.random(32).hex() else Bytes.alnum(32)
        val inside = drive.tree(folder.uuid)
        drive.lock.write { addToLink(linkUuid, ring.encryptMeta(key), ring, listOf(folder) + inside, rootParent = folder.uuid) }
    }

    /** Adds [items] to one folder link. The item [rootParent] (the link root) gets the parent "base". */
    private suspend fun addToLink(linkUuid: String, encryptedKey: String, ring: Keyring, items: List<Item>, rootParent: String?) {
        val key = Links.metaKey(ring.decryptMeta(encryptedKey))
        parallel(items) { item ->
            api.post(
                "/v3/dir/link/add",
                JSONObject().put("uuid", item.uuid).put("parent", if (item.uuid == rootParent) "base" else item.parent)
                    .put("linkUUID", linkUuid).put("type", if (item.folder) "folder" else "file")
                    .put("metadata", key.encrypt(item.metaJson())).put("key", encryptedKey).put("expiration", "never"),
            )
        }
    }

    // ---- shares with users (F-78, F-79) ----

    suspend fun sharedWith(item: Item): List<ShareUser> = drive.guard {
        api.post("/v3/item/shared", JSONObject().put("uuid", item.uuid)).optJSONArray("users").objects()
            .map { ShareUser(it.optLong("id"), it.optString("email")) }
    }

    /** Shares [item] (and all inside, for a folder) with the Filen user [email]. Read only for the receiver. */
    suspend fun share(item: Item, email: String) = drive.guard {
        val mail = email.trim()
        val key = try {
            api.post("/v3/user/publicKey", JSONObject().put("email", mail)).getString("publicKey")
        } catch (e: ApiException) {
            // Only a refused address means "no account"; an ended session or a server problem keeps its own text.
            if (e.sessionEnded || e.http >= 500 || e.http == 429) throw e
            throw IOException("No Filen account has this email address", e)
        }
        val all = if (item.folder) listOf(item) + drive.tree(item.uuid) else listOf(item)
        shareTo(mail, key, all, root = item.uuid)
        drive.invalidate(Listing.SharedOut)
    }

    suspend fun stopSharing(item: Item, user: ShareUser) = drive.guard {
        api.post("/v3/item/shared/out/remove", JSONObject().put("uuid", item.uuid).put("receiverId", user.id))
        drive.invalidate(Listing.SharedOut)
    }

    /** Removes an item that another user shared with me from my list. */
    suspend fun leave(item: Item) = drive.guard {
        api.post("/v3/item/shared/in/remove", JSONObject().put("uuid", item.uuid))
        drive.invalidate(Listing.SharedIn)
    }

    private suspend fun shareTo(email: String, publicKey: String, items: List<Item>, root: String?) {
        parallel(items) { item ->
            val meta = item.metaJson()
            // RSA-OAEP-SHA512 with a 4096-bit key takes at most 382 bytes (F-27).
            if (meta.encodeToByteArray().size > 382) throw IOException("A name is too long to share")
            api.post(
                "/v3/item/share",
                JSONObject().put("uuid", item.uuid).put("parent", if (item.uuid == root) "none" else item.parent).put("email", email)
                    .put("type", if (item.folder) "folder" else "file").put("metadata", Keyring.encryptFor(publicKey, meta)),
            )
        }
    }

    private suspend fun parallel(items: List<Item>, call: suspend (Item) -> Unit) = coroutineScope {
        val gate = Semaphore(PARALLEL)
        items.map { item -> async { gate.withPermit { call(item) } } }.awaitAll()
    }

    private fun hasPassword(o: JSONObject): Boolean {
        val p: Any? = o.opt("password")
        return when (p) {
            is String -> p.isNotEmpty() && p != "empty" && p != "null" && p != EMPTY_HASH
            is Boolean -> p
            else -> false
        }
    }

    companion object {
        val EXPIRATIONS = listOf("never", "1h", "6h", "1d", "3d", "7d", "14d", "30d")
        private const val PARALLEL = 6

        /** What older clients send for "no password": the old hash of the text "empty" (research F-76). */
        private val EMPTY_HASH = app.filenza.crypto.Names.v2Hash("empty".encodeToByteArray())
    }
}
