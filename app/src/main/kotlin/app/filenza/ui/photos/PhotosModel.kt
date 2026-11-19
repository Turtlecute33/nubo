package app.filenza.ui.photos

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.filenza.FilenzaApp
import app.filenza.data.AccountState
import app.filenza.drive.Item
import app.filenza.ui.components.userText
import app.filenza.util.FileType
import app.filenza.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/** One day of the timeline: its header text and its media, newest first. */
@Immutable
class Day(val key: Long, val label: String, val items: List<Item>)

/**
 * The media of the last read photo folder, while the process lives. Tab changes show it at once. It goes
 * away when the session ends, so that another account never sees it. Memory only, never on the disk.
 */
internal object PhotoCache {
    @Volatile
    var folder: String? = null

    @Volatile
    var media: List<Item>? = null

    private val watching = AtomicBoolean(false)

    /** Clears the cache at each logout. Starts once for the process. */
    fun watch(app: FilenzaApp) {
        if (!watching.compareAndSet(false, true)) return
        app.scope.launch {
            app.account.state.collect { if (it !is AccountState.LoggedIn) clear() }
        }
    }

    fun clear() {
        folder = null
        media = null
    }
}

/**
 * The images and videos under the photo folder (all levels), from one tree read (research F-65). Newest
 * first by the time the photo was taken, else by its change time.
 */
@Stable
class PhotosModel(private val app: FilenzaApp, private val scope: CoroutineScope) {
    var folder by mutableStateOf<String?>(null)
        private set
    var media by mutableStateOf<List<Item>?>(null)
        private set
    var days by mutableStateOf<List<Day>>(emptyList())
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    /** Shows [uuid]'s media: from the cache when it has them, then from the server. */
    fun show(uuid: String?) {
        if (uuid == folder && (media != null || refreshing)) return
        folder = uuid
        error = null
        val cached = PhotoCache.media.takeIf { PhotoCache.folder == uuid }
        media = cached
        days = emptyList()
        if (cached != null) scope.launch {
            val grouped = withContext(Dispatchers.Default) { group(cached) }
            if (media === cached) days = grouped
        }
        if (uuid != null) refresh() else job?.cancel()
    }

    fun refresh() {
        val uuid = folder ?: return
        job?.cancel()
        refreshing = true
        job = scope.launch {
            try {
                val tree = app.drive.tree(uuid)
                val (list, grouped) = withContext(Dispatchers.Default) {
                    val l = tree.filter { !it.folder && (it.type == FileType.IMAGE || it.type == FileType.VIDEO) }.sortedByDescending(::time)
                    l to group(l)
                }
                PhotoCache.folder = uuid
                PhotoCache.media = list
                media = list
                days = grouped
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = userText(e)
            } finally {
                refreshing = false
            }
        }
    }

    /**
     * Reads again after drive changes. Camera upload sends one change for each file: the read waits until
     * the changes stop for a moment, so that a burst gives one read.
     */
    suspend fun watch() {
        app.drive.changed.collectLatest {
            delay(QUIET_MS)
            if (folder != null) refresh()
        }
    }

    private companion object {
        const val QUIET_MS = 1_500L

        fun time(item: Item): Long = item.created.takeIf { it > 0 } ?: item.modified

        /** Groups [list] (newest first) by local calendar day. */
        fun group(list: List<Item>): List<Day> {
            val cal = Calendar.getInstance()
            val out = ArrayList<Day>()
            var key = Long.MIN_VALUE
            var current = ArrayList<Item>()
            for (item in list) {
                cal.timeInMillis = time(item)
                val k = cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)
                if (k != key) {
                    if (current.isNotEmpty()) out += Day(key, Format.day(time(current[0])), current)
                    key = k
                    current = ArrayList()
                }
                current += item
            }
            if (current.isNotEmpty()) out += Day(key, Format.day(time(current[0])), current)
            return out
        }
    }
}
