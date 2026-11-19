package app.nubo.ui.photos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nubo.app
import app.nubo.data.CameraUpload
import app.nubo.ui.Icons
import app.nubo.ui.components.IconAction
import app.nubo.ui.components.SectionHeader
import app.nubo.ui.components.userText
import app.nubo.ui.drive.FolderPicker
import app.nubo.ui.nav.LocalNav
import app.nubo.work.CameraJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What waits for the media permission: turning camera upload on, or turning videos on. */
private enum class Waiting { NONE, ENABLE, VIDEOS }

/**
 * Camera upload settings (DESIGN §9): on/off, the cloud folder, videos, and the network. Turning it on asks
 * for photo (and video) access and for notifications, then whether the photos that are on the phone already
 * upload too. Each change schedules the background job again.
 */
@Composable
fun CameraSettingsScreen() {
    val context = LocalContext.current
    val app = context.app
    val nav = LocalNav.current
    val haptics = LocalHapticFeedback.current
    val settings by app.prefs.state.collectAsStateWithLifecycle()
    val cam = settings.camera
    val folderName = rememberFolderName(cam.folder)
    var waiting by remember { mutableStateOf(Waiting.NONE) }
    var askSince by remember { mutableStateOf(false) }
    var noAccess by remember { mutableStateOf<Access?>(null) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(CameraJob.allowed(context, cam)) }
    // The user can give or take the access in the system settings and come back.
    LifecycleResumeEffect(cam.videos) {
        allowed = CameraJob.allowed(context, cam)
        onPauseOrDispose {}
    }

    fun apply(change: (CameraUpload) -> CameraUpload) {
        app.prefs.edit { it.copy(camera = change(it.camera)) }
        val now = app.prefs.value.camera
        allowed = CameraJob.allowed(context, now)
        CameraJob.schedule(context, now)
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val want = waiting
        waiting = Waiting.NONE
        val videos = want == Waiting.VIDEOS || app.prefs.value.camera.videos
        val ok = CameraJob.allowed(context, app.prefs.value.camera.copy(videos = videos))
        when {
            !ok -> noAccess = access(context)
            want == Waiting.ENABLE -> askSince = true
            want == Waiting.VIDEOS -> apply { c -> c.copy(videos = true) }
        }
        allowed = CameraJob.allowed(context, app.prefs.value.camera)
    }

    fun request(what: Waiting, videos: Boolean) {
        waiting = what
        permissions.launch(media(videos, notify = what == Waiting.ENABLE))
    }

    /**
     * Turns camera upload on. With no folder yet, it uses the root folder "Camera uploads" (made or found). It
     * runs in the app scope: Back during the folder write does not stop it halfway (review 99).
     */
    fun enable(since: Long) {
        busy = true
        app.scope.launch {
            try {
                var next = app.prefs.value.camera
                if (next.folder == null) {
                    val root = app.account.session?.baseFolder ?: return@launch
                    val folder = app.drive.ops.createFolder(root, DEFAULT_FOLDER)
                    PhotoCache.name(folder.uuid, folder.name)
                    next = next.copy(folder = folder.uuid)
                }
                val on = next.copy(enabled = true, since = since)
                app.prefs.edit { it.copy(camera = on) }
                CameraJob.schedule(app, on)
                CameraJob.runNow(app, on)
                allowed = CameraJob.allowed(app, on)
                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.messenger.show(userText(e))
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Camera upload") }, navigationIcon = { IconAction(Icons.ArrowBack, "Back", nav::back) }) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "main") {
                Toggle(
                    Icons.PhotoCamera,
                    "Camera upload",
                    if (cam.enabled) "On · new photos upload by themselves" else "Off",
                    cam.enabled,
                    enabled = !busy,
                    busy = busy,
                ) { on ->
                    when {
                        !on -> {
                            apply { it.copy(enabled = false) }
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                        }
                        CameraJob.allowed(context, cam) && notifications(context) -> askSince = true
                        else -> request(Waiting.ENABLE, cam.videos)
                    }
                }
            }
            if (cam.enabled && !allowed) item(key = "access") {
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Camera upload is paused", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("nubo has no access to the photos on this phone.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = { request(Waiting.NONE, cam.videos) }) { Text("Allow access") }
                    }
                }
            }
            item(key = "h-where") { SectionHeader("Where") }
            item(key = "folder") {
                Row(
                    Icons.FolderFill,
                    "Folder",
                    when {
                        cam.folder == null -> "“$DEFAULT_FOLDER” in your drive (made when you turn upload on)"
                        else -> folderName ?: "A folder in your drive"
                    },
                ) { picking = true }
            }
            item(key = "h-what") { SectionHeader("What and when") }
            item(key = "videos") {
                Toggle(Icons.Videocam, "Include videos", "Videos can use much data and storage", cam.videos) { on ->
                    when {
                        !on -> apply { it.copy(videos = false) }
                        !cam.enabled || CameraJob.allowed(context, cam.copy(videos = true)) -> apply { it.copy(videos = true) }
                        else -> request(Waiting.VIDEOS, true)
                    }
                }
            }
            item(key = "wifi") {
                Toggle(Icons.Wifi, "Only on Wi-Fi", "Wait for a network that is not metered", cam.unmeteredOnly) { on ->
                    apply { it.copy(unmeteredOnly = on) }
                }
            }
            if (cam.enabled && allowed) item(key = "now") {
                Row(Icons.CloudUpload, "Check now", "Look for new photos and upload them") {
                    CameraJob.runNow(context, app.prefs.value.camera)
                    app.messenger.show("Camera upload is checking for new photos")
                }
            }
            item(key = "about") {
                Text(
                    "Photos and videos from the camera (the DCIM folder) upload in the background, on the network that you " +
                        "allow, encrypted on this phone. Android removes the GPS position from the uploaded files, because " +
                        "nubo does not ask for access to the location of your media.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (askSince) SinceDialog(onDismiss = { askSince = false }) { all -> enable(if (all) 0 else System.currentTimeMillis()) }
    noAccess?.let { a ->
        AlertDialog(
            onDismissRequest = { noAccess = null },
            title = { Text("Camera upload needs access to all photos") },
            text = {
                Text(
                    if (a == Access.SELECTED) "You gave access to some photos only. Camera upload must see all new photos: allow all photos (and videos) in the app settings."
                    else "Allow access to photos (and videos) in the app settings.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    noAccess = null
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    }
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { noAccess = null }) { Text("Cancel") } },
        )
    }
    if (picking) {
        FolderPicker(
            title = "Camera upload folder",
            confirm = "Use this folder",
            onPick = { uuid, name ->
                PhotoCache.name(uuid, name)
                apply { it.copy(folder = uuid) }
            },
            onDismiss = { picking = false },
        )
    }
}

