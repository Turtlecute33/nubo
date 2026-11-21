package app.nubo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nubo.app
import app.nubo.drive.Links
import app.nubo.drive.Listing
import app.nubo.drive.Usage
import app.nubo.ui.Icons
import app.nubo.ui.components.EmptyState
import app.nubo.ui.components.ErrorState
import app.nubo.ui.components.ItemRow
import app.nubo.ui.components.LoadingBox
import app.nubo.ui.components.PrivateTextInput
import app.nubo.ui.drive.ItemActionsHost
import app.nubo.ui.drive.rememberFolder
import app.nubo.ui.drive.rememberItemActions
import app.nubo.ui.me.rememberUsage
import app.nubo.ui.nav.LocalNav
import app.nubo.ui.nav.Route
import app.nubo.ui.nav.Tab
import app.nubo.ui.theme.AppTheme
import app.nubo.util.Format
import java.util.Calendar

private class Quick(val label: String, val icon: ImageVector, val shape: Shape, val color: Color, val open: () -> Unit)

/** Search, quick places, a storage warning when the drive is almost full, and the recent files. */
@Composable
fun HomeScreen() {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val email = app.account.session?.email.orEmpty()
    val recents = rememberFolder(Listing.Recents)
    val actions = rememberItemActions()
    val offline by app.offline.uuids.collectAsStateWithLifecycle()
    val usageModel = rememberUsage()
    val usage = usageModel.usage
    var linkDialog by remember { mutableStateOf(false) }
    val reload = {
        recents.refresh()
        usageModel.load(force = true)
    }
    val list = recents.items
    val files = remember(list) { list?.filter { !it.folder }?.sortedByDescending { it.uploaded }?.take(MAX_RECENTS) }

    val x = AppTheme.extra
    val quick = listOf(
        Quick("Notes", Icons.StickyNote, MaterialShapes.Arch.toShape(), x.spreadsheet) { nav.go(Route.Notes) },
        Quick("Chats", Icons.Chat, MaterialShapes.Cookie6Sided.toShape(), x.presentation) { nav.go(Route.Chats) },
        Quick("Favourites", Icons.StarFill, MaterialShapes.Cookie9Sided.toShape(), x.warning) { nav.go(Route.Folder(Listing.Favorites, "Favourites")) },
        Quick("Shared", Icons.Group, MaterialShapes.Circle.toShape(), x.document) { nav.go(Route.Folder(Listing.SharedIn, "Shared with me")) },
        Quick("My shares", Icons.FolderSharedFill, MaterialShapes.Square.toShape(), x.audio) { nav.go(Route.Folder(Listing.SharedOut, "Shared by me")) },
        Quick("Links", Icons.Link, MaterialShapes.Pill.toShape(), x.video) { nav.go(Route.Folder(Listing.Links, "Public links")) },
        Quick("Offline", Icons.OfflinePinFill, MaterialShapes.Clover4Leaf.toShape(), x.success) { nav.go(Route.Folder(Listing.Offline, "Offline")) },
        Quick("Trash", Icons.Delete, MaterialShapes.Slanted.toShape(), x.other) { nav.go(Route.Folder(Listing.Trash, "Trash")) },
        Quick("Open link", Icons.OpenInNew, MaterialShapes.Sunny.toShape(), x.code) { linkDialog = true },
    )

    val refresh = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = recents.refreshing && recents.items != null,
        onRefresh = reload,
        state = refresh,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = refresh,
                isRefreshing = recents.refreshing && recents.items != null,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            )
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item(key = "top") {
                Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(12.dp))
                    SearchPill(email, onClick = { nav.go(Route.Search) }, onAvatar = { nav.select(Tab.ME) })
                    Spacer(Modifier.height(24.dp))
                    Text(greeting(), style = MaterialTheme.typography.headlineMedium)
                }
            }
            usage?.takeIf { it.max > 0 && it.used.toFloat() / it.max >= 0.8f }?.let { u ->
                item(key = "storage") { StorageWarning(u) }
            }
            item(key = "quick") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(quick, key = { it.label }) { QuickTile(it) }
                }
            }
            item(key = "recentsTitle") {
                Text("Recents", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            when {
                // No network and no cache: the error and "Try again", not a spinner for ever (review 48).
                files == null && recents.error != null -> item(key = "error") {
                    ErrorState(recents.error.orEmpty(), reload, Modifier.fillMaxWidth())
                }
                files == null -> item(key = "loading") { LoadingBox(Modifier.height(200.dp)) }
                files.isEmpty() -> item(key = "empty") {
                    EmptyState(Icons.History, "Nothing recent", Modifier.fillMaxWidth().height(280.dp), message = recents.error ?: "Files that you add or change show here.")
                }
                else -> {
                    items(files.size, key = { files[it].uuid }) { i ->
                        val item = files[i]
                        ItemRow(
                            item,
                            onClick = { actions.open(item, files) },
                            onMore = { actions.menu = item },
                            offline = item.uuid in offline,
                            subtitle = Format.size(item.size) + " · " + Format.date(item.uploaded),
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
    ItemActionsHost(actions, Listing.Recents)
    if (linkDialog) OpenLinkDialog(onDismiss = { linkDialog = false }) { nav.go(Route.PublicLink(it)) }
}

@Composable
private fun SearchPill(email: String, onClick: () -> Unit, onAvatar: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(16.dp))
            Text("Search in Drive", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            // A screen reader says "Account, button", not the letter (review 87).
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                    .semantics { contentDescription = "Account" }
                    .clickable(role = Role.Button, onClick = onAvatar),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    email.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.clearAndSetSemantics {},
                )
            }
        }
    }
}

@Composable
private fun greeting(): String = when (remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun QuickTile(q: Quick) {
    Column(
        Modifier.width(76.dp).clip(MaterialTheme.shapes.large).clickable(onClick = q.open).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(60.dp).background(q.color.copy(alpha = 0.18f), q.shape), contentAlignment = Alignment.Center) {
            Icon(q.icon, null, Modifier.size(28.dp), tint = q.color)
        }
        Spacer(Modifier.height(8.dp))
        // Two lines: a large font size must not cut the label (review 94).
        Text(q.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StorageWarning(u: Usage) {
    val fraction = (u.used.toFloat() / u.max).coerceIn(0f, 1f)
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        colors = CardDefaults.cardColors(containerColor = if (fraction >= 0.95f) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(if (fraction >= 1f) "Your storage is full" else "Your storage is almost full", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("${Format.size(u.used)} of ${Format.size(u.max)} used", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            // A fixed value: the wave stands still, so the screen does not draw each frame while it shows.
            LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), waveSpeed = 0.dp)
        }
    }
}

/** Paste a public Filen link. The text stays on the phone; only a valid link opens. */
@Composable
fun OpenLinkDialog(onDismiss: () -> Unit, onOpen: (Links.Parsed) -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) { Links.find(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open a Filen link") },
        text = {
            // The link holds the key: the keyboard must not learn it.
            PrivateTextInput {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(2048) },
                    label = { Text("Link") },
                    singleLine = true,
                    isError = text.isNotEmpty() && parsed == null,
                    supportingText = { if (text.isNotEmpty() && parsed == null) Text("This is not a Filen link with its key") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { onDismiss(); parsed?.let(onOpen) }) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAX_RECENTS = 50
