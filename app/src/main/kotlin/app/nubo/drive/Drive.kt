package app.nubo.drive

import android.os.SystemClock
import app.nubo.api.Api
import app.nubo.api.ApiException
import app.nubo.api.optText
import app.nubo.crypto.Keyring
import app.nubo.data.Account
import app.nubo.offline.Offline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Storage use and plan of the account. */
data class Usage(val used: Long, val max: Long, val premium: Boolean)

/**
 * Lists and changes the drive (research F-58..F-79). Listings are decrypted on all cores and kept in a small memory
 * cache, so that Back and tab changes show at once; whole-tree reads stay in [Trees]. Every write goes through
 * [WriteLock] and then tells the screens which listings changed. Writes live in [Ops]; shares and links in [Sharing].
 */
class Drive(
    internal val api: Api,
    private val account: Account,
    private val offline: Offline,
    internal val scope: CoroutineScope,
    /** The folder of the encrypted tree copies ([Trees]), in the cache folder. */
    treeDir: File,
    /** Shows a short warning to the user (the snackbar). */
    internal val warn: (String) -> Unit = {},
) {
    internal val lock = WriteLock(api, scope)
    private val cache = object : LinkedHashMap<Listing, List<Item>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Listing, List<Item>>) = size > MAX_CACHED
    }
    private val _changed = MutableSharedFlow<Listing?>(extraBufferCapacity = 16)
    private val trees = Trees(treeDir) { account.session?.cacheKey }

    /** When a write of this app last changed a folder (elapsed ms). Older tree reads are not fresh. */
    @Volatile
    private var treeChangedAt = 0L

    /** A listing whose content changed; null means all of them. */
    val changed: SharedFlow<Listing?> = _changed

    val ops by lazy { Ops(this) }
    val sharing by lazy { Sharing(this) }

    internal fun keys(): Keyring = account.keys()

    val root: String get() = account.session?.baseFolder ?: throw ApiException("api_key_not_found", 401)

    fun cached(listing: Listing): List<Item>? = synchronized(cache) { cache[listing] }

    /**
     * The last tree read of [folder] (search: the root; Photos: the photo folder): from memory, from the disk, or the
     * part of the root read. Null when there is none. Show it at once; read again when it is not [fresh].
     */
    suspend fun cachedTree(folder: String): Tree? = trees.get(folder, root)

    /** Reads the tree of [folder] from the server and keeps it for [cachedTree]. */
    suspend fun readTree(folder: String): Tree {
        val at = SystemClock.elapsedRealtime()
        return Tree(folder, tree(folder), at).also { trees.put(it, root, scope) }
    }

    /**
     * True when [tree] is younger than [maxAgeMs] and no write of this app changed a folder after its read. Other
     * devices can change the drive too, so a screen reads again after [maxAgeMs].
     */
    fun fresh(tree: Tree, maxAgeMs: Long): Boolean =
        tree.at > treeChangedAt && SystemClock.elapsedRealtime() - tree.at < maxAgeMs

    /** Reads [listing] from the server (or the phone for [Listing.Offline]) and caches it. */
    suspend fun list(listing: Listing): List<Item> = guard {
        val items = when (listing) {
            is Listing.Dir -> content(listing.uuid)
            Listing.Recents -> content("recents")
            Listing.Favorites -> content("favorites")
            Listing.Trash -> content("trash")
            Listing.Links -> content("links")
            Listing.SharedIn -> sharedIn(JSONObject().put("uuid", "shared-in"), "")
            is Listing.SharedDir -> sharedIn(JSONObject().put("uuid", listing.uuid), listing.owner)
            Listing.SharedOut -> sharedOut()
            Listing.Offline -> offline.items()
            is Listing.LinkDir -> linkContent(listing)
        }
        synchronized(cache) { cache[listing] = items }
        items
    }

    /**
     * Every file and folder under [folder], folders first (research F-65). For search, Photos ([readTree]) and folder
     * downloads. Streamed: a large drive gives more than any answer limit (review R-65); only the items stay in memory.
     */
    suspend fun tree(folder: String): List<Item> = guard {
        val ring = keys()
        streamed("/v3/dir/download", JSONObject().put("uuid", folder).put("skipCache", false), mapOf(
            // The tree also lists [folder] itself, with the parent "base".
            "folders" to { o -> if (o.optString("parent") == "base") null else Decode.folder(o, Source.Own, "name", ring::decryptMeta) },
            "files" to { o -> Decode.file(o, Source.Own, ring::decryptMeta) },
        ))
    }

    /**
     * POST with an answer that [Decode.stream] reads while it comes in, on all cores (review P-01). The stream reader
     * blocks its IO thread, so the decode waits there for its workers.
     */
    private suspend fun streamed(path: String, body: JSONObject, sections: Map<String, (JSONObject) -> Item?>): List<Item> =
        api.postLarge(path, body) { r -> runBlocking { Decode.stream(r, sections) } }

    suspend fun usage(): Usage = guard {
        val o = api.get("/v3/user/info")
        Usage(o.optLong("storageUsed"), o.optLong("maxStorage"), o.optInt("isPremium") == 1)
    }

    /** One own item by UUID, for example after a restore moved it (research F-63, F-65). */
    suspend fun item(uuid: String, folder: Boolean): Item? = guard {
        val ring = keys()
        if (folder) {
            val o = api.post("/v3/dir", JSONObject().put("uuid", uuid))
            Decode.folder(o, Source.Own, "nameEncrypted", ring::decryptMeta)
        } else {
            val o = api.post("/v3/file", JSONObject().put("uuid", uuid))
            Decode.file(o, Source.Own, ring::decryptMeta)
        }
    }

    /** Tells the screens that [listings] changed, and drops them from the cache. */
    fun invalidate(vararg listings: Listing?) {
        if (listings.any(::changesTree)) treeChangedAt = SystemClock.elapsedRealtime()
        synchronized(cache) {
            if (listings.any { it == null }) cache.clear() else listings.forEach { cache.remove(it) }
        }
        // A full buffer (a slow screen, many writes) must not lose a change: the emit then waits (review 76).
        listings.forEach { if (!_changed.tryEmit(it)) scope.launch { _changed.emit(it) } }
    }

    fun clear() {
        trees.clear()
        synchronized(cache) { cache.clear() }
    }

    private suspend fun content(uuid: String): List<Item> {
        val ring = keys()
        return streamed("/v3/dir/content", JSONObject().put("uuid", uuid), mapOf(
            "folders" to { o -> Decode.folder(o, Source.Own, "name", ring::decryptMeta) },
            "uploads" to { o -> Decode.file(o, Source.Own, ring::decryptMeta) },
        ))
    }

    /** [owner]: the sharer of the root item; rows inside a shared folder do not name it. */
    private suspend fun sharedIn(body: JSONObject, owner: String): List<Item> {
        val data = api.post("/v3/shared/in", body)
        val ring = keys()
        val decrypt = { text: String -> shared(ring, text) }
        return withContext(Dispatchers.Default) {
            Decode.all(data.optJSONArray("folders").objects()) { o ->
                Decode.folder(o, Source.SharedIn(o.optText("sharerEmail").ifEmpty { owner }), "metadata", decrypt)
            } + Decode.all(data.optJSONArray("uploads").objects()) { o ->
                Decode.file(o, Source.SharedIn(o.optText("sharerEmail").ifEmpty { owner }), decrypt)
            }
        }
    }

    /** The server lists each item once for each receiver: one row for each item, with all receivers. */
    private suspend fun sharedOut(): List<Item> {
        val data = api.post("/v3/shared/out", JSONObject().put("uuid", "shared-out").put("receiverId", 0))
        val ring = keys()
        return withContext(Dispatchers.Default) {
            suspend fun group(list: List<JSONObject>, decode: (JSONObject, Source) -> Item?) = Decode.all(list.groupBy { it.optString("uuid") }.values.toList()) { rows ->
                decode(rows.first(), Source.SharedOut(rows.map { it.optString("receiverEmail") }.distinct().joinToString(", ")))
            }
            group(data.optJSONArray("folders").objects()) { o, s -> Decode.folder(o, s, "metadata", ring::decryptMeta) } +
                group(data.optJSONArray("uploads").objects()) { o, s -> Decode.file(o, s, ring::decryptMeta) }
        }
    }

    private suspend fun linkContent(listing: Listing.LinkDir): List<Item> {
        val link = listing.link
        val data = api.postAnon(
            "/v3/dir/link/content",
            JSONObject().put("uuid", link.uuid).put("password", link.password).put("parent", listing.uuid),
        )
        val key = Links.metaKey(link.key)
        val source = Source.Link(link.uuid)
        return withContext(Dispatchers.Default) {
            Decode.all(data.optJSONArray("folders").objects()) { Decode.folder(it, source, "metadata", key::decrypt) } +
                Decode.all(data.optJSONArray("files").objects()) { Decode.file(it, source, key::decrypt) }
        }
    }

    /** A server answer that says the session ended logs out the app. */
    internal suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.sessionEnded) scope.launch { account.ended() }
        throw e
    }

    internal companion object {
        const val MAX_CACHED = 48

        /** Only a folder change (or a change of all) can change a tree; link, share and offline changes cannot. */
        fun changesTree(change: Listing?): Boolean = change == null || change is Listing.Dir

        fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        /**
         * Metadata that another user encrypted for this account with RSA. An old sharer app wrote Latin-1 by
         * mistake (research F-78): bytes that are not valid UTF-8 are read as Latin-1. Valid UTF-8 stays as it
         * is, also a name with a real U+FFFD in it.
         */
        fun shared(ring: Keyring, text: String): String {
            val bytes = ring.decryptSharedBytes(text)
            return try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                String(bytes, Charsets.ISO_8859_1)
            }
        }
    }
}