/** The answer of the system to the media permission request. */
private enum class Access { SELECTED, NONE }

private fun access(context: Context): Access =
    if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) Access.SELECTED
    else Access.NONE

/**
 * The permissions to ask. READ_MEDIA_VISUAL_USER_SELECTED goes with them, so that a "selected photos" answer
 * can be told apart from a refusal (it has an effect only when the manifest declares it). With [notify],
 * also the notifications: camera upload tells the user about account errors with a notification. A refusal
 * of the notifications does not stop camera upload.
 */
private fun media(videos: Boolean, notify: Boolean): Array<String> = buildList {
    add(Manifest.permission.READ_MEDIA_IMAGES)
    if (videos) add(Manifest.permission.READ_MEDIA_VIDEO)
    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    if (notify) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun notifications(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** Asked once each time the user turns camera upload on. */
@Composable
private fun SinceDialog(onDismiss: () -> Unit, onConfirm: (all: Boolean) -> Unit) {
    var all by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which photos?") },
        text = {
            Column(Modifier.selectableGroup()) {
                Choice("Only new photos", "Photos that you take from now on", !all) { all = false }
                Choice("Also the photos already on the phone", "All photos and videos in the DCIM folder", all) { all = true }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(all) }) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(title: String, text: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        supportingContent = { Text(text) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) { Text(title) }
}

@Composable
private fun Toggle(
    icon: ImageVector,
    title: String,
    text: String,
    checked: Boolean,
    enabled: Boolean = true,
    busy: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        supportingContent = { Text(text) },
        trailingContent = {
            if (busy) LoadingIndicator(Modifier.size(32.dp)) else Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    ) { Text(title) }
}

@Composable
private fun Row(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        leadingContent = { Icon(icon, null) },
        supportingContent = { Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Icon(Icons.ChevronRight, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(title) }
}

private const val DEFAULT_FOLDER = "Camera uploads"
