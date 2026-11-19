package app.filenza.ui.photos

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.filenza.app
import app.filenza.drive.Item
import app.filenza.ui.Icons
import app.filenza.ui.components.EmptyState
import app.filenza.ui.components.ErrorState
import app.filenza.ui.components.FileTypeIcon
import app.filenza.ui.components.IconAction
import app.filenza.ui.components.LoadingBox
import app.filenza.ui.components.rememberThumb
import app.filenza.ui.drive.FolderPicker
import app.filenza.ui.nav.LocalNav
import app.filenza.ui.nav.Route
import app.filenza.ui.viewer.Viewers
import app.filenza.util.FileType
import app.filenza.work.CameraJob

/**
 * The photo timeline: every image and video under the camera upload folder, by day, newest first. Pinch
 * with two fingers to show more or fewer columns (3 to 6). A tap opens the viewer on all the media.
 */
@Composable
fun PhotosScreen() {
    val context = LocalContext.current
    val app = context.app
    val nav = LocalNav.current
    val settings by app.prefs.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val model = remember { PhotosModel(app, scope) }
    var picking by remember { mutableStateOf(false) }
    val folder = settings.camera.folder

    LaunchedEffect(Unit) { PhotoCache.watch(app) }
    LaunchedEffect(folder) { model.show(folder) }
    LaunchedEffect(model) { model.watch() }

    if (folder == null) {
        NoFolder(onCamera = { nav.go(Route.CameraSettings) }, onPick = { picking = true })
    } else {
        Timeline(model, rememberFolderName(folder), settings.camera.enabled)
    }
    if (picking) {
        FolderPicker(
            title = "Folder of your photos",
            confirm = "Use this folder",
            onPick = { uuid, name ->
                PhotoCache.name(uuid, name)
                app.prefs.edit { it.copy(camera = it.camera.copy(folder = uuid)) }
                CameraJob.schedule(context, app.prefs.value.camera)
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun Timeline(model: PhotosModel, folderName: String?, uploading: Boolean) {
    val nav = LocalNav.current
    val haptics = LocalHapticFeedback.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var columns by rememberSaveable { mutableIntStateOf(4) }
    val media = model.media
    val grid = rememberLazyGridState()

    fun open(item: Item) {
        val all = model.media ?: return
        val (session, index) = Viewers.open(all, item)
        nav.go(Route.Viewer(session, index))
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Photos") },
                subtitle = {
                    Text(
                        listOfNotNull(
                            media?.let { count(it) },
                            folderName?.takeIf { it.isNotEmpty() },
                            if (uploading) "Camera upload on" else null,
                        ).joinToString(" · "),
                        maxLines = 1,
                    )
                },
                actions = { IconAction(Icons.PhotoCamera, "Camera upload", { nav.go(Route.CameraSettings) }) },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        val refresh = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = model.refreshing && media != null,
            onRefresh = model::refresh,
            state = refresh,
            modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(state = refresh, isRefreshing = model.refreshing && media != null, modifier = Modifier.align(Alignment.TopCenter))
            },
        ) {
            when {
                media == null && model.error != null -> ErrorState(model.error.orEmpty(), model::refresh, Modifier.fillMaxSize())
                media == null -> LoadingBox(Modifier.fillMaxSize())
                media.isEmpty() -> EmptyState(
                    Icons.PhotoLibrary,
                    "No photos yet",
                    Modifier.fillMaxSize(),
                    message = if (uploading) "New photos from the camera show here after their upload." else "Photos and videos in this folder show here.",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = grid,
                    modifier = Modifier.fillMaxSize().pinchColumns { more ->
                        val next = (columns + if (more) 1 else -1).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
                        if (next != columns) {
                            columns = next
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                    },
                    contentPadding = PaddingValues(bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    model.days.forEach { day ->
                        item(key = "day:${day.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "day") {
                            Text(
                                day.label,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                            )
                        }
                        items(day.items, key = { it.uuid }, contentType = { "photo" }) { item ->
                            Cell(item, Modifier.animateItem()) { open(item) }
                        }
                    }
                }
            }
        }
    }
}

/** One square thumbnail; the type icon shows while it loads and when it cannot load. */
@Composable
private fun Cell(item: Item, modifier: Modifier, onClick: () -> Unit) {
    val thumb = rememberThumb(item)
    Box(
        modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .semantics { contentDescription = item.name },
        contentAlignment = Alignment.Center,
    ) {
        if (thumb == null) {
            FileTypeIcon(item.type, size = 40.dp)
        } else {
            Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        if (item.type == FileType.VIDEO) {
            Icon(
                Icons.PlayArrowFill,
                null,
                Modifier.padding(4.dp).size(28.dp).align(if (thumb == null) Alignment.BottomEnd else Alignment.Center),
                tint = Color.White,
            )
        }
    }
}

/** No photo folder yet: turn camera upload on, or pick a folder that has photos. */
@Composable
private fun NoFolder(onCamera: () -> Unit, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(96.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.PhotoLibrary, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text("Your photos", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Upload the photos of this phone, or show the photos of a folder in your drive.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onCamera, modifier = Modifier.fillMaxWidth()) { Text("Turn on camera upload") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Choose a folder") }
    }
}

/**
 * Two-finger pinch: [onStep] gets true for "more columns" (fingers closer) and false for fewer. The events
 * come to this modifier before the grid, so a pinch does not scroll; one finger scrolls as usual.
 */
private fun Modifier.pinchColumns(onStep: (more: Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                when {
                    zoom > ZOOM_STEP -> {
                        onStep(false)
                        zoom = 1f
                    }
                    zoom < 1f / ZOOM_STEP -> {
                        onStep(true)
                        zoom = 1f
                    }
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

private fun count(items: List<Item>): String {
    val videos = items.count { it.type == FileType.VIDEO }
    val photos = items.size - videos
    fun n(c: Int, w: String) = if (c == 1) "1 $w" else "$c ${w}s"
    return when {
        videos == 0 -> n(photos, "photo")
        photos == 0 -> n(videos, "video")
        else -> n(photos, "photo") + " · " + n(videos, "video")
    }
}

private const val MIN_COLUMNS = 3
private const val MAX_COLUMNS = 6
private const val ZOOM_STEP = 1.3f
