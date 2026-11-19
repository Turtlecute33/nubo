package app.nubo.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import app.nubo.app
import app.nubo.crypto.Bytes
import app.nubo.crypto.hex
import app.nubo.drive.Item
import app.nubo.util.FileType
import java.io.FileNotFoundException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives decrypted files to other apps ("Open with", "Send a copy"). The provider is NOT exported: an app can
 * read only a URI that Nubo granted to it in an intent. Each URI holds a random 128-bit token, kept only in
 * memory for [LIFETIME_MS] (and never after the process ends). The data streams through a proxy file
 * descriptor ([ProxyFd]): no plain copy goes to the disk. DESIGN §7.
 */
class FileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = entry(uri)?.let { mime(it) }

    /** Only the name and the size, the columns that receiving apps read to show the file. */
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val item = entry(uri) ?: return null
        val cols = (projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            .filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }.toTypedArray()
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { if (it == OpenableColumns.DISPLAY_NAME) item.name else item.size })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Read only")
        val item = entry(uri) ?: throw FileNotFoundException("Unknown or expired file")
        val context = context ?: throw FileNotFoundException("No context")
        val reader = ChunkReader(context.app.content, item)
        readers += reader
        return ProxyFd.open(context, reader)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
        throw UnsupportedOperationException()

    private class Entry(val item: Item, val at: Long)

    companion object {
        private const val LIFETIME_MS = 6 * 60 * 60_000L
        private val entries = ConcurrentHashMap<String, Entry>()

        /** The readers of the open descriptors, for [clear]. Weak: a closed descriptor lets its reader go. */
        private val readers: MutableSet<ChunkReader> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        /** A new content URI for [item]: `content://<package>.files/<token>/<name>`. */
        fun uriFor(context: Context, item: Item): Uri {
            require(!item.folder)
            prune()
            val token = Bytes.random(16).hex()
            entries[token] = Entry(item, SystemClock.elapsedRealtime())
            return Uri.Builder().scheme("content").authority("${context.packageName}.files")
                .appendPath(token).appendPath(item.name).build()
        }

        fun mime(item: Item): String = item.mime.takeIf { '/' in it } ?: FileType.mime(item.name)

        /**
         * Forgets every URI (at logout): apps that hold one can read no more. An app that has a descriptor open
         * gets an I/O error at its next read (review 67).
         */
        fun clear() {
            entries.clear()
            synchronized(readers) {
                // Closing the reader's data source stops the reader itself.
                readers.forEach { it.dataSource().close() }
                readers.clear()
            }
        }

        private fun entry(uri: Uri): Item? {
            val token = uri.pathSegments.firstOrNull() ?: return null
            val e = entries[token] ?: return null
            if (SystemClock.elapsedRealtime() - e.at > LIFETIME_MS) {
                entries.remove(token)
                return null
            }
            return e.item
        }

        private fun prune() {
            val now = SystemClock.elapsedRealtime()
            entries.entries.removeIf { now - it.value.at > LIFETIME_MS }
        }
    }
}
