package app.nubo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.nubo.NuboApp
import app.nubo.api.ApiException
import app.nubo.app
import app.nubo.chat.Message
import app.nubo.ui.Icons
import app.nubo.ui.act
import app.nubo.ui.components.ConfirmDialog
import app.nubo.ui.components.EmptyState
import app.nubo.ui.components.ErrorState
import app.nubo.ui.components.IconAction
import app.nubo.ui.components.LoadingBox
import app.nubo.ui.components.PrivateTextInput
import app.nubo.ui.components.SheetAction
import app.nubo.ui.components.userText
import app.nubo.ui.nav.LocalNav
import app.nubo.ui.share.SecretClip
import app.nubo.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One open chat: the messages (oldest first), older pages on scroll, and the composer. Sends run in the app scope:
 * Back must not stop one halfway. DESIGN §13.
 */
@Stable
class ChatModel(private val app: NuboApp, private val uuid: String, private val scope: CoroutineScope) {
    var conversation by mutableStateOf(app.chats.find(uuid))
        private set
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loadingOlder by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    private var end = false

    var replyTo by mutableStateOf<Message?>(null)
    var editing by mutableStateOf<Message?>(null)
        private set
    val input = TextFieldState()

    suspend fun load() {
        error = null
        try {
            val c = conversation ?: app.chats.conversations().firstOrNull { it.uuid == uuid }?.also { conversation = it } ?: throw ApiException("file_not_found", 404)
            merge(app.chats.messages(c))
            loaded = true
            runCatching { app.chats.markRead(c) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = userText(e)
        }
    }

    /** Reads the newest page again and the chat (its name can change). New messages of others are read now. */
    suspend fun poll() {
        val c = conversation ?: return load()
        val fresh = runCatching { merge(app.chats.messages(c)) }.getOrDefault(false)
        app.chats.find(uuid)?.let { conversation = it }
        if (fresh) runCatching { app.chats.markRead(c) }
    }

    /** The next older page (C-11): the cursor is the oldest message; a page with nothing new is the end. */
    fun older() {
        val c = conversation ?: return
        val oldest = messages.firstOrNull() ?: return
        if (end || loadingOlder || !loaded) return
        loadingOlder = true
        scope.launch {
            try {
                val have = messages.mapTo(HashSet()) { it.uuid }
                val page = app.chats.messages(c, oldest.sent).filter { it.uuid !in have }
                if (page.isEmpty()) end = true else messages = page + messages
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The next scroll tries again.
            } finally {
                loadingOlder = false
            }
        }
    }

    fun send() {
        val c = conversation ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty() || sending) return
        sending = true
        val edit = editing
        val reply = replyTo
        app.scope.launch {
            try {
                if (edit != null) {
                    app.chats.edit(c, edit, text)
                    messages = messages.map { if (it.uuid == edit.uuid) it.copy(text = text, edited = true) else it }
                    editing = null
                } else {
                    val m = app.chats.send(c, text, reply)
                    messages = messages.filter { it.uuid != m.uuid } + m
                    if (replyTo == reply) replyTo = null
                }
                // Text that the user typed during the send stays.
                if (input.text.toString().trim() == text) input.clearText()
                runCatching { app.chats.markRead(c) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.messenger.show("Not sent. " + userText(e))
            } finally {
                sending = false
            }
        }
    }

    /** The chat again after a change in its menu (name, mute). */
    suspend fun reload() {
        runCatching { app.chats.conversations().firstOrNull { it.uuid == uuid } }.getOrNull()?.let { conversation = it }
    }

    fun startEdit(m: Message) {
        editing = m
        replyTo = null
        input.setTextAndPlaceCursorAtEnd(m.text.orEmpty())
    }

    fun cancel() {
        if (editing != null) input.clearText()
        editing = null
        replyTo = null
    }

    fun delete(m: Message) = app.act {
        app.chats.delete(m)
        messages = messages.filter { it.uuid != m.uuid }
    }

    /**
     * The newest page takes the place of its own time window; older loaded messages stay (C-43). Returns true when
     * the page has a message of another user that was not here.
     */
    private fun merge(page: List<Message>): Boolean {
        val have = messages.mapTo(HashSet()) { it.uuid }
        val from = page.firstOrNull()?.sent
        val fresh = page.mapTo(HashSet()) { it.uuid }
        messages = if (from == null) emptyList() else messages.filter { it.sent < from && it.uuid !in fresh } + page
        return page.any { !it.mine && it.uuid !in have }
    }
}

@Composable
fun ChatScreen(uuid: String) {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val model = remember(uuid) { ChatModel(app, uuid, scope) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val list = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Message?>(null) }
    // Checks for news only while the chat shows (C-41).
    LaunchedEffect(model) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (model.loaded) model.poll() else model.load()
            app.chats.watch { model.poll() }
        }
    }
    LaunchedEffect(model) { app.chats.changed.collect { model.reload() } }
    // The list is upside down: index 0 is the newest message. Near the oldest one, read the next page.
    LaunchedEffect(model, list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= model.messages.size - 5) model.older()
        }
    }
    val c = model.conversation
    val messages = model.messages
    val group = (c?.others?.size ?: 0) > 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(c?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (c != null && (group || c.name.isNotBlank())) {
                            Text(
                                c.others.joinToString(", ") { it.name }.ifEmpty { "Only you" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = { IconAction(Icons.ArrowBack, "Back", nav::back) },
                actions = { if (c != null) IconAction(Icons.MoreVert, "More", { menu = true }) },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !model.loaded && model.error != null -> ErrorState(model.error.orEmpty(), { scope.launch { model.load() } }, Modifier.fillMaxSize())
                    !model.loaded -> LoadingBox(Modifier.fillMaxSize())
                    c?.key == null -> EmptyState(Icons.Lock, "Cannot decrypt this chat", Modifier.fillMaxSize())
                    messages.isEmpty() -> EmptyState(Icons.Chat, "No messages", Modifier.fillMaxSize())
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        state = list,
                        reverseLayout = true,
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(messages.size, key = { messages[messages.lastIndex - it].uuid }) { i ->
                            val m = messages[messages.lastIndex - i]
                            val before = messages.getOrNull(messages.lastIndex - i - 1)
                            Bubble(
                                m,
                                showSender = group && !m.mine && before?.sender?.id != m.sender.id,
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    selected = m
                                },
                            )
                        }
                        if (model.loadingOlder) item(key = "older") { LoadingBox(Modifier.padding(16.dp)) }
                    }
                }
            }
            if (c?.key != null && model.loaded) Composer(model)
        }
    }
    if (menu && c != null) ChatMenu(c, onGone = nav::back) { menu = false }
    selected?.let { m -> MessageMenu(m, model) { selected = null } }
}

