package app.nubo.offline

import android.content.Context
import android.util.AtomicFile
import app.nubo.crypto.DataKey
import app.nubo.drive.ChunkSource
import app.nubo.drive.Content
import app.nubo.drive.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/**
 * Files kept on the phone (DESIGN §6). The chunks are stored as the server sends them: still encrypted with
 * the file key. The index (names and file keys) is encrypted with the session's cache key. So nothing on
 * the disk is plain, and opening an offline file is the same code as a download, with no network.
 */
class Offline(context: Context, private val cacheKey: () -> DataKey?) : ChunkSource {
    private val dir = File(context.noBackupFilesDir, "offline")
    private val index = AtomicFile(File(dir, "index.bin"))
    private val mutex = Mutex()
    private val adding = java.util.Collections.synchronizedSet(HashSet<String>())
    private var loaded = false
    private var entries = LinkedHashMap<String, Item>()
    private val _uuids = MutableStateFlow<Set<String>>(emptySet())

    /** The file key of each copy, by UUID, for [encrypted] with no lock. */
    @Volatile
    private var keys: Map<String, String> = emptyMap()

    /** The UUIDs of the offline files, for the badges in lists. */
    val uuids: StateFlow<Set<String>> = _uuids.asStateFlow()

    suspend fun items(): List<Item> = mutex.withLock {
        read()
        entries.values.toList()
    }

    /** Reads the index once (at the app start). */
    suspend fun load() = mutex.withLock { read() }

    private suspend fun read() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        val key = cacheKey() ?: return@withContext
        entries = runCatching {
            val array = JSONArray(key.decrypt(index.readFully().decodeToString()))
            (0 until array.length()).map { Item.fromJson(array.getJSONObject(it)) }.associateByTo(LinkedHashMap()) { it.uuid }
        }.getOrDefault(LinkedHashMap())
        // Downloads that a process death stopped. No add runs yet: each add reads the index first.
        dir.listFiles { f -> f.name.endsWith(TMP) }?.forEach { it.deleteRecursively() }
        loaded = true
        publish()
    }

    override suspend fun encrypted(item: Item, index: Int): ByteArray? {
        // Only the copy of this version: a new version of the file (the same UUID, another key) comes from the network.
        if (keys[item.uuid] != item.key) return null
        val f = File(dir, "${item.uuid}/$index")
        return withContext(Dispatchers.IO) { if (f.isFile) f.readBytes() else null }
    }

    /**
     * Stores all chunks of [item]. [fetch] gives the encrypted chunk. The whole file is checked like a download
     * (each chunk's GCM, the size, the BLAKE3 hash): a damaged or reordered chunk from the server is never kept,
     * where it would come before the good network copy at every read. A new version of the file (another key)
     * replaces the old copy. The chunks go to a temporary folder first, so that the old copy stays good until
     * the new one is complete. A file that is offline with this key, or that another add downloads now, is
     * skipped (review 44).
     */
    suspend fun add(item: Item, fetch: suspend (Int) -> ByteArray, progress: (Int) -> Unit) {
        require(!item.folder)
        if (!adding.add(item.uuid)) return
        try {
            if (mutex.withLock { read(); entries[item.uuid]?.key == item.key }) return
            val count = Content.chunkCount(item)
            val tmp = File(dir, item.uuid + TMP)
            withContext(Dispatchers.IO) {
                tmp.deleteRecursively()
                tmp.mkdirs()
            }
            try {
                val check = Content.Check(item)
                var done = 0
                // The same parallel window as a download; the checks and the writes go in chunk order.
                Content.inOrder(count, fetch) { data ->
                    check.update(Content.decrypt(item, data))
                    val i = done++
                    withContext(Dispatchers.IO) { File(tmp, "$i").writeBytes(data) }
                    progress(i + 1)
                }
                check.finish()
                mutex.withLock {
                    withContext(Dispatchers.IO) {
                        val target = File(dir, item.uuid)
                        target.deleteRecursively()
                        if (!tmp.renameTo(target)) throw java.io.IOException("Cannot write the offline copy")
                    }
                    entries[item.uuid] = item
                    save()
                }
            } catch (e: Throwable) {
                // NonCancellable: in a cancelled add, a plain withContext would throw before the cleanup (review 43).
                withContext(Dispatchers.IO + NonCancellable) { tmp.deleteRecursively() }
                throw e
            }
        } finally {
            adding.remove(item.uuid)
        }
    }

    suspend fun remove(uuid: String) = mutex.withLock {
        read()
        entries.remove(uuid)
        save()
        withContext(Dispatchers.IO) { File(dir, uuid).deleteRecursively() }
    }

    /** Bytes on the disk. */
    suspend fun size(): Long = withContext(Dispatchers.IO) { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    /**
     * After a logout: every file and the index. In the lock: a [read] that runs now would bring the old index
     * back after the wipe.
     */
    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
        entries = LinkedHashMap()
        loaded = false
        publish()
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        val key = cacheKey() ?: return@withContext
        dir.mkdirs()
        val blob = key.encrypt(JSONArray(entries.values.map { it.toJson() }).toString()).encodeToByteArray()
        val out = index.startWrite()
        try {
            out.write(blob)
            index.finishWrite(out)
        } catch (e: Exception) {
            index.failWrite(out)
            throw e
        }
        publish()
    }

    /** Shows [entries] to the readers with no lock: the badges and [encrypted]. */
    private fun publish() {
        keys = entries.mapValues { it.value.key }
        _uuids.value = keys.keys
    }

    private companion object {
        /** The suffix of the folder of a download in progress. */
        const val TMP = ".tmp"
    }
}
