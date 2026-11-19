package app.filenza.transfer

import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.Immutable
import app.filenza.FilenzaApp
import app.filenza.drive.Item
import app.filenza.drive.Listing
import app.filenza.drive.UploadSource
import app.filenza.ui.components.userText
import app.filenza.util.FileType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

@Immutable
data class Transfer(
    val id: Long,
    val kind: Kind,
    val name: String,
    val size: Long,
    val done: Long = 0,
    val state: State = State.QUEUED,
    val error: String? = null,
    val speed: Long = 0,
) {
    enum class Kind { UPLOAD, DOWNLOAD, OFFLINE }
    enum class State { QUEUED, RUNNING, DONE, FAILED, CANCELED }

    val active: Boolean get() = state == State.QUEUED || state == State.RUNNING
    val progress: Float get() = if (size <= 0) (if (state == State.DONE) 1f else 0f) else (done.toFloat() / size).coerceIn(0f, 1f)
}

/** The sum of the running transfers, for the floating pill and the notification. */
@Immutable
data class Summary(val active: Int, val uploads: Int, val done: Long, val size: Long, val speed: Long) {
    val progress: Float get() = if (size <= 0) 0f else (done.toFloat() / size).coerceIn(0f, 1f)
}

/**
 * The queue of uploads, downloads and offline copies, [PARALLEL] files at a time. Tasks live in memory: a
 * killed process forgets them. [TransferJob] keeps the process running while the queue is not empty.
 * Downloads go to Download/Filenza through MediaStore: no storage permission. DESIGN §6.
 */
class Transfers(private val app: FilenzaApp) {
    private val next = AtomicLong(1)
    private val gate = Semaphore(PARALLEL)
    private val jobs = HashMap<Long, Job>()
    private val retries = HashMap<Long, () -> Unit>()
    private val _list = MutableStateFlow<List<Transfer>>(emptyList())
    val list: StateFlow<List<Transfer>> = _list.asStateFlow()

    fun summary(list: List<Transfer>): Summary {
        val running = list.filter { it.active }
        return Summary(running.size, running.count { it.kind == Transfer.Kind.UPLOAD }, running.sumOf { it.done }, running.sumOf { it.size }, running.sumOf { it.speed })
    }

    /** Uploads content URIs that the user picked or shared into the folder [parent]. */
    fun upload(parent: String, uris: List<Uri>) {
        val resolver = app.contentResolver
        for (uri in uris) {
            val meta = runCatching { describe(uri) }.getOrNull()
            val name = meta?.name ?: "File"
            val src = UploadSource(name, resolver.getType(uri) ?: FileType.mime(name), meta?.modified ?: 0, 0) {
                resolver.openInputStream(uri) ?: throw IOException("The file cannot be read")
            }
            upload(parent, src, meta?.size ?: -1)
        }
    }

    /** Queues one upload. Returns its task ID. */
    fun upload(parent: String, src: UploadSource, size: Long, onDone: (suspend (Item) -> Unit)? = null): Long =
        start(Transfer.Kind.UPLOAD, src.name, size) { report ->
            val item = app.uploader.upload(parent, src) { report(it) }
            onDone?.invoke(item)
        }

    /** Saves files (and the files inside folders) to Download/Filenza, with the folder structure. */
    fun download(items: List<Item>) {
        for (item in items) {
            if (!item.folder) {
                save(item, "")
                continue
            }
            app.scope.launch {
                try {
                    val tree = app.drive.tree(item.uuid)
                    val folders = tree.filter { it.folder }.associateBy { it.uuid } + (item.uuid to item)
                    for (file in tree.filter { !it.folder }) save(file, path(file.parent, folders, item.uuid, item.name))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.messenger.show(userText(e))
                }
            }
        }
    }

