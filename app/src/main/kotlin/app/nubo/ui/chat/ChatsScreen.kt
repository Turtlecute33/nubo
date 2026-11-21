package app.nubo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.nubo.NuboApp
import app.nubo.app
import app.nubo.chat.Conversation
import app.nubo.ui.Icons
import app.nubo.ui.act
import app.nubo.ui.components.ConfirmDialog
import app.nubo.ui.components.EmptyState
import app.nubo.ui.components.ErrorState
import app.nubo.ui.components.IconAction
import app.nubo.ui.components.LoadingBox
import app.nubo.ui.components.NameDialog
import app.nubo.ui.components.SheetAction
import app.nubo.ui.components.userText
import app.nubo.ui.nav.LocalNav
import app.nubo.ui.nav.Route
import app.nubo.util.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
class ChatsModel(private val app: NuboApp, private val scope: CoroutineScope) {
    var list by mutableStateOf(app.chats.cached)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun refresh() {
        scope.launch { load() }
    }

    suspend fun load() {
        refreshing = true
        error = null
        try {
            list = app.chats.conversations()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = userText(e)
        }
        refreshing = false
    }
}

/** The chats of the account, newest first. The list checks for news only while it shows (C-41). DESIGN §13. */
@Composable
fun ChatsScreen() {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val model = remember { ChatsModel(app, scope) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var menu by remember { mutableStateOf<Conversation?>(null) }
    LaunchedEffect(model) { app.chats.changed.collect { model.load() } }
    LaunchedEffect(model) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.load()
            app.chats.watch { model.load() }
        }
    }
    val list = model.list
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Chats") },
                subtitle = { Text("End-to-end encrypted") },
                navigationIcon = { IconAction(Icons.ArrowBack, "Back", nav::back) },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.go(Route.NewChat) }, icon = { Icon(Icons.Chat, null) }, text = { Text("New chat") })
        },
    ) { pad ->
        val refresh = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = model.refreshing && list != null,
            onRefresh = model::refresh,
            state = refresh,
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            indicator = { PullToRefreshDefaults.LoadingIndicator(state = refresh, isRefreshing = model.refreshing && list != null, modifier = Modifier.align(Alignment.TopCenter)) },
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                when {
                    list == null && model.error != null -> item(key = "error") { ErrorState(model.error.orEmpty(), model::refresh, Modifier.fillMaxWidth()) }
                    list == null -> item(key = "loading") { LoadingBox(Modifier.height(240.dp)) }
                    list.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            Icons.Forum,
                            "No chats",
                            Modifier.fillMaxWidth().height(320.dp),
                            message = "Chat with your Filen contacts. Messages are encrypted on this phone.",
                        )
                    }
                    else -> items(list, key = { it.uuid }) { c ->
                        ConversationRow(
                            c,
                            onClick = { nav.go(Route.Chat(c.uuid)) },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menu = c
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
    menu?.let { ChatMenu(it) { menu = null } }
}

@Composable
private fun ConversationRow(c: Conversation, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val last = c.last
    val preview = when {
        c.key == null -> "Cannot decrypt this chat"
        last == null -> "No messages"
        else -> (if (last.mine) "You: " else if (c.others.size > 1) last.sender.name + ": " else "") + (last.text ?: "Cannot decrypt this message")
    }
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Chat menu").padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Initial(c.title, 48.dp, group = c.others.size > 1)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.titleMedium, fontWeight = if (c.unread) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(preview.replace('\n', ' '), style = MaterialTheme.typography.bodyMedium, color = if (c.unread) colors.onSurface else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(Format.time(c.time), style = MaterialTheme.typography.labelSmall, color = if (c.unread) colors.primary else colors.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            when {
                c.muted -> Icon(Icons.NotificationsOff, "Muted", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                c.unread -> Box(Modifier.size(10.dp).background(colors.primary, CircleShape))
            }
        }
    }
}

/** The first letter of [name] in a soft shape. Nubo never loads avatars (C-82). */
@Composable
internal fun Initial(name: String, size: Dp, group: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(size).background(if (group) colors.tertiaryContainer else colors.primaryContainer, (if (group) MaterialShapes.Cookie6Sided else MaterialShapes.Circle).toShape())
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (group) Icon(Icons.Group, null, tint = colors.onTertiaryContainer)
        else Text(name.firstOrNull()?.uppercaseChar()?.toString() ?: "?", style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
    }
}

/** The menu of one chat: mute, and for the owner rename and delete; others leave. [onGone] runs after delete or leave. */
@Composable
internal fun ChatMenu(c: Conversation, onGone: () -> Unit = {}, close: () -> Unit) {
    val app = LocalContext.current.app
    val chats = app.chats
    var sheet by remember { mutableStateOf(true) }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    fun tap(block: () -> Unit) = { sheet = false; block() }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = close) {
            Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState())) {
                Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp))
                HorizontalDivider()
                SheetAction(
                    if (c.muted) Icons.Notifications else Icons.NotificationsOff,
                    if (c.muted) "Unmute" else "Mute in the Filen apps",
                    tap { close(); app.act { chats.mute(c, !c.muted) } },
                )
                if (c.owner && c.key != null) SheetAction(Icons.DriveFileRenameOutline, "Rename", tap { renaming = true })
                SheetAction(Icons.Logout.takeUnless { c.owner } ?: Icons.Delete, if (c.owner) "Delete chat" else "Leave chat", tap { removing = true }, destructive = true)
            }
        }
    }
    if (renaming) {
        NameDialog("Rename chat", c.name, "Rename", { n -> app.act { chats.rename(c, n) } }, { renaming = false; close() }, valid = { it.isNotEmpty() && it.length <= 255 })
    }
    if (removing) {
        ConfirmDialog(
            if (c.owner) "Delete this chat?" else "Leave this chat?",
            if (c.owner) "The chat and all its messages will be deleted for all participants." else "You will not get its messages any more.",
            if (c.owner) "Delete" else "Leave",
            { onGone(); app.act { chats.remove(c) } },
            { removing = false; close() },
            destructive = true,
        )
    }
}
