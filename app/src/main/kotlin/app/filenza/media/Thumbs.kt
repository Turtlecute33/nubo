package app.filenza.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.filenza.crypto.Bytes
import app.filenza.crypto.DataKey
import app.filenza.drive.Content
import app.filenza.drive.Item
import app.filenza.util.FileType
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
    private val gate = Semaphore(3)
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

    fun peek(item: Item): ImageBitmap? = memory.get(key(item))

    /** The thumbnail of [item], from memory, the disk or made now; null when it cannot be made. */
    suspend fun get(item: Item): ImageBitmap? {
        if (!supports(item)) return null
        val k = key(item)
        memory.get(k)?.let { return it }
        if (k in failed) return null
        return gate.withPermit {
            memory.get(k) ?: withContext(Dispatchers.IO) { load(item, k) }?.also { memory.put(k, it) }
        }
    }

    fun clear() {
        memory.evictAll()
        failed.clear()
        dir.deleteRecursively()
    }

    private suspend fun load(item: Item, k: String): ImageBitmap? {
        val ck = cacheKey() ?: return null
        val file = File(dir, k)
        if (file.isFile) {
            runCatching { decode(ck.decryptChunk(file.readBytes())) }.getOrNull()?.let { return it.asImageBitmap() }
        }
        val bitmap = runCatching { make(item) }.getOrNull()
        if (bitmap == null) {
            failed += k
            return null
        }
        runCatching {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)
            dir.mkdirs()
            val tmp = File(dir, "$k.tmp")
            tmp.writeBytes(ck.encryptChunk(out.toByteArray()))
            tmp.renameTo(file)
        }
        return bitmap.asImageBitmap()
    }

    private suspend fun make(item: Item): Bitmap? = when (item.type) {
        FileType.IMAGE -> {
            val bytes = content.bytes(item, MAX_IMAGE)
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
                val s = info.size
                val scale = SIZE.toFloat() / maxOf(1, minOf(s.width, s.height))
                if (scale < 1f) d.setTargetSize(maxOf(1, (s.width * scale).toInt()), maxOf(1, (s.height * scale).toInt()))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        FileType.VIDEO -> MediaMetadataRetriever().use { r ->
            val source = ChunkReader(content, item).dataSource()
            r.setDataSource(source)
            r.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, SIZE * 2, SIZE * 2)
                .also { source.close() }
        }
        else -> null
    }

    private fun decode(webp: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(webp))) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }

    /** A file name that tells nothing: a hash of the UUID and the key (a new version has a new key). */
    private fun key(item: Item): String = Bytes.sha1((item.uuid + item.key).encodeToByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val SIZE = 320
        const val QUALITY = 70
        const val MAX_IMAGE = 40L * 1000 * 1000
        const val MEMORY_BYTES = 48 * 1024 * 1024
    }
}