    /** Keeps files on the phone (encrypted chunks, see [app.filenza.offline.Offline]). */
    fun keepOffline(items: List<Item>) {
        for (item in items.filter { !it.folder }) {
            start(Transfer.Kind.OFFLINE, item.name, item.size) { report ->
                var bytes = 0L
                app.offline.add(item, { i -> app.api.downloadChunk(item.region, item.bucket, item.uuid, i) }) { n ->
                    bytes = minOf(item.size, n.toLong() * CHUNK)
                    report(bytes)
                }
                app.drive.invalidate(Listing.Offline)
            }
        }
    }

    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        set(id) { if (it.active) it.copy(state = Transfer.State.CANCELED, speed = 0) else it }
    }

    fun retry(id: Long) {
        val again = retries.remove(id) ?: return
        _list.update { l -> l.filterNot { it.id == id } }
        again()
    }

    fun clearFinished() = _list.update { l -> l.filter { it.active }.also { kept -> retries.keys.retainAll(kept.map { it.id }.toSet()) } }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        retries.clear()
        _list.value = emptyList()
    }

    private fun save(item: Item, folder: String) {
        start(Transfer.Kind.DOWNLOAD, item.name, item.size) { report ->
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mime.ifEmpty { FileType.mime(item.name) })
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Filenza" + if (folder.isEmpty()) "" else "/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = withContext(Dispatchers.IO) { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }
                ?: throw IOException("Cannot write to Downloads")
            try {
                withContext(Dispatchers.IO) {
                    (resolver.openOutputStream(uri, "w") ?: throw IOException("Cannot write to Downloads")).use { out ->
                        app.content.write(item, out) { report(it) }
                    }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { runCatching { resolver.delete(uri, null, null) } }
                throw e
            }
        }
    }

    /**
     * Starts one task. [work] gets a progress callback (bytes done). Progress updates go to the list at most
     * every [TICK_MS], so that a fast link does not flood the UI.
     */
    private fun start(kind: Transfer.Kind, name: String, size: Long, work: suspend (report: (Long) -> Unit) -> Unit): Long {
        val id = next.getAndIncrement()
        _list.update { it + Transfer(id, kind, name, size) }
        retries[id] = { start(kind, name, size, work) }
        TransferJob.ensure(app)
        jobs[id] = app.scope.launch {
            try {
                gate.withPermit {
                    set(id) { it.copy(state = Transfer.State.RUNNING) }
                    val began = SystemClock.elapsedRealtime()
                    var last = 0L
                    work { done ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - last >= TICK_MS) {
                            last = now
                            val speed = if (now > began) done * 1000 / (now - began) else 0
                            set(id) { it.copy(done = done, speed = speed) }
                        }
                    }
                }
                set(id) { it.copy(state = Transfer.State.DONE, done = if (it.size > 0) it.size else it.done, speed = 0) }
                retries.remove(id)
            } catch (e: CancellationException) {
                set(id) { if (it.active) it.copy(state = Transfer.State.CANCELED, speed = 0) else it }
            } catch (e: Exception) {
                set(id) { it.copy(state = Transfer.State.FAILED, error = userText(e), speed = 0) }
            } finally {
                jobs.remove(id)
            }
        }
        return id
    }

    private fun set(id: Long, change: (Transfer) -> Transfer) = _list.update { l -> l.map { if (it.id == id) change(it) else it } }

    private class Described(val name: String?, val size: Long, val modified: Long)

    /** Name, size and change time of a content URI. Only these columns are read. */
    private fun describe(uri: Uri): Described {
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        var name: String? = null
        var size = -1L
        app.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)?.substringAfterLast('/')?.take(255)
                size = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
        val modified = runCatching {
            app.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        return Described(name, size, modified)
    }

    private fun path(parent: String, folders: Map<String, Item>, root: String, rootName: String): String {
        val parts = ArrayList<String>()
        var at: String? = parent
        var guard = 0
        while (at != null && at != root && guard++ < 64) {
            val f = folders[at] ?: break
            parts += f.name
            at = f.parent
        }
        return (listOf(rootName) + parts.reversed()).joinToString("/")
    }

    companion object {
        const val PARALLEL = 2
        private const val TICK_MS = 250L
        private const val CHUNK = 1024L * 1024
    }
}
