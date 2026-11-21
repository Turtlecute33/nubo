package app.nubo.ui.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nubo.NuboApp
import app.nubo.app
import app.nubo.notes.Note
import app.nubo.notes.NoteType
import app.nubo.ui.Icons
import app.nubo.ui.act
import app.nubo.ui.components.ConfirmDialog
import app.nubo.ui.components.EmptyState
import app.nubo.ui.components.ErrorState
import app.nubo.ui.components.IconAction
import app.nubo.ui.components.LoadingBox
import app.nubo.ui.components.NameDialog
import app.nubo.ui.components.PrivateTextInput
import app.nubo.ui.components.SheetAction
import app.nubo.ui.components.userText
import app.nubo.ui.nav.LocalNav
import app.nubo.ui.nav.Route
import app.nubo.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The four views of the note list. */
enum class NoteFilter(val label: String) { NOTES("Notes"), FAVOURITES("Favourites"), ARCHIVE("Archive"), TRASH("Trash") }

/** The note list: the last list at once, then the server's. Reads again after each write of this app. */
@Stable
class NotesModel(private val app: NuboApp, private val scope: CoroutineScope) {
    var notes by mutableStateOf(app.notes.cached)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        refreshing = true
        error = null
        job = scope.launch {
            try {
                notes = app.notes.list()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = userText(e)
            }
            refreshing = false
        }
    }

    suspend fun watch(): Nothing = app.notes.changed.collect { refresh() }
}

@Composable
private fun rememberNotes(): NotesModel {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val model = remember { NotesModel(app, scope) }
    LaunchedEffect(model) {
        model.refresh()
        model.watch()
    }
    return model
}

