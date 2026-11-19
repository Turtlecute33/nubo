package app.filenza.media

import android.media.MediaDataSource
import app.filenza.drive.Content
import app.filenza.drive.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Random-access reads of the plain content of one file, chunk by chunk, with the last [KEEP] chunks in
 * memory. No plain data goes to the disk. Used by the media player, the video thumbnailer and the proxy file
 * descriptors of "Open with" and the PDF viewer. [read] blocks: call it on a background thread.
 */
class ChunkReader(private val content: Content, val item: Item) {
    private val cache = object : LinkedHashMap<Int, ByteArray>(KEEP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>) = size > KEEP
    }

    val size: Long get() = item.size

    /** Set by [MediaDataSource.close] with no lock: a read that waits for the network must not block the closer. */
    @Volatile
    private var closed = false

    /** The first read error of [dataSource]. MediaMetadataRetriever hides it in its own error (review 46). */
    @Volatile
    var error: Exception? = null
        private set

    /** Copies up to [length] bytes from [position] into [out]; returns the count, or -1 at the end. */
    @Synchronized
    fun read(position: Long, out: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw java.io.IOException("Closed")
        if (position >= item.size) return -1
        var done = 0
        var pos = position
        while (done < length && pos < item.size) {
            val index = (pos / CHUNK).toInt()
            val chunk = cache[index] ?: runBlocking(Dispatchers.IO) { content.chunk(item, index) }.also { cache[index] = it }
            val inChunk = (pos - index.toLong() * CHUNK).toInt()
            if (inChunk >= chunk.size) break
            val n = minOf(length - done, chunk.size - inChunk)
            System.arraycopy(chunk, inChunk, out, offset + done, n)
            done += n
            pos += n
        }
        return if (done == 0) -1 else done
    }

    /** The reader as a [MediaDataSource] for MediaPlayer and MediaMetadataRetriever. */
    fun dataSource(): MediaDataSource = object : MediaDataSource() {
        // A network or decrypt error is an IOException for the player, not the end of the file.
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            try {
                read(position, buffer, offset, size)
            } catch (e: Exception) {
                if (error == null) error = e
                throw e as? java.io.IOException ?: java.io.IOException(e)
            }

        override fun getSize(): Long = item.size

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val CHUNK = 1024L * 1024
        const val KEEP = 6
    }
}