@Composable
private fun Bubble(m: Message, showSender: Boolean, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = if (m.mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    val content = if (m.mine) colors.onPrimaryContainer else colors.onSurface
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
        if (showSender) Text(m.sender.name, style = MaterialTheme.typography.labelMedium, color = colors.primary, modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 2.dp))
        Surface(
            shape = shape,
            color = if (m.mine) colors.primaryContainer else colors.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 320.dp).clip(shape).combinedClickable(onClick = {}, onLongClick = onLongClick, onLongClickLabel = "Message menu"),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                m.reply?.let { r ->
                    Column(Modifier.background(content.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(r.sender, style = MaterialTheme.typography.labelMedium, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.text ?: "Cannot decrypt this message", style = MaterialTheme.typography.bodySmall, color = content, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    m.text ?: "Cannot decrypt this message",
                    style = MaterialTheme.typography.bodyLarge,
                    color = content,
                    fontStyle = if (m.text == null) FontStyle.Italic else null,
                )
                Text(
                    Format.time(m.sent) + if (m.edited) " · edited" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = content.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun Composer(model: ChatModel) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainer)) {
        val edit = model.editing
        val reply = model.replyTo
        if (edit != null || reply != null) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (edit != null) Icons.Edit else Icons.Reply, null, tint = colors.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (edit != null) "Edit message" else "Reply to ${reply?.sender?.name.orEmpty()}", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    Text((edit ?: reply)?.text.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconAction(Icons.Close, "Cancel", model::cancel)
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            // Messages are private: the keyboard must not learn them (DESIGN §7).
            PrivateTextInput {
                OutlinedTextField(
                    state = model.input,
                    placeholder = { Text("Message") },
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(8.dp))
            val ready = !model.sending && model.input.text.isNotBlank()
            FilledIconButton(onClick = model::send, enabled = ready, modifier = Modifier.padding(bottom = 4.dp)) {
                if (model.sending) LoadingIndicator() else Icon(if (edit != null) Icons.Check else Icons.Send, if (edit != null) "Save" else "Send")
            }
        }
    }
}

@Composable
private fun MessageMenu(m: Message, model: ChatModel, close: () -> Unit) {
    val context = LocalContext.current
    var deleting by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(true) }
    fun tap(block: () -> Unit) = { sheet = false; block() }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = close) {
            Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
                Text(m.text ?: "Cannot decrypt this message", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp))
                HorizontalDivider()
                if (m.text != null) {
                    SheetAction(Icons.Reply, "Reply", tap { close(); model.replyTo = m })
                    // A sensitive clip that the app clears again (DESIGN §7).
                    SheetAction(Icons.ContentCopy, "Copy", tap { close(); SecretClip.copy(context, "Message", m.text) })
                }
                if (m.mine && m.text != null) SheetAction(Icons.Edit, "Edit", tap { close(); model.startEdit(m) })
                if (m.mine) SheetAction(Icons.Delete, "Delete", tap { deleting = true }, destructive = true)
            }
        }
    }
    if (deleting) {
        ConfirmDialog("Delete this message?", "It will be deleted for all participants.", "Delete", { model.delete(m) }, { deleting = false; close() }, destructive = true)
    }
}
