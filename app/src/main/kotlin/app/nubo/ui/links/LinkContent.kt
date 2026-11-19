package app.nubo.ui.links

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nubo.app
import app.nubo.data.FolderView
import app.nubo.drive.FolderLink
import app.nubo.drive.Item
import app.nubo.drive.Listing
import app.nubo.transfer.Transfer
import app.nubo.ui.Icons
import app.nubo.ui.components.EmptyState
import app.nubo.ui.components.ErrorState
import app.nubo.ui.components.ItemCollection
import app.nubo.ui.components.ItemThumb
import app.nubo.ui.components.LoadingBox
import app.nubo.ui.drive.rememberFolder
import app.nubo.ui.drive.rememberSorted
import app.nubo.util.Format

/** Folders first, then by name. */
private val ByName = FolderView()

/** The explanation at the top of a link: no account is used, and the key stays on the phone. */
@Composable
internal fun LinkHeader() {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Encrypted, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Text(
                "Opened without your account. The key is in the link and stays on this phone, so Filen cannot read these files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * One shared file: its icon, name and size, Open and Download. The progress of the download shows here,
 * also when no account is logged in (the transfer pill is not there then).
 */
@Composable
internal fun FileCard(item: Item, onOpen: () -> Unit) {
    val app = LocalContext.current.app
    val transfers by app.transfers.list.collectAsStateWithLifecycle()
    var after by remember { mutableLongStateOf(-1L) }
    val mine = if (after < 0) null else transfers.lastOrNull { it.id > after && it.kind == Transfer.Kind.DOWNLOAD && it.name == item.name }
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ItemThumb(item, size = 120.dp, cornerFraction = 0.22f)
        Spacer(Modifier.height(20.dp))
        Text(item.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.MiddleEllipsis)
        Spacer(Modifier.height(4.dp))
        Text(Format.size(item.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.widthIn(max = 420.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                Icon(Icons.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Open")
            }
            Button(
                onClick = {
                    after = transfers.maxOfOrNull { it.id } ?: 0
                    app.transfers.download(listOf(item))
                },
                enabled = mine?.active != true,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Download")
            }
        }
        if (mine != null) {
            Spacer(Modifier.height(20.dp))
            if (mine.state == Transfer.State.RUNNING) {
                LinearWavyProgressIndicator(progress = { mine.progress }, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }
            Text(
                when (mine.state) {
                    Transfer.State.QUEUED -> "Waiting"
                    Transfer.State.RUNNING -> "${Format.size(mine.done)} of ${Format.size(mine.size)}"
                    Transfer.State.DONE -> "Saved to Download/nubo"
                    Transfer.State.FAILED -> mine.error ?: "The download failed"
                    Transfer.State.CANCELED -> "Cancelled"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (mine.state == Transfer.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The content of one folder of a public folder link. Folders open in the screen's own stack ([onFolder]);
 * files open in the viewer ([onFile] gets the file and the files of the folder).
 */
@Composable
internal fun LinkFolder(link: FolderLink, uuid: String, onFolder: (Item) -> Unit, onFile: (Item, List<Item>) -> Unit) {
    val app = LocalContext.current.app
    val folder = rememberFolder(Listing.LinkDir(link, uuid))
    val items = rememberSorted(folder.items, ByName)
    var menu by remember { mutableStateOf<Item?>(null) }
    when {
        folder.items == null && folder.error != null -> ErrorState(folder.error.orEmpty(), folder::refresh, Modifier.fillMaxSize())
        items == null -> Column(Modifier.fillMaxSize()) {
            LinkHeader()
            if (folder.items == null) LoadingBox(Modifier.padding(top = 48.dp)) // Else the first sort runs (one frame).
        }
        else -> ItemCollection(
            items = items,
            grid = false,
            selection = emptySet(),
            onClick = { if (it.folder) onFolder(it) else onFile(it, items) },
            onLongClick = { menu = it },
            onMore = { menu = it },
            contentPadding = PaddingValues(bottom = 96.dp),
            header = {
                Column {
                    LinkHeader()
                    if (items.isEmpty()) EmptyState(Icons.Folder, "This folder is empty", Modifier.fillMaxWidth().padding(top = 32.dp))
                }
            },
        )
    }
    menu?.let { item ->
        ModalBottomSheet(onDismissRequest = { menu = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
                Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ItemThumb(item, size = 48.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                }
                MenuRow(Icons.OpenInNew, "Open") {
                    menu = null
                    if (item.folder) onFolder(item) else onFile(item, items.orEmpty())
                }
                if (!item.folder) {
                    MenuRow(Icons.Download, "Download") {
                        menu = null
                        app.transfers.download(listOf(item))
                        app.messenger.show("Saving to Download/nubo")
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp),
    ) { Text(label) }
}
