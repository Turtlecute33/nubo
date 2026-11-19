package app.filenza.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.filenza.app
import app.filenza.drive.Item
import app.filenza.drive.Listing
import app.filenza.ui.Icons
import app.filenza.ui.components.EmptyState
import app.filenza.ui.components.ErrorState
import app.filenza.ui.components.IconAction
import app.filenza.ui.components.ItemRow
import app.filenza.ui.components.LoadingBox
import app.filenza.ui.components.PrivateTextInput
import app.filenza.ui.components.userText
import app.filenza.ui.drive.ItemActionsHost
import app.filenza.ui.drive.rememberItemActions
import app.filenza.ui.nav.LocalNav
import app.filenza.ui.nav.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer

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
    var query by remember { mutableStateOf("") }
    var tree by remember { mutableStateOf(app.drive.searchTree) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf(emptyList<Item>()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
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
    LaunchedEffect(query, tree) {
        val all = tree ?: return@LaunchedEffect
        delay(150)
        val q = fold(query.trim())
        results = if (q.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
            all.filter { fold(it.name).contains(q) }
                .sortedWith(compareBy<Item>({ !fold(it.name).startsWith(q) }, { !it.folder }, { it.name.lowercase() }))
                .take(MAX_RESULTS)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.ArrowBack, "Back", nav::back)
            PrivateTextInput {
                TextField(
                    value = query,
                    onValueChange = { query = it.take(200) },
                    placeholder = { Text("Search in Drive") },
                    singleLine = true,
                    shape = CircleShape,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    trailingIcon = { if (query.isNotEmpty()) IconAction(Icons.Close, "Clear", { query = "" }) },
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
        }
        Box(Modifier.fillMaxSize()) {
            when {
                error != null -> ErrorState(error.orEmpty(), { error = null; tree = null }, Modifier.fillMaxSize())
                tree == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingBox(Modifier.padding(top = 48.dp))
                    Text("Reading your drive…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
                query.isBlank() -> EmptyState(Icons.Search, "Search by name", Modifier.fillMaxSize(), message = "The search runs on this phone. Filen does not see what you look for.")
                results.isEmpty() -> EmptyState(Icons.Search, "No results", Modifier.fillMaxSize())
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(results.size, key = { results[it].uuid }) { i ->
                        val item = results[i]
                        ItemRow(
                            item,
                            onClick = {
                                if (item.folder) nav.go(Route.Folder(Listing.Dir(item.uuid), item.name)) else actions.open(item, results)
                            },
                            onMore = { actions.menu = item },
                            offline = item.uuid in offline,
                        )
                    }
                }
            }
        }
    }
    ItemActionsHost(actions, Listing.Recents)
}

/** Case- and accent-insensitive form of a name. */
private fun fold(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

private const val MAX_RESULTS = 300
