package app.nubo.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.nubo.NuboApp
import app.nubo.api.ApiException
import app.nubo.app
import app.nubo.notes.Check
import app.nubo.notes.Note
import app.nubo.notes.NoteConflict
import app.nubo.notes.NoteContent
import app.nubo.notes.NoteText
import app.nubo.notes.NoteType
import app.nubo.ui.Icons
import app.nubo.ui.act
import app.nubo.ui.components.ErrorState
import app.nubo.ui.components.IconAction
import app.nubo.ui.components.LoadingBox
import app.nubo.ui.components.NameDialog
import app.nubo.ui.components.PrivateTextInput
import app.nubo.ui.components.userText
import app.nubo.ui.nav.LocalNav
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One checklist row in the editor. */
@Stable
internal class CheckRow(val id: Long, text: String, done: Boolean) {
    val field = TextFieldState(text)
    var done by mutableStateOf(done)
}

internal enum class SaveState { IDLE, SAVING, SAVED, FAILED }

/**
 * The open note: its content, the edits, and the saves. A save runs a short time after the last edit, when the
 * app goes to the background, and when the editor closes. Saves run in the app scope, one at a time. DESIGN §12.
 */
@Stable
internal class NoteEditor(private val app: NuboApp, private val uuid: String) {
    var note by mutableStateOf(app.notes.find(uuid))
        private set

    /** The type of the loaded content; the list can be older. */
    var type by mutableStateOf<NoteType?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var state by mutableStateOf(SaveState.IDLE)
        private set

    val body = TextFieldState()
    val rows = mutableStateListOf<CheckRow>()

    /** The row that gets the focus next. */
    var focus by mutableStateOf<Long?>(null)

    /** The content as the server gave it: rich text and unknown types show it read-only. */
    var raw by mutableStateOf("")
        private set

    /** The server text that the edits start from: a save stops when the server has other text (N-69). */
    private var base = ""

    /** [current] right after the load or the last save: a save runs only when the text is different. */
    private var clean = ""
    private val saves = Mutex()
    private var nextId = 0L

    val editable: Boolean get() = loaded && note?.editable == true && type in NoteType.writable

    suspend fun load() {
        error = null
        try {
            val n = note ?: app.notes.list().firstOrNull { it.uuid == uuid }?.also { note = it } ?: throw ApiException("file_not_found", 404)
            show(app.notes.content(n))
            loaded = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = userText(e)
        }
    }

    /** Reads the note flags and title again after a menu action; a new type loads the content again. */
    suspend fun watch(): Nothing = app.notes.changed.collect {
        val n = runCatching { app.notes.list().firstOrNull { it.uuid == uuid } }.getOrNull() ?: return@collect
        val retype = loaded && n.type != type
        note = n
        if (retype) saves.withLock { runCatching { show(app.notes.content(n)) } }
    }

    /** The content in the server format of [type]. */
    fun current(): String = when (type) {
        NoteType.CHECKLIST -> NoteText.writeChecklist(rows.map { Check(it.field.text.toString().replace('\n', ' '), it.done) })
        NoteType.TEXT, NoteType.MD, NoteType.CODE -> body.text.toString()
        else -> raw
    }

    fun save(): Job = app.scope.launch { saves.withLock { push() } }

    /** Saves the edits first, so that the type change converts the newest text. */
    fun changeType(to: NoteType) {
        app.scope.launch {
            saves.withLock {
                push()
                val n = note ?: return@withLock
                if (state == SaveState.FAILED) return@withLock
                try {
                    app.notes.changeType(n, to)
                    note = n.copy(type = to)
                    show(app.notes.content(n))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    app.messenger.show(userText(e))
                }
            }
        }
    }

    fun addRow(after: CheckRow?) {
        val at = after?.let { rows.indexOf(it) + 1 } ?: rows.size
        val row = CheckRow(nextId++, "", false)
        rows.add(at.coerceIn(0, rows.size), row)
        focus = row.id
    }

    fun removeRow(row: CheckRow) {
        rows.remove(row)
        if (rows.isEmpty()) addRow(null)
    }

    private fun show(c: NoteContent) {
        type = c.type ?: note?.type
        base = c.text
        raw = c.text
        if (type == NoteType.CHECKLIST) {
            rows.clear()
            NoteText.parseChecklist(c.text).ifEmpty { listOf(Check("", false)) }.forEach { rows += CheckRow(nextId++, it.text, it.done) }
        } else {
            body.setTextAndPlaceCursorAtEnd(c.text)
        }
        clean = current()
    }

