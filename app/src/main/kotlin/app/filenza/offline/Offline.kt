package app.filenza.offline

import android.content.Context
import android.util.AtomicFile
import app.filenza.crypto.DataKey
import app.filenza.drive.ChunkSource
import app.filenza.drive.Item
import kotlinx.coroutines.Dispatchers
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
    private var loaded = false
    private var entries = LinkedHashMap<String, Item>()
    private val _uuids = MutableStateFlow<Set<String>>(emptySet())

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
        loaded = true
        _uuids.value = entries.keys.toSet()
    }

    override suspend fun encrypted(item: Item, index: Int): ByteArray? {
        if (item.uuid !in _uuids.value) return null
        val f = File(dir, "${item.uuid}/$index")
        return withContext(Dispatchers.IO) { if (f.isFile) f.readBytes() else null }
    }

    /**
     * Stores all chunks of [item]. [fetch] gives the encrypted chunk; the GCM check happens at each read. A
     * new version of the file (another key) replaces the old copy.
     */
    suspend fun add(item: Item, fetch: suspend (Int) -> ByteArray, progress: (Int) -> Unit) {
        require(!item.folder)
        val target = File(dir, item.uuid)
        withContext(Dispatchers.IO) {
            target.deleteRecursively()
            target.mkdirs()
        }
        try {
            for (i in 0 until item.chunks) {
                val data = fetch(i)
                withContext(Dispatchers.IO) {
                    val part = File(target, "$i.part")
                    part.writeBytes(data)
                    part.renameTo(File(target, "$i"))
                }
                progress(i + 1)
            }
        } catch (e: Throwable) {
            withContext(Dispatchers.IO) { target.deleteRecursively() }
            throw e
        }
        mutex.withLock {
            read()
            entries[item.uuid] = item
            save()
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

    /** After a logout: every file and the index. */
    fun clear() {
        dir.deleteRecursively()
        entries = LinkedHashMap()
        loaded = false
        _uuids.value = emptySet()
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
        _uuids.value = entries.keys.toSet()
    }
}
