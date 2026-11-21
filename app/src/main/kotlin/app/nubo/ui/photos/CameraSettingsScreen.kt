package app.nubo.ui.photos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import app.nubo.ui.components.Notice
import app.nubo.ui.components.SectionHeader
import app.nubo.ui.components.SettingRow
import app.nubo.ui.components.Toggle
import app.nubo.ui.components.userText
import app.nubo.ui.drive.FolderPicker
import app.nubo.ui.nav.LocalNav
import app.nubo.util.Format
import app.nubo.work.CameraJob
import app.nubo.work.FOLDER_GONE_TEXT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val status by app.cameraStatus.state.collectAsStateWithLifecycle()
    // The names of the items that did not upload come from MediaStore; the app does not store them. An item that is
    // not on the phone any more leaves the list.
    var skipped by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(status.skipped) {
        val (found, gone) = withContext(Dispatchers.IO) { mediaNames(context, status.skipped) }
        skipped = found
        if (gone.isNotEmpty()) {
            val left = withContext(Dispatchers.IO) { app.cameraStatus.forget(gone) }.skipped
            if (left.isEmpty()) app.notifier.cameraSkippedDone()
        }
    }

    /** Sends the items that did not upload to the transfer queue, where the user sees each result. */
    fun retrySkipped() {
        val folder = app.prefs.value.camera.folder ?: return
        val uris = skipped.map { it.first }
        app.transfers.upload(folder, uris.map { it.toUri() })
        app.notifier.cameraSkippedDone()
        app.scope.launch(Dispatchers.IO) { app.cameraStatus.forget(uris) }
        app.messenger.show(if (uris.size == 1) "1 item is in the transfer queue" else "${uris.size} items are in the transfer queue")
    }

    fun checkNow() {
        CameraJob.runNow(context, app.prefs.value.camera)
        app.messenger.show("Camera upload is checking for new photos")
    }
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
                    when {
                        !cam.enabled -> null
                        status.last > 0 -> "Last upload: ${Format.dateTime(status.last)}"
                        else -> "Nothing uploaded yet"
                    },
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
            val problem = status.problem
            if (cam.enabled && allowed && problem != null) item(key = "problem") {
                // A folder that is gone needs another folder; a try again cannot help.
                if (problem == FOLDER_GONE_TEXT) {
                    Notice("Camera upload stopped", problem, "Choose a folder") { picking = true }
                } else {
                    Notice("Camera upload stopped", problem, "Try again", ::checkNow)
                }
            }
            if (cam.enabled && !allowed) item(key = "access") {
                Notice("Camera upload is paused", "Nubo has no access to the photos on this phone.", "Allow access") { request(Waiting.NONE, cam.videos) }
            }
            item(key = "h-options") { SectionHeader("Options") }
            item(key = "folder") {
                SettingRow(Icons.FolderFill, "Folder", if (cam.folder == null) DEFAULT_FOLDER else folderName ?: "A folder in your drive", Icons.ChevronRight) { picking = true }
            }
            item(key = "videos") {
                Toggle(Icons.Videocam, "Include videos", null, cam.videos) { on ->
                    when {
                        !on -> apply { it.copy(videos = false) }
                        !cam.enabled || CameraJob.allowed(context, cam.copy(videos = true)) -> apply { it.copy(videos = true) }
                        else -> request(Waiting.VIDEOS, true)
                    }
                }
            }
            item(key = "wifi") { Toggle(Icons.Wifi, "Only on Wi-Fi", null, cam.unmeteredOnly) { on -> apply { it.copy(unmeteredOnly = on) } } }
            if (cam.enabled && allowed) item(key = "now") { SettingRow(Icons.CloudUpload, "Check now", onClick = ::checkNow) }
            if (skipped.isNotEmpty()) {
                item(key = "h-skipped") { SectionHeader("Not uploaded") }
                items(skipped.take(MAX_SHOWN), key = { "s-" + it.first }) { (_, name) ->
                    ListItem(
                        leadingContent = { Icon(Icons.Image, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    ) { Text(name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
                }
                if (skipped.size > MAX_SHOWN) item(key = "skipped-more") {
                    Text(
                        "And ${skipped.size - MAX_SHOWN} more",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                item(key = "skipped-retry") {
                    FilledTonalButton(onClick = ::retrySkipped, enabled = cam.folder != null, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Try again")
                    }
                }
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
                    if (a == Access.SELECTED) "You gave access to some photos only. Allow all photos in the app settings."
                    else "Allow access to photos in the app settings.",
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
                // A new folder ends a "folder is gone" problem; the photos that wait upload now.
                app.scope.launch(Dispatchers.IO) { app.cameraStatus.resolved() }
                CameraJob.runNow(context, app.prefs.value.camera)
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
                Choice("New photos only", !all) { all = false }
                Choice("All photos on this phone", all) { all = true }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm(all) }) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(title: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) { Text(title) }
}

private const val DEFAULT_FOLDER = "Camera uploads"

/** The items of the "Not uploaded" list that show; the rest is a count. */
private const val MAX_SHOWN = 20

/**
 * The names of MediaStore [uris]: the found items (URI, name) and the URIs that are not there any more. With no
 * media access an item stays in the list, with no name.
 */
private fun mediaNames(context: Context, uris: List<String>): Pair<List<Pair<String, String>>, List<String>> {
    val found = ArrayList<Pair<String, String>>()
    val gone = ArrayList<String>()
    for (u in uris) {
        // No cursor is a provider problem, not proof that the item is gone: the item stays.
        val name = try {
            when (val cursor = context.contentResolver.query(u.toUri(), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)) {
                null -> ""
                else -> cursor.use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else null }
            }
        } catch (_: SecurityException) {
            ""
        }
        if (name == null) gone += u else found += u to name.ifEmpty { "A photo or video" }
    }
    return found to gone
}