/** The notes of the account: four views, a search over titles and previews, and a new-note button. DESIGN §12. */
@Composable
fun NotesScreen() {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val haptics = LocalHapticFeedback.current
    val model = rememberNotes()
    var filter by rememberSaveable { mutableStateOf(NoteFilter.NOTES) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Note?>(null) }
    var creating by remember { mutableStateOf(false) }
    val all = model.notes
    // The filter and the order run off the main thread (review 101).
    val shown = produceState<List<Note>?>(null, all, filter, query) {
        value = all?.let { list -> withContext(Dispatchers.Default) { list.shownFor(filter, query) } }
    }.value
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Notes") },
                subtitle = shown?.let { { Text(if (it.size == 1) "1 note" else "${it.size} notes") } },
                navigationIcon = { IconAction(Icons.ArrowBack, "Back", nav::back) },
                actions = { IconAction(if (searching) Icons.Close else Icons.Search, if (searching) "Close search" else "Search", { searching = !searching; query = "" }) },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (filter == NoteFilter.NOTES || filter == NoteFilter.FAVOURITES) {
                ExtendedFloatingActionButton(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        creating = true
                    },
                    icon = { Icon(Icons.EditNote, null) },
                    text = { Text("New note") },
                )
            }
        },
    ) { pad ->
        val refresh = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = model.refreshing && all != null,
            onRefresh = model::refresh,
            state = refresh,
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            indicator = { PullToRefreshDefaults.LoadingIndicator(state = refresh, isRefreshing = model.refreshing && all != null, modifier = Modifier.align(Alignment.TopCenter)) },
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                item(key = "filters") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NoteFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
                    }
                }
                if (searching) item(key = "search") { SearchField(query) { query = it } }
                when {
                    all == null && model.error != null -> item(key = "error") { ErrorState(model.error.orEmpty(), model::refresh, Modifier.fillMaxWidth()) }
                    all == null || shown == null -> item(key = "loading") { LoadingBox(Modifier.height(240.dp)) }
                    shown.isEmpty() -> item(key = "empty") { EmptyFor(filter, query.isNotBlank()) }
                    else -> items(shown, key = { it.uuid }) { note ->
                        NoteRow(
                            note,
                            onClick = { nav.go(Route.Note(note.uuid)) },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menu = note
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
    menu?.let { NoteMenu(it, onOpen = { nav.go(Route.Note(it.uuid)) }) { menu = null } }
    if (creating) {
        NewNoteDialog(onDismiss = { creating = false }) { title, type ->
            app.act { nav.go(Route.Note(app.notes.create(title, type).uuid)) }
        }
    }
}

/** Pinned first, then the newest edit. A query matches the title and the preview. */
private fun List<Note>.shownFor(filter: NoteFilter, query: String): List<Note> {
    val q = query.trim()
    return filter { n ->
        when (filter) {
            NoteFilter.NOTES -> !n.trash && !n.archive
            NoteFilter.FAVOURITES -> n.favorite && !n.trash && !n.archive
            NoteFilter.ARCHIVE -> n.archive && !n.trash
            NoteFilter.TRASH -> n.trash
        } && (q.isEmpty() || n.title.contains(q, ignoreCase = true) || n.preview.contains(q, ignoreCase = true))
    }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.edited })
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Search text is private: the keyboard must not learn it (DESIGN §7).
    PrivateTextInput {
        OutlinedTextField(
            value = query,
            onValueChange = { onChange(it.take(200)) },
            placeholder = { Text("Search in notes") },
            leadingIcon = { Icon(Icons.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
        )
    }
}

@Composable
private fun EmptyFor(filter: NoteFilter, searching: Boolean) {
    val (title, text) = when {
        searching -> "No notes found" to null
        filter == NoteFilter.FAVOURITES -> "No favourite notes" to "Add notes to your favourites from their menu."
        filter == NoteFilter.ARCHIVE -> "The archive is empty" to null
        filter == NoteFilter.TRASH -> "The trash is empty" to null
        else -> "No notes" to "Tap New note to write one. Filen encrypts it on this phone."
    }
    EmptyState(Icons.StickyNote, title, Modifier.fillMaxWidth().height(320.dp), message = text)
}

/** The icon of each note type. */
internal fun NoteType?.icon(): ImageVector = when (this) {
    NoteType.CHECKLIST -> Icons.Checklist
    NoteType.MD -> Icons.Article
    NoteType.CODE -> Icons.Code
    NoteType.RICH -> Icons.Description
    else -> Icons.Notes
}

/** The title to show: an empty title, or a note with no readable key, gets a plain label. */
internal val Note.label: String get() = if (key == null) "Cannot decrypt this note" else title.ifBlank { "Untitled" }

@Composable
private fun NoteRow(note: Note, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Note menu").padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(40.dp).background(colors.secondaryContainer, MaterialShapes.Square.toShape()), contentAlignment = Alignment.Center) {
            Icon(note.type.icon(), null, Modifier.size(22.dp), tint = colors.onSecondaryContainer)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(note.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (note.pinned) Icon(Icons.KeepFill, "Pinned", Modifier.padding(start = 6.dp).size(16.dp), tint = colors.primary)
                if (note.favorite) Icon(Icons.StarFill, "Favourite", Modifier.padding(start = 4.dp).size(16.dp), tint = colors.primary)
                if (note.shared) Icon(Icons.Group, "Shared", Modifier.padding(start = 4.dp).size(16.dp), tint = colors.onSurfaceVariant)
            }
            if (note.preview.isNotBlank()) {
                Text(note.preview, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(Format.date(note.edited), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * The menu of one note, for the list and the editor. [onOpen] is null in the editor; the editor changes the type
 * itself ([changeType]), so that it saves its edits first.
 */
@Composable
internal fun NoteMenu(note: Note, onOpen: (() -> Unit)?, onDeleted: () -> Unit = {}, changeType: ((NoteType) -> Unit)? = null, close: () -> Unit) {
    val app = LocalContext.current.app
    val notes = app.notes
    val retype = changeType ?: { t -> app.act { notes.changeType(note, t) } }
    var renaming by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(true) }
    fun tap(block: () -> Unit) = { sheet = false; block() }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = close) {
            Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
                Text(note.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp))
                HorizontalDivider()
                if (onOpen != null && note.key != null) SheetAction(Icons.OpenInNew, "Open", tap { close(); onOpen() })
                if (note.trash) {
                    SheetAction(Icons.SettingsBackupRestore, "Restore", tap { close(); app.act(done = "Restored") { notes.restore(note) } })
                    if (note.owner) SheetAction(Icons.DeleteForever, "Delete for ever", tap { deleting = true }, destructive = true)
                } else {
                    SheetAction(if (note.pinned) Icons.KeepOff else Icons.Keep, if (note.pinned) "Unpin" else "Pin", tap { close(); app.act { notes.pin(note, !note.pinned) } })
                    SheetAction(
                        if (note.favorite) Icons.StarFill else Icons.Star,
                        if (note.favorite) "Remove from favourites" else "Add to favourites",
                        tap { close(); app.act { notes.favorite(note, !note.favorite) } },
                    )
                    if (note.canWrite && note.key != null) {
                        SheetAction(Icons.DriveFileRenameOutline, "Rename", tap { renaming = true })
                        if (note.type in NoteType.writable) SheetAction(note.type.icon(), "Change type", tap { typing = true })
                    }
                    if (note.owner) {
                        if (note.archive) SheetAction(Icons.Unarchive, "Move out of the archive", tap { close(); app.act(done = "Moved out of the archive") { notes.restore(note) } })
                        else SheetAction(Icons.Archive, "Archive", tap { close(); app.act(done = "Archived", undo = { notes.restore(note) }) { notes.archive(note) } })
                        SheetAction(Icons.Delete, "Move to trash", tap { close(); onDeleted(); app.act(done = "Moved to trash", undo = { notes.restore(note) }) { notes.trash(note) } }, destructive = true)
                    } else {
                        SheetAction(Icons.Logout, "Leave this shared note", tap { leaving = true }, destructive = true)
                    }
                }
            }
        }
    }
    if (renaming) {
        NameDialog("Rename note", note.title, "Rename", { t -> app.act { notes.rename(note, t) } }, { renaming = false; close() }, valid = ::validTitle)
    }
    if (typing) TypeDialog(note.type, onDismiss = { typing = false; close() }, onPick = retype)
    if (deleting) {
        ConfirmDialog("Delete for ever?", "The note and its history will be deleted. You cannot undo this.", "Delete", {
            onDeleted()
            app.act(done = "Deleted") { notes.delete(note) }
        }, { deleting = false; close() }, destructive = true)
    }
    if (leaving) {
        ConfirmDialog("Leave this note?", "You will not see it again until the owner adds you again.", "Leave", {
            onDeleted()
            app.act { notes.leave(note) }
        }, { leaving = false; close() }, destructive = true)
    }
}

/** A note title: not empty, one line. */
internal fun validTitle(t: String) = t.isNotEmpty() && t.length <= 255 && t.none { it == '\n' || it.isISOControl() }

@Composable
private fun TypeDialog(current: NoteType?, onDismiss: () -> Unit, onPick: (NoteType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note type") },
        text = {
            Column {
                Text(
                    "A checklist gets one row for each line. Other types keep the text.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                TypeChips(current) { if (it != current) onPick(it); onDismiss() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TypeChips(selected: NoteType?, onPick: (NoteType) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NoteType.writable.forEach { t ->
            FilterChip(selected = t == selected, onClick = { onPick(t) }, label = { Text(t.label) }, leadingIcon = { Icon(t.icon(), null, Modifier.size(18.dp)) })
        }
    }
}

@Composable
private fun NewNoteDialog(onDismiss: () -> Unit, onCreate: (String, NoteType) -> Unit) {
    var title by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(NoteType.TEXT) }
    val focus = remember { FocusRequester() }
    val valid = validTitle(title.trim())
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New note") },
        text = {
            Column {
                // Note titles are private: the keyboard must not learn them.
                PrivateTextInput {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(255) },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Spacer(Modifier.height(16.dp))
                TypeChips(type) { type = it }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onDismiss(); onCreate(title.trim(), type) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
