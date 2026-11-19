package app.nubo.ui.drive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nubo.app
import app.nubo.data.FolderView
import app.nubo.drive.Listing
import app.nubo.ui.Icons
import app.nubo.ui.components.EmptyState
import app.nubo.ui.components.ErrorState
import app.nubo.ui.components.IconAction
import app.nubo.ui.components.ItemRow
import app.nubo.ui.components.LoadingBox
import app.nubo.ui.components.NameDialog
import app.nubo.ui.components.userText
import kotlinx.coroutines.launch

/** Folders by name. */
private val ByName = FolderView()

/**
 * A full-screen picker of an own folder: for Move, uploads that other apps share, and the camera upload
 * folder. [exclude] holds folders that cannot be picked or opened (the folders that move).
 */
@Composable
fun FolderPicker(
    title: String,
    confirm: String,
    onPick: (uuid: String, name: String) -> Unit,
    onDismiss: () -> Unit,
    exclude: Set<String> = emptySet(),
) {
    val app = LocalContext.current.app
    val root = app.account.session?.baseFolder ?: return onDismiss()
    val path = remember { mutableStateListOf(root to "Drive") }
    val (uuid, name) = path.last()
    val folder = rememberFolder(Listing.Dir(uuid))
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val states = rememberSaveableStateHolder()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { if (path.size > 1) path.removeAt(path.lastIndex) else onDismiss() }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            Text(path.joinToString(" › ") { it.second }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                        }
                    },
                    navigationIcon = { IconAction(Icons.Close, "Close", onDismiss) },
                    actions = { IconAction(Icons.CreateNewFolder, "New folder", { creating = true }) },
                )
            },
            bottomBar = {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { onDismiss(); onPick(uuid, name) }, modifier = Modifier.weight(1f)) { Text(confirm) }
                }
            },
        ) { pad ->
            // The state of each folder (its sort and its list place): Up shows the parent at its own place (review 97).
            Box(Modifier.fillMaxSize().padding(pad)) {
                states.SaveableStateProvider(uuid) {
                    val folders = rememberSorted(remember(folder.items) { folder.items?.filter { it.folder && it.uuid !in exclude } }, ByName)
                    when {
                        folder.items == null && folder.error != null -> ErrorState(folder.error.orEmpty(), folder::refresh, Modifier.fillMaxSize())
                        folder.items == null -> LoadingBox(Modifier.fillMaxSize())
                        folders == null -> Unit // The first sort runs (one frame).
                        folders.isEmpty() -> EmptyState(Icons.Folder, "No folders here", Modifier.fillMaxSize())
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(folders.size, key = { folders[it].uuid }) { i ->
                                val f = folders[i]
                                ItemRow(f, onClick = { path += f.uuid to f.name }, subtitle = "")
                            }
                        }
                    }
                }
            }
        }
        if (creating) {
            NameDialog("New folder", "", "Create", { n ->
                scope.launch {
                    runCatching { app.drive.ops.createFolder(uuid, n) }
                        .onSuccess { path += it.uuid to it.name }
                        .onFailure { app.messenger.show(userText(it)) }
                }
            }, { creating = false })
        }
    }
}

/** Asks for the folder of files that another app shared, then queues their upload. */
@Composable
fun UploadTarget(uris: List<android.net.Uri>, onDone: () -> Unit) {
    val app = LocalContext.current.app
    FolderPicker(
        title = if (uris.size == 1) "Upload 1 file" else "Upload ${uris.size} files",
        confirm = "Upload here",
        onPick = { uuid, _ ->
            app.transfers.upload(uuid, uris)
            app.messenger.show(if (uris.size == 1) "Uploading 1 file" else "Uploading ${uris.size} files")
        },
        onDismiss = onDone,
    )
}
