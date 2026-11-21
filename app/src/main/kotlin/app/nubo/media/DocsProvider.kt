package app.nubo.media

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import app.nubo.NuboApp
import app.nubo.R
import app.nubo.app
import app.nubo.data.AccountState
import app.nubo.drive.Item
import app.nubo.drive.Listing
import app.nubo.ui.components.userText
import app.nubo.ui.lock.AppLock
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/**
 * The Nubo entry of the system file picker: other apps can open files of the own drive. Read only. The data
 * streams through a proxy file descriptor, as for "Open with" ([FileProvider.openPlain]): no plain copy goes
 * to the disk. Only the system picker (MANAGE_DOCUMENTS) can browse; another app reads only the files that the
 * user picked for it. There is no entry with no session, or while the app lock is on and the app is locked;
 * each call checks this again. The calls come on binder threads, never on the main thread: they can wait for
 * the network.
 */
class DocsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLUMNS)
        val app = context?.app ?: return c
        if (!ready(app) || !open(app)) return c
        val root = app.account.session?.baseFolder ?: return c
        // No email or account name: the picker shows the root to anyone who holds the phone.
        c.newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT_ID)
            .add(Root.COLUMN_DOCUMENT_ID, DocId.folder(root))
            .add(Root.COLUMN_TITLE, "Nubo")
            .add(Root.COLUMN_SUMMARY, "Filen drive")
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            .add(Root.COLUMN_FLAGS, 0)
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val app = check()
        return MatrixCursor(projection ?: DOC_COLUMNS).also { row(app, it, documentId, item(app, documentId)) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val app = check()
        val parent = DocId.parse(parentDocumentId)?.takeIf { it.folder } ?: throw FileNotFoundException("Not a folder")
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        val list = try {
            runBlocking { app.drive.list(Listing.Dir(parent.uuid)) }
        } catch (e: Exception) {
            // The picker shows the text; it holds no name, key or path.
            c.extras = Bundle().apply { putString(DocumentsContract.EXTRA_ERROR, userText(e)) }
            return c
        }
        synchronized(items) { list.forEach { items[it.uuid] = it } }
        list.forEach { row(app, c, DocId.of(it), it) }
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (!DocId.readOnly(mode)) throw SecurityException("Read only")
        val app = check()
        val item = item(app, documentId)?.takeIf { !it.folder } ?: throw FileNotFoundException("Not a file")
        // The check again in the lock of FileProvider.clear: a logout or a lock during the network read wins.
        return FileProvider.openPlain(app) { item.takeIf { open(app) } }
    }

    override fun openDocumentThumbnail(documentId: String, sizeHint: Point?, signal: CancellationSignal?): AssetFileDescriptor {
        val app = check()
        val item = item(app, documentId)?.takeIf { app.thumbs.supports(it) } ?: throw FileNotFoundException("No thumbnail")
        // The encrypted thumbnail cache of the app; the picker gets the small image through a pipe, not a file.
        val bitmap = runBlocking { app.thumbs.get(item) }?.asAndroidBitmap() ?: throw FileNotFoundException("No thumbnail")
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        val (read, write) = ParcelFileDescriptor.createPipe()
        // A thread: the pipe buffer can be smaller than the image, and the picker reads only after this returns.
        Thread({ ParcelFileDescriptor.AutoCloseOutputStream(write).use { runCatching { it.write(bytes) } } }, "nubo-thumb").start()
        return AssetFileDescriptor(read, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    /** The app, when the picker may see the drive now; else the same error as for an unknown document. */
    private fun check(): NuboApp {
        val app = context?.app ?: throw FileNotFoundException("No context")
        if (!ready(app) || !open(app)) throw FileNotFoundException("Nubo is locked or logged out")
        return app
    }

    /**
     * The item of [id]; null for the drive root. A file that another app kept from an earlier process is not in
     * [items]: the app reads it again by its UUID. An item that is gone (deleted, other account) is not found.
     */
    private fun item(app: NuboApp, id: String): Item? {
        val doc = DocId.parse(id) ?: throw FileNotFoundException("Unknown document")
        if (doc.folder && doc.uuid == app.account.session?.baseFolder) return null
        synchronized(items) { items[doc.uuid] }?.takeIf { it.folder == doc.folder }?.let { return it }
        val found = try {
            runBlocking { app.drive.item(doc.uuid, doc.folder) }
        } catch (e: Exception) {
            throw FileNotFoundException(userText(e))
        } ?: throw FileNotFoundException("Unknown document")
        synchronized(items) { items[found.uuid] = found }
        return found
    }

    private fun row(app: NuboApp, c: MatrixCursor, id: String, item: Item?) {
        val r = c.newRow().add(Document.COLUMN_DOCUMENT_ID, id)
        if (item == null) {
            r.add(Document.COLUMN_DISPLAY_NAME, "Drive").add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR).add(Document.COLUMN_FLAGS, 0)
            return
        }
        r.add(Document.COLUMN_DISPLAY_NAME, item.name)
            .add(Document.COLUMN_MIME_TYPE, if (item.folder) Document.MIME_TYPE_DIR else FileProvider.mime(item))
            .add(Document.COLUMN_SIZE, if (item.folder) null else item.size)
            .add(Document.COLUMN_LAST_MODIFIED, item.modified.takeIf { it > 0 })
            .add(Document.COLUMN_FLAGS, if (app.thumbs.supports(item)) Document.FLAG_SUPPORTS_THUMBNAIL else 0)
    }

    companion object {
        private const val ROOT_ID = "drive"

        /** How long a call that started the process waits for the settings and the session. */
        private const val WAIT_MS = 10_000L

        /** Items that the picker listed, by UUID. Memory only; [clear] at logout. */
        private const val MAX_ITEMS = 5_000
        private val items = object : LinkedHashMap<String, Item>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Item>) = size > MAX_ITEMS
        }

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_ICON, Root.COLUMN_FLAGS,
        )
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        fun authority(context: Context) = "${context.packageName}.documents"

        /** A call can start the process: wait until the settings and the session are read. */
        private fun ready(app: NuboApp): Boolean =
            app.ready.value || runBlocking { withTimeoutOrNull(WAIT_MS) { app.ready.first { it } } } != null

        /** True when the picker may show the drive: a session, and no app lock that is locked now. */
        private fun open(app: NuboApp): Boolean = app.account.session != null && !(app.prefs.value.appLock && AppLock.locked.value)

        /** Tells the picker to read the roots again after a login, a logout, a lock or an unlock. Runs for ever. */
        suspend fun follow(app: NuboApp) {
            app.ready.first { it }
            combine(app.account.state, app.prefs.state.map { it.appLock }, AppLock.locked) { s, lock, locked ->
                s is AccountState.LoggedIn && !(lock && locked)
            }.distinctUntilChanged().collect {
                app.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(app)), null)
            }
        }

        /** Forgets the listed items (at logout). */
        fun clear() = synchronized(items) { items.clear() }
    }
}

/** Document IDs: "d:<uuid>" for a folder, "f:<uuid>" for a file, so that a call knows which API reads it. */
internal object DocId {
    class Doc(val folder: Boolean, val uuid: String)

    fun folder(uuid: String) = "d:$uuid"

    fun of(item: Item) = (if (item.folder) "d:" else "f:") + item.uuid

    fun parse(id: String): Doc? {
        val folder = when {
            id.startsWith("d:") -> true
            id.startsWith("f:") -> false
            else -> return null
        }
        return id.substring(2).takeIf { UUID.matches(it) }?.let { Doc(folder, it) }
    }

    /** Only plain read: no write, append, truncate or read-write mode. */
    fun readOnly(mode: String) = mode == "r"

    private val UUID = Regex("[0-9a-fA-F-]{1,64}")
}
