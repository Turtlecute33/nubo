package app.nubo.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nubo.NuboApp
import app.nubo.app
import app.nubo.data.AccountState
import app.nubo.drive.Listing
import app.nubo.ui.Icons
import app.nubo.ui.components.EmptyState
import app.nubo.ui.components.ErrorState
import app.nubo.ui.components.IconAction
import app.nubo.ui.components.ItemRow
import app.nubo.ui.components.LoadingBox
import app.nubo.ui.components.PrivateTextInput
import app.nubo.ui.components.userText
import app.nubo.ui.drive.ItemActionsHost
import app.nubo.ui.drive.rememberItemActions
import app.nubo.ui.nav.LocalNav
import app.nubo.ui.nav.Route
import app.nubo.ui.nav.Tab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Search by name in the whole drive. The names are encrypted on the server, so the app reads the full tree
 * once (one request) and searches on the phone. No search term leaves the phone (research F-68, F-91).
 */
@Composable
fun SearchScreen() {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val actions = rememberItemActions()
    val offline by app.offline.uuids.collectAsStateWithLifecycle()
    // The saved state gets only the entry number; the text stays in memory (reviews 25, 47).
    val id = rememberSaveable { SearchEntries.new(app) }
    val entry = remember(id) { SearchEntries[id] }
    var tree by remember { mutableStateOf(app.drive.searchTree) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val query = entry.query
    val hits = entry.hits
    val index = SearchEntries.index?.takeIf { it.items === tree }

    DisposableEffect(id) {
        // A result that opens on top keeps the text; Back or a jump to the tab root removes it.
        onDispose { if (Tab.entries.none { Route.Search in nav.stack(it) }) SearchEntries.remove(id) }
    }
    LaunchedEffect(Unit) { if (entry.query.isEmpty()) focus.requestFocus() }
    LaunchedEffect(tree == null, error) {
        if (tree != null || error != null) return@LaunchedEffect
        try {
            val root = app.account.session?.baseFolder ?: return@LaunchedEffect
            tree = app.drive.tree(root).also { app.drive.searchTree = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = userText(e)
        }
    }
    // The names fold once for each tree, not for each key press (review 101).
    LaunchedEffect(tree) {
        val all = tree ?: return@LaunchedEffect
        if (SearchEntries.index?.items !== all) SearchEntries.index = withContext(Dispatchers.Default) { SearchIndex.of(all) }
    }
    // A new key press cancels this search, and the search stops soon after (review 101).
    LaunchedEffect(query, index) {
        if (index == null) return@LaunchedEffect
        if (query.isBlank()) {
            entry.hits = emptyList()
            return@LaunchedEffect
        }
        delay(150)
        entry.hits = withContext(Dispatchers.Default) { index.find(query, MAX_RESULTS) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.ArrowBack, "Back", nav::back)
            PrivateTextInput {
                TextField(
                    value = query,
                    onValueChange = { entry.query = it.take(200) },
                    placeholder = { Text("Search in Drive") },
                    singleLine = true,
                    shape = CircleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    trailingIcon = { if (query.isNotEmpty()) IconAction(Icons.Close, "Clear", { entry.query = "" }) },
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
        }
        // The last results stay above the keyboard (review 92). The tab bar hides while the keyboard shows, so
        // this box reaches the bottom of the screen and pads for the whole keyboard.
        Box(Modifier.fillMaxSize().imePadding()) {
            when {
                error != null -> ErrorState(error.orEmpty(), { error = null; tree = null }, Modifier.fillMaxSize())
                index == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingBox(Modifier.padding(top = 48.dp))
                    Text("Reading your drive…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
                query.isBlank() -> EmptyState(Icons.Search, "Search by name", Modifier.fillMaxSize(), message = "The search runs on this phone. Filen does not see what you look for.")
                hits.isEmpty() -> EmptyState(Icons.Search, "No results", Modifier.fillMaxSize())
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(hits.size, key = { hits[it].item.uuid }) { i ->
                        val hit = hits[i]
                        val item = hit.item
                        ItemRow(
                            item,
                            onClick = {
                                if (item.folder) nav.go(Route.Folder(Listing.Dir(item.uuid), item.name)) else actions.open(item, hits.map { it.item })
                            },
                            onMore = { actions.menu = item },
                            offline = item.uuid in offline,
                            subtitle = hit.path,
                        )
                    }
                }
            }
        }
    }
    ItemActionsHost(actions, Listing.Recents)
}

/** What one search entry keeps while it is in a back stack. Memory only. */
@Stable
internal class SearchEntry {
    var query by mutableStateOf("")
    var hits by mutableStateOf(emptyList<Hit>())
}

/**
 * The search entries by number, on the main thread. The saved state holds only the number, so no search text
 * goes into it (review 25), and the text stays while a result is open on top (review 47). Logout clears all.
 */
internal object SearchEntries {
    private var next = 1L
    private val entries = object : LinkedHashMap<Long, SearchEntry>() {
        // An entry that left its stack while it did not show (a tab reset) is not removed: keep only a few.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, SearchEntry>) = size > MAX_ENTRIES
    }
    private val watching = AtomicBoolean(false)

    /** The folded names of the last search tree, for all entries. */
    var index by mutableStateOf<SearchIndex?>(null)

    fun new(app: NuboApp): Long {
        if (watching.compareAndSet(false, true)) app.scope.launch {
            app.account.state.collect {
                if (it !is AccountState.LoggedIn) {
                    entries.clear()
                    index = null
                }
            }
        }
        return next++
    }

    operator fun get(id: Long): SearchEntry = entries.getOrPut(id) { SearchEntry() }

    fun remove(id: Long) {
        entries.remove(id)
    }

    private const val MAX_ENTRIES = 4
}

private const val MAX_RESULTS = 300
