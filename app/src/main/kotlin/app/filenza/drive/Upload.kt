package app.filenza.drive

import app.filenza.api.Api
import app.filenza.crypto.Bytes
import app.filenza.crypto.DataKey
import app.filenza.crypto.hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.digests.Blake3Digest
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** What to upload: a name, the times, and a way to open the plain data (once). */
class UploadSource(val name: String, val mime: String, val modified: Long, val created: Long, val open: () -> InputStream)

/**
 * Encrypts and uploads one file (research F-UPLOAD): a new random file key, 1 MiB chunks with their own nonce,
 * [PARALLEL] chunks on the way, then `upload/done` with the encrypted metadata. The BLAKE3 hash of the plain
 * data goes into the metadata, so that every client can check a download.
 */
class Uploader(private val drive: Drive) {
    private val api get() = drive.api

    suspend fun upload(parent: String, src: UploadSource, progress: (Long) -> Unit): Item = drive.guard { send(parent, src, progress) }

    private suspend fun send(parent: String, src: UploadSource, progress: (Long) -> Unit): Item = coroutineScope {
        val ring = drive.keys()
        val name = NameRules.clean(src.name)
        val uuid = UUID.randomUUID().toString()
        val fileKey = DataKey.newFileKey(ring.version)
        val uploadKey = Bytes.alnum(32)
        val hasher = Blake3Digest(256)
        val gate = Semaphore(PARALLEL)
        val sent = AtomicLong()
        var place: Pair<String, String>? = null
        var size = 0L
        var count = 0
        val jobs = ArrayList<kotlinx.coroutines.Deferred<Unit>>()
        withContext(Dispatchers.IO) { src.open() }.use { input ->
            while (true) {
                val buf = ByteArray(Api.CHUNK)
                val n = withContext(Dispatchers.IO) { input.readFully(buf) }
                if (n == 0) break
                hasher.update(buf, 0, n)
                size += n
                val index = count++
                gate.acquire() // reads stay at most PARALLEL chunks ahead of the network
                jobs += async(Dispatchers.Default) {
                    try {
                        val data = fileKey.encryptChunk(buf, n)
                        val where = api.uploadChunk(uuid, index, parent, uploadKey, data)
                        synchronized(jobs) { if (place == null) place = where }
                        progress(sent.addAndGet(n.toLong()))
                    } finally {
                        gate.release()
                    }
                }
                if (n < Api.CHUNK) break
            }
        }
        jobs.awaitAll()

        val hash = ByteArray(32).also { hasher.doFinal(it, 0) }.hex()
        val now = System.currentTimeMillis()
        val item = Item(
            uuid = uuid,
            parent = parent,
            name = name,
            folder = false,
            size = size,
            mime = src.mime,
            modified = src.modified.takeIf { it > 0 } ?: now,
            created = src.created.takeIf { it > 0 } ?: src.modified.takeIf { it > 0 } ?: now,
            uploaded = now,
            key = fileKey.text(ring.version),
            region = place?.first.orEmpty(),
            bucket = place?.second.orEmpty(),
            chunks = count,
            version = ring.version,
            hash = hash,
        )
        val nameKey = Ops.fieldKey(fileKey, ring.version)
        val body = JSONObject()
            .put("uuid", uuid)
            .put("name", nameKey.encrypt(item.name))
            .put("nameHashed", ring.hashName(item.name))
            .put("size", nameKey.encrypt(size.toString()))
            .put("parent", parent)
            .put("mime", nameKey.encrypt(item.mime))
            .put("metadata", ring.encryptMeta(item.metaJson()))
            .put("version", ring.version)
        drive.lock.write {
            try {
                if (size == 0L) {
                    api.post("/v3/upload/empty", body)
                } else {
                    if (place == null) throw IOException("Upload failed")
                    api.post("/v3/upload/done", body.put("chunks", count).put("rm", Bytes.alnum(32)).put("uploadKey", uploadKey))
                }
            } catch (e: app.filenza.api.ApiException) {
                // A retry after a lost answer can get an error for a call that worked: the file is there then (review 29).
                val there = runCatching {
                    api.post("/v3/file/exists", JSONObject().put("nameHashed", ring.hashName(name)).put("parent", parent)).optString("uuid") == uuid
                }.getOrDefault(false)
                if (!there) throw e
            }
        }
        drive.invalidate(Listing.Dir(parent), Listing.Recents)
        drive.sharing.propagateAfter(parent, listOf(item))
        item
    }

    companion object {
        /** Chunks in flight for one upload. */
        const val PARALLEL = 3

        /** Reads until [buf] is full or the stream ends; returns the count. */
        private fun InputStream.readFully(buf: ByteArray): Int {
            var n = 0
            while (n < buf.size) {
                val r = read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            return n
        }
    }
}
