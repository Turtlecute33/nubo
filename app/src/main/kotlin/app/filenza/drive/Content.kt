package app.filenza.drive

import app.filenza.api.Api
import app.filenza.crypto.MetaV1
import app.filenza.crypto.hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.digests.Blake3Digest
import java.io.IOException
import java.io.OutputStream
import java.security.GeneralSecurityException

/** Where encrypted chunks come from: the offline store first, then the network. */
fun interface ChunkSource {
    /** The encrypted chunk [index] of [item], or null when this source does not have it. */
    suspend fun encrypted(item: Item, index: Int): ByteArray?
}

/**
 * Reads the plain content of files (research F-DATA): 1 MiB chunks, each AES-256-GCM with its own nonce, so a
 * reader can start at any chunk. GCM checks each chunk; a full read also checks the BLAKE3 hash of the file
 * when the uploader wrote one. Decrypted data stays in memory or goes to the caller's stream.
 */
class Content(private val api: Api, private val local: ChunkSource) {
    /** One decrypted chunk. */
    suspend fun chunk(item: Item, index: Int): ByteArray {
        require(!item.folder && index in 0 until item.chunks)
        val data = local.encrypted(item, index) ?: api.downloadChunk(item.region, item.bucket, item.uuid, index)
        return withContext(Dispatchers.Default) {
            if (item.version == 1) MetaV1.decryptChunk(item.dataKey().bytes, data) else item.dataKey().decryptChunk(data)
        }
    }

    /** The whole file in memory; for viewers of small files. Throws when the file is larger than [max]. */
    suspend fun bytes(item: Item, max: Long): ByteArray {
        if (item.size > max) throw IOException("File too large to open here")
        // One array of the final size: no second copy of a large image in memory.
        val out = ByteArray(item.size.toInt())
        var at = 0
        write(item, object : java.io.OutputStream() {
            override fun write(b: Int) = throw UnsupportedOperationException()
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (at + len > out.size) throw IOException("The file size does not match")
                System.arraycopy(b, off, out, at, len)
                at += len
            }
        }) { }
        return out
    }

    /**
     * Writes the plain file to [out] in order, with [PARALLEL] chunks on the way at a time. [progress] gets the
     * bytes done. Throws [GeneralSecurityException] when a chunk or the whole-file hash does not match.
     */
    suspend fun write(item: Item, out: OutputStream, progress: (Long) -> Unit) = coroutineScope {
        val hasher = if (item.hash.isNotEmpty()) Blake3Digest(256) else null
        var done = 0L
        var next = 0
        val window = ArrayDeque<kotlinx.coroutines.Deferred<ByteArray>>()
        while (next < item.chunks || window.isNotEmpty()) {
            while (next < item.chunks && window.size < PARALLEL) {
                val i = next++
                window.addLast(async { chunk(item, i) })
            }
            val plain = window.removeFirst().await()
            withContext(Dispatchers.IO) { out.write(plain) }
            hasher?.update(plain, 0, plain.size)
            done += plain.size
            progress(done)
        }
        if (done != item.size) throw IOException("The file size does not match")
        if (hasher != null) {
            val sum = ByteArray(32).also { hasher.doFinal(it, 0) }.hex()
            if (!sum.equals(item.hash, ignoreCase = true)) throw GeneralSecurityException("The file hash does not match")
        }
    }

    companion object {
        /** Chunks in flight for one file: enough to fill a fast link, little memory (about 4 MiB). */
        const val PARALLEL = 4
    }
}
