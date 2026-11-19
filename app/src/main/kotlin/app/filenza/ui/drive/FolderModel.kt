package app.filenza.ui.drive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.filenza.FilenzaApp
import app.filenza.app
import app.filenza.data.FolderView
import app.filenza.data.SortBy
import app.filenza.drive.Item
import app.filenza.drive.Listing
import app.filenza.ui.components.userText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The items of one listing: the cached list at once, then the server's. Reloads when a write changes it. */
@Stable
class FolderModel(private val app: FilenzaApp, val listing: Listing, private val scope: CoroutineScope) {
    var items by mutableStateOf(app.drive.cached(listing))
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        refreshing = true
        job = scope.launch {
            try {
                items = app.drive.list(listing)
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

    suspend fun watch() {
        app.drive.changed.collect { if (it == null || it == listing) refresh() }
    }
}

@Composable
fun rememberFolder(listing: Listing): FolderModel {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val model = remember(listing) { FolderModel(app, listing, scope) }
    LaunchedEffect(model) {
        model.refresh()
        model.watch()
    }
    return model
}

/** Folders first, then [view]'s order. Names compare like a person reads them (case-insensitive). */
fun List<Item>.sortedFor(view: FolderView): List<Item> {
    val byName = compareBy<Item, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    val order: Comparator<Item> = when (view.sort) {
        SortBy.NAME -> byName
        SortBy.MODIFIED -> compareBy<Item> { it.modified }.then(byName)
        SortBy.SIZE -> compareBy<Item> { it.size }.then(byName)
        SortBy.TYPE -> compareBy<Item> { it.name.substringAfterLast('.', "").lowercase() }.then(byName)
    }
    val directed = if (view.descending) order.reversed() else order
    return sortedWith(compareBy<Item> { !it.folder }.then(directed))
}
