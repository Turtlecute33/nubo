package app.filenza.ui.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.filenza.app
import app.filenza.drive.Listing
import app.filenza.drive.Usage
import app.filenza.ui.Icons
import app.filenza.ui.components.ConfirmDialog
import app.filenza.ui.components.SectionHeader
import app.filenza.ui.nav.LocalNav
import app.filenza.ui.nav.Route
import app.filenza.util.Format
import kotlinx.coroutines.launch

/** The account: storage, places that are not folders, settings, and log out. */
@Composable
fun MeScreen() {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val settings by app.prefs.state.collectAsStateWithLifecycle()
    val email = app.account.session?.email.orEmpty()
    val usage = rememberUsage()
    var leaving by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "head") {
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 32.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                    Text(email.firstOrNull()?.uppercaseChar()?.toString() ?: "?", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(12.dp))
                Text(email, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
        item(key = "usage") { UsageCard(usage.usage, usage.error) { usage.load(force = true) } }
        item(key = "h-places") { SectionHeader("Places") }
        item(key = "transfers") { Row(Icons.SwapVert, "Transfers", null) { nav.go(Route.Transfers) } }
        item(key = "offline") { Row(Icons.OfflinePin, "Offline files", null) { nav.go(Route.Folder(Listing.Offline, "Offline")) } }
        item(key = "shared-in") { Row(Icons.Group, "Shared with me", null) { nav.go(Route.Folder(Listing.SharedIn, "Shared with me")) } }
        item(key = "shared-out") { Row(Icons.FolderSharedFill, "Shared by me", null) { nav.go(Route.Folder(Listing.SharedOut, "Shared by me")) } }
        item(key = "links") { Row(Icons.Link, "Public links", null) { nav.go(Route.Folder(Listing.Links, "Public links")) } }
        item(key = "trash") { Row(Icons.Delete, "Trash", null) { nav.go(Route.Folder(Listing.Trash, "Trash")) } }
        item(key = "h-app") { SectionHeader("App") }
        item(key = "camera") {
            Row(Icons.PhotoCamera, "Camera upload", if (settings.camera.enabled) "On" else "Off") { nav.go(Route.CameraSettings) }
        }
        item(key = "settings") { Row(Icons.Palette, "Settings", "Theme, app lock, screenshots, cache") { nav.go(Route.Settings) } }
        item(key = "logout") {
            ListItem(
                onClick = { leaving = true },
                leadingContent = { Icon(Icons.Logout, null, tint = MaterialTheme.colorScheme.error) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            ) { Text("Log out", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (leaving) {
        ConfirmDialog(
            title = "Log out?",
            text = "Filenza deletes the session, the offline files, the thumbnails and the queued transfers from this phone. Your files stay in your Filen drive.",
            confirm = "Log out",
            destructive = true,
            // The app scope: this screen goes away during the logout, and the cleanup must finish.
            onConfirm = { app.scope.launch { app.account.logout() } },
            onDismiss = { leaving = false },
        )
    }
}

/** The storage use. A failed read shows its error and "Try again", not "Reading…" for ever (review 48). */
@Composable
private fun UsageCard(usage: Usage?, error: String?, onRetry: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    usage != null -> "${Format.size(usage.used)} of ${Format.size(usage.max)} used" + if (usage.premium) " · Pro" else ""
                    error != null -> error
                    else -> "Reading…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (usage == null && error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (usage == null && error != null) {
                TextButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) { Text("Try again") }
            } else {
                Spacer(Modifier.height(14.dp))
                val fraction = if (usage == null || usage.max <= 0) 0f else (usage.used.toFloat() / usage.max).coerceIn(0f, 1f)
                LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Row(icon: ImageVector, title: String, text: String?, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        leadingContent = { Icon(icon, null) },
        supportingContent = text?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = { Icon(Icons.ChevronRight, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(title) }
}