    private suspend fun push() {
        val n = note ?: return
        val t = type ?: return
        if (!editable) return
        val text = current()
        if (text == clean) return
        state = SaveState.SAVING
        try {
            app.notes.save(n, t, text, base)
            base = text
            clean = text
            state = SaveState.SAVED
        } catch (c: NoteConflict) {
            // Keep both (N-69): this text goes to a new note, and the editor shows the text of the other device.
            try {
                app.notes.create(NoteText.cut(n.title.ifBlank { "Untitled" }, 240) + " (conflict)", t, text)
                show(c.remote)
                state = SaveState.SAVED
                app.messenger.show("The note changed on another device. Your text is in a new note.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = SaveState.FAILED
                app.messenger.show(userText(e))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = SaveState.FAILED
            app.messenger.show(userText(e))
        }
    }
}

/** A note: text, Markdown and code in one text field; a checklist as rows; rich text read-only. */
@Composable
fun NoteScreen(uuid: String) {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val editor = remember(uuid) { NoteEditor(app, uuid) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    LaunchedEffect(editor) { if (!editor.loaded) editor.load() }
    LaunchedEffect(editor) {
        snapshotFlow { editor.current() }.collectLatest {
            delay(SAVE_DELAY_MS)
            editor.save()
        }
    }
    LaunchedEffect(editor) { editor.watch() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { editor.save() }
    DisposableEffect(editor) { onDispose { editor.save() } }
    val note = editor.note

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val canRename = note != null && note.canWrite && note.key != null && !note.trash
                    Column(Modifier.clickable(enabled = canRename, onClickLabel = "Rename") { renaming = true }) {
                        Text(note?.label.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        status(editor, note)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                navigationIcon = { IconAction(Icons.ArrowBack, "Back", nav::back) },
                actions = {
                    if (editor.state == SaveState.FAILED) IconAction(Icons.Replay, "Save again", { editor.save() })
                    if (note != null) IconAction(Icons.MoreVert, "More", { editor.save(); menu = true })
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            when {
                !editor.loaded && editor.error != null -> ErrorState(editor.error.orEmpty(), { app.scope.launch { editor.load() } }, Modifier.fillMaxSize())
                !editor.loaded -> LoadingBox(Modifier.fillMaxSize())
                editor.type == NoteType.CHECKLIST -> Checklist(editor)
                editor.type == NoteType.TEXT || editor.type == NoteType.MD || editor.type == NoteType.CODE -> TextBody(editor)
                else -> ReadOnly(editor.raw, editor.type)
            }
        }
    }
    if (menu && note != null) NoteMenu(note, onOpen = null, onDeleted = nav::back, changeType = editor::changeType) { menu = false }
    if (renaming && note != null) {
        NameDialog("Rename note", note.title, "Rename", { t -> app.act { app.notes.rename(note, t) } }, { renaming = false }, valid = ::validTitle)
    }
}

private fun status(editor: NoteEditor, note: Note?): String? = when {
    note == null || !editor.loaded -> null
    editor.state == SaveState.SAVING -> "Saving…"
    editor.state == SaveState.FAILED -> "Not saved"
    note.trash -> "In the trash · read only"
    !note.canWrite -> "Read only"
    editor.type == NoteType.RICH -> "Rich text · read only"
    editor.type == null -> "Read only"
    editor.state == SaveState.SAVED -> "Saved"
    else -> editor.type?.label
}

@Composable
private fun TextBody(editor: NoteEditor) {
    val code = editor.type == NoteType.CODE
    val colors = MaterialTheme.colorScheme
    val style = if (code) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge
    // Note text is private: the keyboard must not learn it (DESIGN §7).
    PrivateTextInput {
        BasicTextField(
            state = editor.body,
            readOnly = !editor.editable,
            textStyle = style.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = if (code) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                autoCorrectEnabled = !code,
            ),
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
            decorator = { inner ->
                Box {
                    if (editor.body.text.isEmpty() && editor.editable) Text("Write here", style = style, color = colors.onSurfaceVariant)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun Checklist(editor: NoteEditor) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)) {
        items(editor.rows, key = { it.id }) { row -> CheckItem(editor, row, Modifier.animateItem()) }
        if (editor.editable) {
            item(key = "add") {
                Row(
                    Modifier.fillMaxWidth().clickable(onClickLabel = "Add item") { editor.addRow(null) }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Add item", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp))
                }
            }
        }
    }
}

@Composable
private fun CheckItem(editor: NoteEditor, row: CheckRow, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(editor.focus) {
        if (editor.focus == row.id) {
            focus.requestFocus()
            editor.focus = null
        }
    }
    val style: TextStyle = MaterialTheme.typography.bodyLarge.let {
        if (row.done) it.copy(color = colors.onSurfaceVariant, textDecoration = TextDecoration.LineThrough) else it.copy(color = colors.onSurface)
    }
    Row(modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = row.done, onCheckedChange = if (editor.editable) ({ row.done = it }) else null)
        PrivateTextInput {
            BasicTextField(
                state = row.field,
                readOnly = !editor.editable,
                lineLimits = TextFieldLineLimits.SingleLine,
                textStyle = style,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                onKeyboardAction = { editor.addRow(row) },
                modifier = Modifier.weight(1f).padding(vertical = 12.dp).focusRequester(focus),
            )
        }
        if (editor.editable) IconAction(Icons.Close, "Remove item", { editor.removeRow(row) })
    }
}

/** Rich text and unknown types: the text only, never the HTML (N-93). The top bar tells that the note is read only. */
@Composable
private fun ReadOnly(raw: String, type: NoteType?) {
    val text = remember(raw, type) { if (type == NoteType.RICH) NoteText.plain(raw) else raw }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
    }
}

/** The time after the last edit before a save (DESIGN §12). */
private const val SAVE_DELAY_MS = 1_500L
