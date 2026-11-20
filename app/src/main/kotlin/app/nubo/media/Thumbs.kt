package app.nubo.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.nubo.crypto.Bytes
import app.nubo.crypto.DataKey
import app.nubo.crypto.hex
import app.nubo.drive.Content
import app.nubo.drive.Item
import app.nubo.util.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Collections

/**
 * Thumbnails made on the phone (Filen stores none; research F-81): images are decoded at a small size,
 * videos give a frame through [ChunkReader] (only the chunks that the decoder reads). The small WebP goes to
 * the cache folder encrypted with the session's cache key, so that no picture is plain on the disk.
 * DESIGN §6.
 */
class Thumbs(context: Context, private val content: Content, private val cacheKey: () -> DataKey?) {
    private val dir = File(context.cacheDir, "thumbs")
    private val gate = Semaphore(2)
    private val failed = Collections.synchronizedSet(HashSet<String>())
    private val memory = object : LruCache<String, ImageBitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** True for files that can get a thumbnail. */
    fun supports(item: Item): Boolean = !item.folder && item.size > 0 && when (item.type) {
        FileType.IMAGE -> FileType.decodable(item.name) && item.size <= MAX_IMAGE
        FileType.VIDEO -> true
        else -> false
    }

    /** For the first composition of a row: no hash for items with no thumbnail (review 59). */
    fun peek(item: Item): ImageBitmap? = if (supports(item)) memory.get(key(item)) else null

    /**
     * The thumbnail of [item], from memory, the disk or made now; null when it cannot be made. Only new
     * thumbnails wait for the [gate]: a disk copy is small and shows at once (review 58). With no session (a
     * public link visitor) there is no cache key: the thumbnail stays in memory only (review 81).
     */
    suspend fun get(item: Item): ImageBitmap? {
        if (!supports(item)) return null
        val k = key(item)
        memory.get(k)?.let { return it }
        if (k in failed) return null
        val ck = cacheKey()
        if (ck != null) withContext(Dispatchers.IO) { fromDisk(ck, k) }?.let { return it.also { memory.put(k, it) } }
        return gate.withPermit {
            memory.get(k) ?: withContext(Dispatchers.IO) { load(item, k, ck) }?.also { memory.put(k, it) }
        }
    }

    fun clear() {
        memory.evictAll()
        failed.clear()
        dir.deleteRecursively()
    }

    private fun fromDisk(ck: DataKey, k: String): ImageBitmap? {
        val file = File(dir, k)
        if (!file.isFile) return null
        return runCatching { decode(ck.decryptChunk(file.readBytes())).asImageBitmap() }.getOrNull()
    }

    private suspend fun load(item: Item, k: String, ck: DataKey?): ImageBitmap? {
        // A network error or a cancel (the cell left the screen) can pass: only a file that does not decode
        // is marked as failed for this process. DecodeException is an IOException: catch it first (review 46).
        val bitmap = try {
            make(item)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: ImageDecoder.DecodeException) {
            null
        } catch (_: java.io.IOException) {
            return null
        } catch (_: Exception) {
            null
        }
        if (bitmap == null) {
            failed += k
            return null
        }
        if (ck != null) runCatching {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)
            dir.mkdirs()
            val tmp = File(dir, "$k.tmp")
            tmp.writeBytes(ck.encryptChunk(out.toByteArray()))
            tmp.renameTo(File(dir, k))
        }
        return bitmap.asImageBitmap()
    }

    private suspend fun make(item: Item): Bitmap? = when (item.type) {
        FileType.IMAGE -> {
            val bytes = content.bytes(item, MAX_IMAGE)
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
                val w = info.size.width.coerceAtLeast(1)
                val h = info.size.height.coerceAtLeast(1)
                // The short side fills the cell. The long side has its own limit: a 1 x 10^8 PNG compresses to a
                // few MB, and with the short side alone it would decode at full size (gigabytes, a crash).
                val scale = minOf(SIZE.toFloat() / minOf(w, h), MAX_SIDE.toFloat() / maxOf(w, h))
                if (scale < 1f) d.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        FileType.VIDEO -> {
            val reader = ChunkReader(content, item)
            val frame = runCatching {
                MediaMetadataRetriever().use { r ->
                    val source = reader.dataSource()
                    r.setDataSource(source)
                    r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE * 2, SIZE * 2)
                        .also { source.close() }
                }
            }
            // The retriever hides a failed read in its own error or a null frame: a read error (the network)
            // does not mark the file as failed (review 46).
            reader.error?.let { throw it }
            frame.getOrThrow()
        }
        else -> null
    }

    private fun decode(webp: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(webp))) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }

    /** A file name that tells nothing: a hash of the UUID and the key (a new version has a new key). */
    private fun key(item: Item): String = Bytes.sha1((item.uuid + item.key).encodeToByteArray()).hex()

    private companion object {
        const val SIZE = 320
        /** The long side of a decoded thumbnail at most: the bitmap stays under 1.7 MB for any image. */
        const val MAX_SIDE = SIZE * 4
        const val QUALITY = 70
        const val MAX_IMAGE = 25L * 1000 * 1000
        const val MEMORY_BYTES = 48 * 1024 * 1024
    }
}
