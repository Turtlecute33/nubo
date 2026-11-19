package app.filenza.ui.links

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.filenza.api.ApiException
import app.filenza.app
import app.filenza.data.AccountState
import app.filenza.drive.Item
import app.filenza.drive.Links
import app.filenza.drive.Opened
import app.filenza.drive.PasswordNeeded
import app.filenza.media.OpenWith
import app.filenza.ui.Icons
import app.filenza.ui.components.EmptyState
import app.filenza.ui.components.IconAction
import app.filenza.ui.components.LoadingBox
import app.filenza.ui.components.PrivateTextInput
import app.filenza.ui.components.userText
import app.filenza.ui.nav.LocalNav
import app.filenza.ui.nav.Navigator
import app.filenza.ui.nav.Route
import app.filenza.ui.viewer.ViewerScreen
import app.filenza.ui.viewer.Viewers
import kotlinx.coroutines.CancellationException
import java.security.GeneralSecurityException

/**
 * Opens a public Filen link as a visitor (research F-73, F-75): no account and no API key go to the server,
 * and the key from the link stays in memory. Works logged in (a route in a tab) and logged out (over the
 * login screen, where no routes exist: the viewer then shows over this screen). A folder link has its own
 * stack of folders; Back goes up one folder, then closes.
 */
@Composable
fun PublicLinkScreen(link: Links.Parsed, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val nav = LocalNav.current
    val account by app.account.state.collectAsStateWithLifecycle()
    val loggedIn = account is AccountState.LoggedIn
    // Kept for the process: the return from the viewer does not open the link (and ask its password) again.
    val session = remember(link.uuid) { LinkSessions.get(link) }
    var opened by session::opened
    val stack = session.stack
    var needPassword by remember(link.uuid) { mutableStateOf(false) }
    var password by remember(link.uuid) { mutableStateOf("") }
    var error by remember(link.uuid) { mutableStateOf<String?>(null) }
    var attempt by remember(link.uuid) { mutableIntStateOf(0) }
    var busy by remember(link.uuid) { mutableStateOf(true) }
    // Logged out: the viewer of this screen, with a navigator of its own so that its Back only closes it.
    val overlayNav = remember { Navigator() }
    var overlay by remember { mutableStateOf<Route.Viewer?>(null) }

    LaunchedEffect(link.uuid, attempt) {
        if (attempt == 0 && opened != null) {
            busy = false
            return@LaunchedEffect
        }
        busy = true
        try {
            val result = app.links.open(link, password.takeIf { needPassword && it.isNotEmpty() })
            opened = result
            if (result is Opened.Folder) {
                stack.clear()
                stack += result.root
            }
            error = null
            needPassword = false
            password = ""
        } catch (e: CancellationException) {
            throw e
        } catch (_: PasswordNeeded) {
            needPassword = true
            error = null
        } catch (e: Exception) {
            if (e is ApiException && e.code == "wrong_password") needPassword = true
            error = linkError(e)
        } finally {
            busy = false
        }
    }

    fun openFile(item: Item, siblings: List<Item>) {
        if (!Viewers.viewable(item)) return OpenWith.open(context, item)
        val (session, index) = Viewers.open(siblings, item)
        if (loggedIn) {
            nav.go(Route.Viewer(session, index))
        } else {
            val route = Route.Viewer(session, index)
            overlayNav.go(route)
            overlay = route
        }
    }

    // Logged in at the root, the tab's Back (with its predictive animation) closes the screen.
    BackHandler(enabled = overlay == null && (stack.size > 1 || !loggedIn)) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onClose()
    }

    val snackbar = remember { SnackbarHostState() }
    if (!loggedIn) {
        // No tab scaffold (and no snackbar host) while logged out: show the app messages here.
        LaunchedEffect(Unit) { app.messenger.messages.collect { snackbar.showSnackbar(it.text, withDismissAction = true) } }
    }

    Scaffold(
        topBar = {
            val title = when (val o = opened) {
                is Opened.File -> o.item.name
                is Opened.Folder -> stack.lastOrNull()?.name ?: o.root.name
                null -> "Public link"
            }
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
                navigationIcon = {
                    if (stack.size > 1) IconAction(Icons.ArrowBack, "Up", { stack.removeAt(stack.lastIndex) })
                    else IconAction(if (loggedIn) Icons.ArrowBack else Icons.Close, "Close", onClose)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (val o = opened) {
                is Opened.File -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    LinkHeader()
                    FileCard(o.item) { openFile(o.item, listOf(o.item)) }
                }
                is Opened.Folder -> {
                    val current = stack.lastOrNull() ?: o.root
                    LinkFolder(
                        link = o.link,
                        uuid = current.uuid,
                        onFolder = { stack += it },
                        onFile = ::openFile,
                    )
                }
                null -> when {
                    needPassword -> PasswordForm(
                        password = password,
                        onPassword = { password = it; error = null },
                        error = error,
                        busy = busy,
                        onOpen = { attempt++ },
                    )
                    busy -> LoadingBox(Modifier.fillMaxSize())
                    else -> EmptyState(
                        Icons.LinkOff,
                        "Cannot open the link",
                        Modifier.fillMaxSize(),
                        message = error,
                        actionLabel = "Try again",
                        onAction = { attempt++ },
                    )
                }
            }
        }
    }

    overlay?.let { route ->
        CompositionLocalProvider(LocalNav provides overlayNav) {
            BackHandler { overlayNav.back() }
            Box(Modifier.fillMaxSize()) { ViewerScreen(route.session, route.index) }
        }
        // The viewer closes itself with nav.back(): its route leaves the stack of the overlay navigator.
        LaunchedEffect(route) { snapshotFlow { overlayNav.current }.collect { if (it != route) overlay = null } }
    }
}

@Composable
private fun PasswordForm(password: String, onPassword: (String) -> Unit, error: String?, busy: Boolean, onOpen: () -> Unit) {
    val ready = password.isNotEmpty() && !busy
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinkHeader()
        Spacer(Modifier.height(24.dp))
        EmptyState(Icons.Lock, "This link has a password", message = "Ask the person who sent the link for it.")
        PrivateTextInput {
            OutlinedTextField(
                value = password,
                onValueChange = { onPassword(it.take(256)) },
                label = { Text("Link password") },
                singleLine = true,
                enabled = !busy,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (ready) onOpen() }),
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpen, enabled = ready, modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(52.dp)) {
            if (busy) LoadingIndicator(Modifier.size(26.dp), color = MaterialTheme.colorScheme.onPrimary) else Text("Open")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "The password is checked with a hash. It does not leave this phone as text.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** One open link: what it opened and the folder stack. In memory only. */
@Stable
private class LinkSession {
    var opened by mutableStateOf<Opened?>(null)
    val stack = mutableStateListOf<Item>()
}

/** The last [MAX] open links of this process. Memory only; the key of a link never goes to the disk. */
private object LinkSessions {
    private const val MAX = 4
    private val map = object : LinkedHashMap<String, LinkSession>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkSession>) = size > MAX
    }

    @Synchronized
    fun get(link: Links.Parsed): LinkSession = map.getOrPut(link.uuid + ":" + link.key) { LinkSession() }
}

/** User text for a link that does not open. Never shows the link or its key. */
private fun linkError(e: Throwable): String = when {
    e is ApiException && e.code == "wrong_password" -> "The link password is wrong"
    e is ApiException && (e.http == 404 || "not_found" in e.code || "expired" in e.code || "disabled" in e.code) ->
        "This link does not exist any more, or it has expired"
    e is GeneralSecurityException -> "The key in the link is wrong. Check that you have the whole link."
    else -> userText(e)
}
