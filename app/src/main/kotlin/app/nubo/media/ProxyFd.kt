package app.nubo.media

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants

/**
 * A read-only file descriptor whose reads come from a [ChunkReader] (StorageManager proxy, through the
 * kernel's app FUSE). The reader of the descriptor gets plain data; no plain copy goes to the disk. Each
 * descriptor has its own thread, because a read can wait for the network. The thread ends when the reader
 * closes the descriptor. DESIGN §7.
 */
object ProxyFd {
    /** [released] runs once, when the descriptor closes (or does not open). */
    fun open(context: Context, reader: ChunkReader, released: () -> Unit = {}): ParcelFileDescriptor {
        val thread = HandlerThread("nubo-proxy").apply { start() }
        val callback = object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = reader.size

            override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
                reader.read(offset, data, 0, size).coerceAtLeast(0)
            } catch (_: Exception) {
                // A network or decrypt error is an I/O error for the reader, not the end of the file.
                throw ErrnoException("onRead", OsConstants.EIO)
            }

            override fun onRelease() {
                thread.quitSafely()
                released()
            }
        }
        return try {
            context.getSystemService(StorageManager::class.java)
                .openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper))
        } catch (e: Exception) {
            thread.quitSafely()
            released()
            throw e
        }
    }
}
