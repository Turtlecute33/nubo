package app.nubo.ui.drive

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import app.nubo.app
import app.nubo.data.FolderView
import app.nubo.data.SortBy
import app.nubo.drive.Item
import app.nubo.drive.Listing
import app.nubo.drive.Source
import app.nubo.ui.Icons
import app.nubo.ui.components.ConfirmDialog
import app.nubo.ui.components.EmptyState
import app.nubo.ui.components.ErrorState
import app.nubo.ui.components.IconAction
import app.nubo.ui.components.ItemCollection
import app.nubo.ui.components.LoadingBox
import app.nubo.ui.components.NameDialog
import app.nubo.ui.nav.LocalNav
import app.nubo.ui.nav.Route

/**
 * One listing: a drive folder, Recents, Favourites, Trash, Links, shares, Offline, or a folder of a public
 * link. List or grid, sort per folder, pull to refresh, selection with a bar of bulk actions, and an add
 * button in folders that the user can write to.
 */
@Composable
fun FolderScreen(listing: Listing, title: String, isRoot: Boolean = false) {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val haptics = LocalHapticFeedback.current
    val settings by app.prefs.state.collectAsStateWithLifecycle()
    val offline by app.offline.uuids.collectAsStateWithLifecycle()
    val folder = rememberFolder(listing)
    val actions = rememberItemActions()
    val viewKey = listing.folder ?: listing.toString()
    val view = settings.viewOf(viewKey)
    var selection by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet()) }
    var sortMenu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var emptying by remember { mutableStateOf(false) }
    var fabOpen by rememberSaveable { mutableStateOf(false) }
    val items = rememberSorted(folder.items, view)
    val selected = remember(items, selection) { items.orEmpty().filter { it.uuid in selection } }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    // A refresh can remove selected items: keep only the items that are still in the list (review 95).
    LaunchedEffect(selected) {
        if (items != null && selected.size != selection.size) selection = selected.mapTo(HashSet()) { it.uuid }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        listing.folder?.let { if (uris.isNotEmpty()) app.transfers.upload(it, uris) }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        listing.folder?.let { if (uri != null) app.transfers.uploadTree(it, uri) }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris: List<Uri> ->
        listing.folder?.let { if (uris.isNotEmpty()) app.transfers.upload(it, uris) }
    }

    BackHandler(enabled = selection.isNotEmpty()) { selection = emptySet() }
    // Back closes the open add menu first, not the folder (review 90).
    BackHandler(enabled = fabOpen) { fabOpen = false }

    fun open(item: Item) {
        when {
            selection.isNotEmpty() -> selection = if (item.uuid in selection) selection - item.uuid else selection + item.uuid
            item.folder -> child(listing, item)?.let { nav.go(Route.Folder(it, item.name)) }
            else -> actions.open(item, items.orEmpty())
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (selection.isNotEmpty()) {
                SelectionBar(
                    count = selected.size,
                    listing = listing,
                    onClose = { selection = emptySet() },
                    onAll = { selection = items.orEmpty().map { it.uuid }.toSet() },
                    onDownload = { actions.download(selected); selection = emptySet() },
                    onMove = { actions.moving = selected; selection = emptySet() },
                    onTrash = { actions.trashing = selected; selection = emptySet() },
                    onRestore = { actions.restore(selected); selection = emptySet() },
                    onDelete = { actions.deleting = selected; selection = emptySet() },
                    onOffline = { actions.offline(selected); selection = emptySet() },
                )
            } else {
                LargeFlexibleTopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    subtitle = folder.items?.let { { Text(count(it)) } },
                    navigationIcon = { if (!isRoot) IconAction(Icons.ArrowBack, "Back", nav::back) },
                    actions = {
                        IconAction(Icons.Search, "Search", { nav.go(Route.Search) })
                        IconAction(if (view.grid) Icons.ViewList else Icons.GridView, if (view.grid) "List" else "Grid", {
                            app.prefs.setView(viewKey, view.copy(grid = !view.grid))
                        })
                        Box {
                            IconAction(Icons.Sort, "Sort", { sortMenu = true })
                            SortMenu(sortMenu, view, { sortMenu = false }) { app.prefs.setView(viewKey, it) }
                        }
                        if (listing == Listing.Trash) IconAction(Icons.DeleteSweep, "Empty trash", { emptying = true })
                    },
                    scrollBehavior = scroll,
                )
            }
        },
        floatingActionButton = {
            val parent = listing.folder
            if (parent != null && selection.isEmpty()) {
                FloatingActionButtonMenu(
                    expanded = fabOpen,
                    button = {
                        ToggleFloatingActionButton(
                            checked = fabOpen,
                            onCheckedChange = {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                fabOpen = it
                            },
                            modifier = Modifier.semantics { contentDescription = if (fabOpen) "Close" else "Add" },
                        ) {
                            Icon(if (checkedProgress > 0.5f) Icons.Close else Icons.Add, null, Modifier.animateIcon({ checkedProgress }))
                        }
                    },
                ) {
                    FloatingActionButtonMenuItem(onClick = { fabOpen = false; pickFiles.launch(arrayOf("*/*")) }, icon = { Icon(Icons.UploadFile, null) }, text = { Text("Upload files") })
                    FloatingActionButtonMenuItem(
                        onClick = { fabOpen = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        icon = { Icon(Icons.PhotoLibrary, null) },
                        text = { Text("Upload photos and videos") },
                    )
                    FloatingActionButtonMenuItem(onClick = { fabOpen = false; pickFolder.launch(null) }, icon = { Icon(Icons.DriveFolderUpload, null) }, text = { Text("Upload a folder") })
                    FloatingActionButtonMenuItem(onClick = { fabOpen = false; creating = true }, icon = { Icon(Icons.CreateNewFolder, null) }, text = { Text("New folder") })
                }
            }
        },
    ) { pad ->
        val refresh = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = folder.refreshing && items != null,
            onRefresh = folder::refresh,
            state = refresh,
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(state = refresh, isRefreshing = folder.refreshing && items != null, modifier = Modifier.align(Alignment.TopCenter))
            },
        ) {
            // The empty and error states scroll, so that pull to refresh works there too (review 91).
            when {
                folder.items == null && folder.error != null ->
                    ErrorState(folder.error.orEmpty(), folder::refresh, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                folder.items == null -> LoadingBox(Modifier.fillMaxSize())
                items == null -> Unit // The first sort runs (one frame).
                items.isEmpty() -> EmptyFor(listing, Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
                else -> ItemCollection(
                    items = items,
                    grid = view.grid,
                    selection = selection,
                    onClick = ::open,
                    onLongClick = { item ->
                        if (listing.writable || listing == Listing.Trash || listing == Listing.Recents || listing == Listing.Favorites) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            selection = selection + item.uuid
                        } else {
                            actions.menu = item
                        }
                    },
                    onMore = { actions.menu = it },
                    offline = offline,
                    contentPadding = PaddingValues(bottom = 120.dp),
                )
            }
        }
    }
    ItemActionsHost(actions, listing)
    if (creating) {
        listing.folder?.let { parent -> NameDialog("New folder", "", "Create", { actions.run { app.drive.ops.createFolder(parent, it) } }, { creating = false }) }
    }
    if (emptying) {
        ConfirmDialog("Empty the trash?", "All items in the trash will be deleted for ever.", "Empty trash", { actions.run(done = "Trash emptied") { app.drive.ops.emptyTrash() } }, { emptying = false }, destructive = true)
    }
}

