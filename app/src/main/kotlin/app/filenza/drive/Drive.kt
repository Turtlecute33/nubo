package app.filenza.drive

import app.filenza.api.Api
import app.filenza.api.ApiException
import app.filenza.crypto.Keyring
import app.filenza.data.Account
import app.filenza.offline.Offline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Storage use and plan of the account. */
data class Usage(val used: Long, val max: Long, val premium: Boolean)

/**
 * Lists and changes the drive (research F-58..F-79). Listings are decrypted off the main thread and kept in a
 * small memory cache, so that Back and tab changes show at once. Every write goes through [WriteLock] and
 * then tells the screens which listings changed. Writes live in [Ops]; shares and links in [Sharing].
 */
class Drive(
    internal val api: Api,
    private val account: Account,
    private val offline: Offline,
    internal val scope: CoroutineScope,
    /** Shows a short warning to the user (the snackbar). */
    internal val warn: (String) -> Unit = {},
) {
    internal val lock = WriteLock(api, scope)
    private val cache = object : LinkedHashMap<Listing, List<Item>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Listing, List<Item>>) = size > MAX_CACHED
    }
    private val _changed = MutableSharedFlow<Listing?>(extraBufferCapacity = 16)

    /** A listing whose content changed; null means all of them. */
    val changed: SharedFlow<Listing?> = _changed

    val ops by lazy { Ops(this) }
    val sharing by lazy { Sharing(this) }

    internal fun keys(): Keyring = account.keys()

    val root: String get() = account.session?.baseFolder ?: throw ApiException("api_key_not_found", 401)

    fun cached(listing: Listing): List<Item>? = synchronized(cache) { cache[listing] }

    /** The whole own tree of the last search, while nothing changes. Memory only; gone at logout. */
    @Volatile
    var searchTree: List<Item>? = null

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

    /** Every file and folder under [folder], with no order (research F-65). For search and photos. */
    suspend fun tree(folder: String): List<Item> = guard {
        val data = api.post("/v3/dir/download", JSONObject().put("uuid", folder).put("skipCache", false))
        val ring = keys()
        withContext(Dispatchers.Default) {
            val files = data.optJSONArray("files").objects().mapNotNull { Decode.file(it, Source.Own, ring::decryptMeta) }
            val dirs = data.optJSONArray("folders").objects().filter { it.optString("parent") != "base" }
                .mapNotNull { Decode.folder(it, Source.Own, "name", ring::decryptMeta) }
            dirs + files
        }
    }

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
        searchTree = null
        synchronized(cache) {
            if (listings.any { it == null }) cache.clear() else listings.forEach { cache.remove(it) }
        }
        // A full buffer (a slow screen, many writes) must not lose a change: the emit then waits (review 76).
        listings.forEach { if (!_changed.tryEmit(it)) scope.launch { _changed.emit(it) } }
    }

    fun clear() {
        searchTree = null
        synchronized(cache) { cache.clear() }
    }

    private suspend fun content(uuid: String): List<Item> {
        val data = api.post("/v3/dir/content", JSONObject().put("uuid", uuid))
        val ring = keys()
        return withContext(Dispatchers.Default) {
            data.optJSONArray("folders").objects().mapNotNull { Decode.folder(it, Source.Own, "name", ring::decryptMeta) } +
                data.optJSONArray("uploads").objects().mapNotNull { Decode.file(it, Source.Own, ring::decryptMeta) }
        }
    }

    /** [owner]: the sharer of the root item; rows inside a shared folder do not name it. */
    private suspend fun sharedIn(body: JSONObject, owner: String): List<Item> {
        val data = api.post("/v3/shared/in", body)
        val ring = keys()
        val decrypt = { text: String -> shared(ring, text) }
        return withContext(Dispatchers.Default) {
            data.optJSONArray("folders").objects().mapNotNull { o ->
                Decode.folder(o, Source.SharedIn(o.optString("sharerEmail").ifEmpty { owner }), "metadata", decrypt)
            } + data.optJSONArray("uploads").objects().mapNotNull { o ->
                Decode.file(o, Source.SharedIn(o.optString("sharerEmail").ifEmpty { owner }), decrypt)
            }
        }
    }

    /** The server lists each item once for each receiver: one row for each item, with all receivers. */
    private suspend fun sharedOut(): List<Item> {
        val data = api.post("/v3/shared/out", JSONObject().put("uuid", "shared-out").put("receiverId", 0))
        val ring = keys()
        return withContext(Dispatchers.Default) {
            fun group(list: List<JSONObject>, decode: (JSONObject, Source) -> Item?) = list.groupBy { it.optString("uuid") }.values.mapNotNull { rows ->
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
            data.optJSONArray("folders").objects().mapNotNull { Decode.folder(it, source, "metadata", key::decrypt) } +
                data.optJSONArray("files").objects().mapNotNull { Decode.file(it, source, key::decrypt) }
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

        fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        /**
         * Metadata that another user encrypted for this account with RSA. An old sharer app wrote Latin-1 by
         * mistake (research F-78): text with replacement characters is read again as Latin-1.
         */
        fun shared(ring: Keyring, text: String): String {
            val bytes = ring.decryptSharedBytes(text)
            val utf8 = bytes.decodeToString()
            return if ('�' in utf8) String(bytes, Charsets.ISO_8859_1) else utf8
        }
    }
}
