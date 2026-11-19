package app.nubo.ui.drive

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nubo.NuboApp
import app.nubo.app
import app.nubo.drive.Item
import app.nubo.drive.Listing
import app.nubo.drive.Source
import app.nubo.media.OpenWith
import app.nubo.ui.Icons
import app.nubo.ui.components.ConfirmDialog
import app.nubo.ui.components.ItemThumb
import app.nubo.ui.components.NameDialog
import app.nubo.ui.components.subtitle
import app.nubo.ui.components.userText
import app.nubo.ui.nav.LocalNav
import app.nubo.ui.nav.Navigator
import app.nubo.ui.nav.Route
import app.nubo.ui.share.LinkSheet
import app.nubo.ui.share.ShareSheet
import app.nubo.ui.viewer.Viewers
import app.nubo.util.Debounce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the item menu and the selection bar can do, and the dialogs and sheets they open. One per screen. */
@Stable
class ItemActions(private val app: NuboApp, private val nav: Navigator, private val scope: CoroutineScope, private val context: Context) {
    var menu by mutableStateOf<Item?>(null)
    var renaming by mutableStateOf<Item?>(null)
    var moving by mutableStateOf<List<Item>>(emptyList())
    var trashing by mutableStateOf<List<Item>>(emptyList())
    var deleting by mutableStateOf<List<Item>>(emptyList())
    var linking by mutableStateOf<Item?>(null)
    var sharing by mutableStateOf<Item?>(null)
    var leaving by mutableStateOf<Item?>(null)
    private val chooser = Debounce()

    /**
     * Opens a file: an in-app viewer for the types it shows, else another app. Folders are the caller's.
     * A double tap opens one viewer ([Navigator.go]) and one chooser only (review 89).
     */
    fun open(item: Item, siblings: List<Item>) {
        if (Viewers.viewable(item)) {
            val (session, index) = Viewers.open(siblings, item)
            nav.go(Route.Viewer(session, index))
        } else if (chooser.pass()) {
            OpenWith.open(context, item)
        }
    }

    fun download(items: List<Item>) {
        app.transfers.download(items)
        app.messenger.show(if (items.size == 1) "Saving to Download/nubo" else "Saving ${items.size} items to Download/nubo")
    }

    fun offline(items: List<Item>) = app.transfers.keepOffline(items)

    fun removeOffline(item: Item) = run { app.offline.remove(item.uuid); app.drive.invalidate(Listing.Offline) }

    fun favorite(item: Item) = run { app.drive.ops.favorite(item, !item.favorite) }

    fun rename(item: Item, name: String) = run { app.drive.ops.rename(item, name) }

    fun move(items: List<Item>, to: String) = run {
        val skipped = app.drive.ops.move(items, to)
        if (skipped > 0) app.messenger.show("$skipped ${if (skipped == 1) "item has" else "items have"} a name that is in that folder")
    }

    fun trash(items: List<Item>) = run(
        done = if (items.size == 1) "Moved to trash" else "${items.size} items moved to trash",
        undo = { app.drive.ops.restore(items) },
    ) { app.drive.ops.trash(items) }

    fun restore(items: List<Item>) = run(done = "Restored") { app.drive.ops.restore(items) }

    fun deleteForever(items: List<Item>) = run(done = "Deleted") { app.drive.ops.deleteForever(items) }

    fun leave(item: Item) = run { app.drive.sharing.leave(item) }

