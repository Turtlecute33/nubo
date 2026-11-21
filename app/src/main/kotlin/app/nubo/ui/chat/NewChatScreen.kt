package app.nubo.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nubo.NuboApp
import app.nubo.app
import app.nubo.chat.Contact
import app.nubo.chat.ContactRequest
import app.nubo.ui.Icons
import app.nubo.ui.act
import app.nubo.ui.components.EmptyState
import app.nubo.ui.components.ErrorState
import app.nubo.ui.components.IconAction
import app.nubo.ui.components.LoadingBox
import app.nubo.ui.components.NameDialog
import app.nubo.ui.components.SectionHeader
import app.nubo.ui.components.userText
import app.nubo.ui.nav.LocalNav
import app.nubo.ui.nav.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
class ContactsModel(private val app: NuboApp, private val scope: CoroutineScope) {
    var contacts by mutableStateOf<List<Contact>?>(null)
        private set
    var requests by mutableStateOf<List<ContactRequest>>(emptyList())
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
            contacts = app.chats.contacts()
            requests = app.chats.requests()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = userText(e)
        }
        refreshing = false
    }
}

/**
 * Start a chat with one or more contacts. Only contacts can join a chat (C-22): this screen also sends contact
 * requests by email and answers the requests of others. DESIGN §13.
 */
@Composable
fun NewChatScreen() {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val model = remember { ContactsModel(app, scope) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var adding by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    LaunchedEffect(model) { model.load() }
    LaunchedEffect(model) { app.chats.changed.collect { model.load() } }
    val contacts = model.contacts
    val incoming = model.requests.filter { it.incoming }
    val outgoing = model.requests.filter { !it.incoming }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("New chat") },
                subtitle = { Text("Pick one or more contacts") },
                navigationIcon = { IconAction(Icons.ArrowBack, "Back", nav::back) },
                actions = { IconAction(Icons.PersonAdd, "Add a contact", { adding = true }) },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (selected.isNotEmpty() && contacts != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (starting) return@ExtendedFloatingActionButton
                        starting = true
                        val picked = contacts.filter { it.uuid in selected }
                        app.act {
                            try {
                                val uuid = app.chats.create(picked)
                                // The chat screen finds the new chat in the list.
                                app.chats.conversations()
                                nav.back()
                                nav.go(Route.Chat(uuid))
                            } finally {
                                starting = false
                            }
                        }
                    },
                    icon = { Icon(Icons.Chat, null) },
                    text = { Text(if (starting) "Starting…" else "Start chat") },
                    // Material 3 1.5 hides the text of an extended button from screen readers.
                    modifier = Modifier.semantics { contentDescription = if (starting) "Starting" else "Start chat" },
                )
            }
        },
    ) { pad ->
        val refresh = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = model.refreshing && contacts != null,
            onRefresh = model::refresh,
            state = refresh,
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            indicator = { PullToRefreshDefaults.LoadingIndicator(state = refresh, isRefreshing = model.refreshing && contacts != null, modifier = Modifier.align(Alignment.TopCenter)) },
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                if (incoming.isNotEmpty()) {
                    item(key = "h-in") { SectionHeader("Contact requests") }
                    items(incoming, key = { "in-" + it.uuid }) { r ->
                        ListItem(
                            leadingContent = { Initial(r.person.name, 40.dp) },
                            supportingContent = r.person.nick.takeIf { it.isNotBlank() }?.let { { Text(r.person.email, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                            trailingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { app.act { app.chats.answer(r, accept = false) } }) { Text("Deny") }
                                    FilledTonalButton(onClick = { app.act(done = "Contact added") { app.chats.answer(r, accept = true) } }) { Text("Accept") }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        ) { Text(r.person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                when {
                    contacts == null && model.error != null -> item(key = "error") { ErrorState(model.error.orEmpty(), model::refresh, Modifier.fillMaxWidth()) }
                    contacts == null -> item(key = "loading") { LoadingBox(Modifier.height(240.dp)) }
                    contacts.isEmpty() -> item(key = "empty") {
                        EmptyState(
                            Icons.PersonAdd,
                            "No contacts",
                            Modifier.fillMaxWidth(),
                            message = "Add a contact with the email of their Filen account.",
                            actionLabel = "Add a contact",
                            onAction = { adding = true },
                        )
                    }
                    else -> {
                        item(key = "h-contacts") { SectionHeader("Contacts") }
                        items(contacts, key = { it.uuid }) { contact ->
                            val on = contact.uuid in selected
                            val toggle = { selected = if (on) selected - contact.uuid else selected + contact.uuid }
                            ListItem(
                                onClick = toggle,
                                leadingContent = { Initial(contact.person.name, 40.dp) },
                                supportingContent = contact.person.nick.takeIf { it.isNotBlank() }?.let { { Text(contact.person.email, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                                trailingContent = { Checkbox(checked = on, onCheckedChange = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            ) { Text(contact.person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
                if (outgoing.isNotEmpty()) {
                    item(key = "h-out") { SectionHeader("Sent requests") }
                    items(outgoing, key = { "out-" + it.uuid }) { r ->
                        ListItem(
                            leadingContent = { Initial(r.person.name, 40.dp) },
                            supportingContent = { Text("Pending") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        ) { Text(r.person.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
    if (adding) {
        NameDialog(
            "Add a contact",
            "",
            "Send request",
            { email -> app.act(done = "Request sent") { app.chats.request(email.lowercase()) } },
            { adding = false },
            valid = { it.length in 3..255 && '@' in it && it.none(Char::isWhitespace) },
        )
    }
}
