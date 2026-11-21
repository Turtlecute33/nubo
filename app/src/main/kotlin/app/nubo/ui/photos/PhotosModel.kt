package app.nubo.ui.photos

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.nubo.NuboApp
import app.nubo.app
import app.nubo.data.AccountState
import app.nubo.drive.Drive
import app.nubo.drive.Item
import app.nubo.drive.Tree
import app.nubo.ui.components.userText
import app.nubo.util.FileType
import app.nubo.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/** One day of the timeline: its header text and its media, newest first. */
@Immutable
class Day(val key: Long, val label: String, val items: List<Item>)

/**
 * The media of the last shown photo folder, while the process lives. Tab changes show it at once, with no new
 * tree read while its read is fresh (review 52). It goes away when the session ends, so that another account never
 * sees it. Memory only; the tree read itself is in [app.nubo.drive.Trees]. It also keeps the name of the photo
 * folder, because the settings file keeps only the UUID (review 63).
 */
internal object PhotoCache {
    @Volatile
    var folder: String? = null

    @Volatile
    var media: List<Item>? = null

    /** The tree read that [media] comes from. */
    @Volatile
    var tree: Tree? = null

    /** The photo folder UUID and its name. */
    @Volatile
    private var named: Pair<String, String>? = null

    private val watching = AtomicBoolean(false)

    /** Clears the cache at each logout. Starts once for the process. */
    fun watch(app: NuboApp) {
        if (!watching.compareAndSet(false, true)) return
        app.scope.launch {
            app.account.state.collect { if (it !is AccountState.LoggedIn) clear() }
        }
    }

    /** True when the media of [uuid] are here and their tree read is fresh (not older than [FRESH_MS]). */
    fun fresh(drive: Drive, uuid: String): Boolean = folder == uuid && media != null && tree?.let { drive.fresh(it, FRESH_MS) } == true

    fun name(uuid: String?): String? = named?.takeIf { it.first == uuid }?.second

    fun name(uuid: String, name: String) {
        named = uuid to name
    }

    fun clear() {
        folder = null
        media = null
        tree = null
        named = null
    }

    private const val FRESH_MS = 5 * 60_000L
}

/** The name of the photo folder [uuid]: from memory, else read once from the server. Null while not known. */
@Composable
internal fun rememberFolderName(uuid: String?): String? {
    val app = LocalContext.current.app
    var name by remember(uuid) { mutableStateOf(PhotoCache.name(uuid)) }
    if (uuid != null && name == null) LaunchedEffect(uuid) {
        PhotoCache.watch(app)
        val read = try {
            app.drive.item(uuid, folder = true)?.name
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (read != null) {
            PhotoCache.name(uuid, read)
            name = read
        }
    }
    return name
}

/**
 * The images and videos under the photo folder (all levels), from one tree read (research F-65). Newest
 * first by the time the photo was taken, else by its change time.
 */
@Stable
class PhotosModel(private val app: NuboApp, private val scope: CoroutineScope) {
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

    /**
     * Shows [uuid]'s media: from the cache, else from the last tree read (the disk, or the part of the search read),
     * then reads the tree when that read is not fresh (review P-03).
     */
    fun show(uuid: String?) {
        if (uuid == folder && (media != null || job?.isActive == true)) return
        folder = uuid
        error = null
        media = PhotoCache.media.takeIf { PhotoCache.folder == uuid }
        days = emptyList()
        job?.cancel()
        refreshing = false
        if (uuid != null) load(uuid, read = false)
    }

    fun refresh() {
        load(folder ?: return, read = true)
    }

    /** [read]: read the tree from the server, also when the last read is fresh (pull to refresh, a change). */
    private fun load(uuid: String, read: Boolean) {
        job?.cancel()
        job = scope.launch {
            try {
                val cached = media
                if (cached != null) {
                    if (days.isEmpty()) days = withContext(Dispatchers.Default) { group(cached) }
                } else if (!read) {
                    app.drive.cachedTree(uuid)?.let { show(uuid, it) }
                }
                if (!read && PhotoCache.fresh(app.drive, uuid)) return@launch
                refreshing = true
                show(uuid, app.drive.readTree(uuid))
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = userText(e)
            } finally {
                // A cancelled read ends while the next read runs: only the last read clears the flag (review 96).
                if (job === coroutineContext.job) refreshing = false
            }
        }
    }

    private suspend fun show(uuid: String, tree: Tree) {
        val (list, grouped) = withContext(Dispatchers.Default) {
            val l = tree.items.filter { !it.folder && (it.type == FileType.IMAGE || it.type == FileType.VIDEO) }.sortedByDescending(::time)
            l to group(l)
        }
        PhotoCache.folder = uuid
        PhotoCache.media = list
        PhotoCache.tree = tree
        media = list
        days = grouped
    }

    /**
     * Reads again after folder changes. Camera upload sends one change for each file: the read waits until
     * the changes stop for a moment, so that a burst gives one read.
     */
    suspend fun watch() {
        app.drive.changed.filter { Drive.changesTree(it) }.collectLatest {
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