    /** Runs [block]; an error goes to the snackbar, and [done] with an optional undo after success. */
    /** Writes run in the app scope: a screen that closes (a tap into a folder, a tab change) must not stop them halfway. */
    fun run(done: String? = null, undo: (suspend () -> Unit)? = null, block: suspend () -> Unit) {
        app.scope.launch {
            try {
                block()
                if (done != null) app.messenger.show(done, if (undo != null) "Undo" else null) { undo?.let { u -> run(block = u) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.messenger.show(userText(e))
            }
        }
    }
}

@Composable
fun rememberItemActions(): ItemActions {
    val context = LocalContext.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    return remember { ItemActions(context.app, nav, scope, context) }
}

/** The item menu (a bottom sheet) and every dialog of [actions]. [listing] decides which actions show. */
@Composable
fun ItemActionsHost(actions: ItemActions, listing: Listing) {
    val app = LocalContext.current.app
    val offline by app.offline.uuids.collectAsStateWithLifecycle()
    actions.menu?.let { item ->
        ItemMenu(item, listing, item.uuid in offline, actions) { actions.menu = null }
    }
    actions.renaming?.let { item ->
        NameDialog(if (item.folder) "Rename folder" else "Rename file", item.name, "Rename", { actions.rename(item, it) }, { actions.renaming = null })
    }
    if (actions.moving.isNotEmpty()) {
        val items = actions.moving
        FolderPicker(
            title = if (items.size == 1) "Move “${items[0].name}”" else "Move ${items.size} items",
            confirm = "Move here",
            exclude = items.filter { it.folder }.map { it.uuid }.toSet(),
            onPick = { uuid, _ -> actions.move(items, uuid) },
            onDismiss = { actions.moving = emptyList() },
        )
    }
    if (actions.trashing.isNotEmpty()) {
        val items = actions.trashing
        ConfirmDialog(
            title = if (items.size == 1) "Move “${items[0].name}” to the trash?" else "Move ${items.size} items to the trash?",
            text = "You can restore items from the trash.",
            confirm = "Move to trash",
            onConfirm = { actions.trash(items) },
            onDismiss = { actions.trashing = emptyList() },
        )
    }
    if (actions.deleting.isNotEmpty()) {
        val items = actions.deleting
        ConfirmDialog(
            title = if (items.size == 1) "Delete “${items[0].name}” for ever?" else "Delete ${items.size} items for ever?",
            text = "This cannot be undone.",
            confirm = "Delete",
            destructive = true,
            onConfirm = { actions.deleteForever(items) },
            onDismiss = { actions.deleting = emptyList() },
        )
    }
    actions.leaving?.let { item ->
        // There is no undo: only the owner can share the item again (review 55).
        ConfirmDialog(
            title = "Remove “${item.name}” from your shared items?",
            text = "You lose access to it. Only the owner can share it with you again.",
            confirm = "Remove",
            destructive = true,
            onConfirm = { actions.leave(item) },
            onDismiss = { actions.leaving = null },
        )
    }
    actions.linking?.let { LinkSheet(it) { actions.linking = null } }
    actions.sharing?.let { ShareSheet(it) { actions.sharing = null } }
}

@Composable
private fun ItemMenu(item: Item, listing: Listing, offline: Boolean, a: ItemActions, close: () -> Unit) {
    val context = LocalContext.current
    val own = item.source is Source.Own || item.source is Source.SharedOut
    val trash = listing == Listing.Trash
    val loggedIn = context.app.account.session != null
    fun act(block: () -> Unit) = { close(); block() }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                ItemThumb(item, size = 48.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
                    Text(item.subtitle(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            HorizontalDivider()
            when {
                trash -> {
                    Action(Icons.SettingsBackupRestore, "Restore", act { a.restore(listOf(item)) })
                    Action(Icons.DeleteForever, "Delete for ever", act { a.deleting = listOf(item) }, destructive = true)
                }
                else -> {
                    if (!item.folder) Action(Icons.OpenInNew, "Open with", act { OpenWith.open(context, item) })
                    if (!item.folder) Action(Icons.Share, "Send a copy", act { OpenWith.share(context, listOf(item)) })
                    // A folder downloads through the own-drive tree; shared and link folders open to download files.
                    if (!item.folder || own) Action(Icons.Download, "Download", act { a.download(listOf(item)) })
                    if (!item.folder && loggedIn && listing != Listing.Offline) {
                        if (offline) Action(Icons.OfflinePinFill, "Remove offline copy", act { a.removeOffline(item) })
                        else Action(Icons.OfflinePin, "Keep offline", act { a.offline(listOf(item)) })
                    }
                    if (listing == Listing.Offline) Action(Icons.OfflinePinFill, "Remove offline copy", act { a.removeOffline(item) })
                    if (own) {
                        Action(Icons.Link, "Public link", act { a.linking = item })
                        Action(Icons.Group, "Share with Filen users", act { a.sharing = item })
                        Action(if (item.favorite) Icons.StarFill else Icons.Star, if (item.favorite) "Remove from favourites" else "Add to favourites", act { a.favorite(item) })
                        Action(Icons.DriveFileRenameOutline, "Rename", act { a.renaming = item })
                        Action(Icons.DriveFileMove, "Move", act { a.moving = listOf(item) })
                        Action(Icons.Delete, "Move to trash", act { a.trashing = listOf(item) }, destructive = true)
                    }
                    if (item.source is Source.SharedIn && listing == Listing.SharedIn) {
                        Action(Icons.Logout, "Remove from my shared items", act { a.leaving = item }, destructive = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit, destructive: Boolean = false) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        onClick = onClick,
        leadingContent = { Icon(icon, null, tint = color) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp),
    ) { Text(label, color = color) }
}