/** The listing that a folder of [parent] opens, or null when folders here cannot open (the trash). */
private fun child(parent: Listing, item: Item): Listing? = when (parent) {
    Listing.Trash -> null
    Listing.SharedIn, is Listing.SharedDir -> Listing.SharedDir(item.uuid, (item.source as? Source.SharedIn)?.owner.orEmpty())
    is Listing.LinkDir -> Listing.LinkDir(parent.link, item.uuid)
    else -> (item.source as? Source.SharedIn)?.let { Listing.SharedDir(item.uuid, it.owner) } ?: Listing.Dir(item.uuid)
}

private fun count(items: List<Item>): String {
    val folders = items.count { it.folder }
    val files = items.size - folders
    fun n(c: Int, w: String) = if (c == 1) "1 $w" else "$c ${w}s"
    return when {
        items.isEmpty() -> "Empty"
        folders == 0 -> n(files, "file")
        files == 0 -> n(folders, "folder")
        else -> n(folders, "folder") + " · " + n(files, "file")
    }
}

@Composable
private fun EmptyFor(listing: Listing, modifier: Modifier) {
    val (icon, title, text) = when (listing) {
        Listing.Trash -> Triple(Icons.Delete, "The trash is empty", null)
        Listing.Favorites -> Triple(Icons.Star, "No favourites", "Add files and folders from their menu.")
        Listing.Recents -> Triple(Icons.History, "Nothing recent", null)
        Listing.Links -> Triple(Icons.Link, "No public links", "Make a link from the menu of a file or folder.")
        Listing.SharedIn -> Triple(Icons.Group, "Nothing shared with you", null)
        Listing.SharedOut -> Triple(Icons.Group, "You share nothing", "Share from the menu of a file or folder.")
        Listing.Offline -> Triple(Icons.OfflinePin, "No offline files", "Keep files on this phone from their menu.")
        else -> Triple(Icons.Folder, "This folder is empty", if (listing.writable) "Tap + to upload files or make a folder." else null)
    }
    EmptyState(icon, title, modifier, message = text)
}

/** The selection survives a tab change. A large one is not saved, so that the saved state stays small (review 95). */
private val SelectionSaver = Saver<Set<String>, ArrayList<String>>(
    save = { if (it.size <= 500) ArrayList(it) else null },
    restore = { it.toSet() },
)

@Composable
private fun SortMenu(open: Boolean, view: FolderView, onClose: () -> Unit, onChange: (FolderView) -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onClose) {
        listOf(SortBy.NAME to "Name", SortBy.MODIFIED to "Date", SortBy.SIZE to "Size", SortBy.TYPE to "Type").forEach { (s, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = { onChange(view.copy(sort = s)); onClose() },
                trailingIcon = { if (view.sort == s) Icon(Icons.Check, null) },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(if (view.descending) "Descending" else "Ascending") },
            leadingIcon = { Icon(if (view.descending) Icons.ArrowDownward else Icons.ArrowUpward, null) },
            onClick = { onChange(view.copy(descending = !view.descending)); onClose() },
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    listing: Listing,
    onClose: () -> Unit,
    onAll: () -> Unit,
    onDownload: () -> Unit,
    onMove: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onOffline: () -> Unit,
) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = { IconAction(Icons.Close, "Clear selection", onClose) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        actions = {
            IconAction(Icons.SelectAll, "Select all", onAll)
            if (listing == Listing.Trash) {
                IconAction(Icons.SettingsBackupRestore, "Restore", onRestore)
                IconAction(Icons.DeleteForever, "Delete for ever", onDelete)
            } else {
                IconAction(Icons.Download, "Download", onDownload)
                IconAction(Icons.OfflinePin, "Keep offline", onOffline)
                if (listing.writable) {
                    IconAction(Icons.DriveFileMove, "Move", onMove)
                    IconAction(Icons.Delete, "Move to trash", onTrash)
                }
            }
        },
    )
}
