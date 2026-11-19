package app.filenza.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.filenza.app
import app.filenza.drive.Item
import app.filenza.drive.Source
import app.filenza.ui.Icons
import app.filenza.ui.theme.AppTheme
import app.filenza.util.FileType
import app.filenza.util.Format

fun FileType.icon(): ImageVector = when (this) {
    FileType.FOLDER -> Icons.FolderFill
    FileType.IMAGE -> Icons.Image
    FileType.VIDEO -> Icons.Movie
    FileType.AUDIO -> Icons.MusicNoteFill
    FileType.PDF -> Icons.PictureAsPdf
    FileType.DOCUMENT -> Icons.Description
    FileType.SPREADSHEET -> Icons.TableChart
    FileType.PRESENTATION -> Icons.Slideshow
    FileType.ARCHIVE -> Icons.FolderZip
    FileType.CODE -> Icons.Code
    FileType.TEXT -> Icons.Article
    FileType.APK -> Icons.Android
    FileType.OTHER -> Icons.Draft
}

@Composable
fun FileType.color(): Color {
    val x = AppTheme.extra
    return when (this) {
        FileType.FOLDER -> x.folder
        FileType.IMAGE -> x.image
        FileType.VIDEO -> x.video
        FileType.AUDIO -> x.audio
        FileType.PDF -> x.pdf
        FileType.DOCUMENT, FileType.TEXT -> x.document
        FileType.SPREADSHEET -> x.spreadsheet
        FileType.PRESENTATION -> x.presentation
        FileType.ARCHIVE -> x.archive
        FileType.CODE -> x.code
        FileType.APK -> x.success
        FileType.OTHER -> x.other
    }
}

/** A tinted rounded square with the file type glyph. */
@Composable
fun FileTypeIcon(type: FileType, modifier: Modifier = Modifier, size: Dp = 40.dp, override: ImageVector? = null) {
    val color = type.color()
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(override ?: type.icon(), null, Modifier.size(size * 0.55f), tint = color)
    }
}

/** The thumbnail of [item], loaded on first show; the type icon stays behind it while it loads and after an error. */
@Composable
fun rememberThumb(item: Item): ImageBitmap? {
    val thumbs = LocalContext.current.app.thumbs
    var bitmap by remember(item.uuid, item.key) { mutableStateOf(thumbs.peek(item)) }
    if (bitmap == null && thumbs.supports(item)) {
        LaunchedEffect(item.uuid, item.key) { bitmap = thumbs.get(item) }
    }
    return bitmap
}

@Composable
fun ItemThumb(item: Item, modifier: Modifier = Modifier, size: Dp = 40.dp, cornerFraction: Float = 0.28f) {
    val folderIcon = when {
        !item.folder -> null
        item.source is Source.SharedIn || item.source is Source.SharedOut -> Icons.FolderSharedFill
        else -> Icons.FolderFill
    }
    val thumb = rememberThumb(item)
    Box(modifier.size(size).clip(RoundedCornerShape(size * cornerFraction))) {
        FileTypeIcon(item.type, Modifier.fillMaxSize(), size, folderIcon)
        if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (thumb != null && item.type == FileType.VIDEO) {
            Icon(Icons.PlayArrowFill, null, Modifier.size(size * 0.4f).align(Alignment.Center), tint = Color.White)
        }
    }
}

/** "1.2 MB · 3 Oct 2026" for files, the date for folders, and the owner of a share. */
fun Item.subtitle(): String {
    val who = when (val s = source) {
        is Source.SharedIn -> s.owner
        is Source.SharedOut -> s.with
        else -> null
    }
    val main = if (folder) Format.date(created) else Format.size(size) + " · " + Format.date(modified)
    return listOfNotNull(who?.takeIf { it.isNotEmpty() }, main.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/** Small badges: favourite and offline. */
@Composable
fun ItemBadges(item: Item, offline: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (item.favorite) Icon(Icons.StarFill, "Favourite", Modifier.size(16.dp), tint = AppTheme.extra.warning)
        if (offline) Icon(Icons.OfflinePinFill, "Offline", Modifier.size(16.dp), tint = AppTheme.extra.success)
    }
}

@Composable
fun ItemRow(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selecting: Boolean = false,
    offline: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    ListItem(
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        leadingContent = { ItemThumb(item, size = 44.dp) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.favorite || offline) {
                    ItemBadges(item, offline)
                    Spacer(Modifier.width(6.dp))
                }
                Text(subtitle ?: item.subtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        trailingContent = when {
            selecting -> { { Checkbox(checked = selected, onCheckedChange = { onClick() }) } }
            onMore != null -> { { IconAction(Icons.MoreVert, "More", onMore) } }
            else -> null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(item.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

@Composable
fun ItemTile(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selecting: Boolean = false,
    offline: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
) {
    val motion = MaterialTheme.motionScheme
    val spec = remember(motion) { motion.fastSpatialSpec<Float>() } // review 102
    val scale by animateFloatAsState(if (selected) 0.92f else 1f, spec, label = "tileScale")
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .scale(scale)
            .clip(MaterialTheme.shapes.large)
            .semantics { if (selecting) this.selected = selected }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(6.dp)) {
                val thumb = if (item.folder) null else rememberThumb(item)
                if (thumb == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        FileTypeIcon(item.type, size = 72.dp, override = if (item.folder) Icons.FolderFill else null)
                    }
                } else {
                    Image(thumb, null, Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
                    if (item.type == FileType.VIDEO) {
                        Icon(Icons.PlayArrowFill, null, Modifier.size(32.dp).align(Alignment.Center), tint = Color.White)
                    }
                }
                androidx.compose.animation.AnimatedVisibility(selecting, Modifier.align(Alignment.TopStart), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Box(
                        Modifier.padding(4.dp).size(24.dp).clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                            .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
            Row(Modifier.padding(start = 12.dp, end = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    ItemBadges(item, offline)
                }
                if (onMore != null && !selecting) {
                    IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) { Icon(Icons.MoreVert, "More") }
                }
            }
        }
    }
}

/** A list or a grid of items with selection. Keys are UUIDs, so items animate on changes and keep their place. */
@Composable
fun ItemCollection(
    items: List<Item>,
    grid: Boolean,
    selection: Set<String>,
    onClick: (Item) -> Unit,
    onLongClick: (Item) -> Unit,
    onMore: ((Item) -> Unit)?,
    modifier: Modifier = Modifier,
    offline: Set<String> = emptySet(),
    contentPadding: PaddingValues = PaddingValues(bottom = 96.dp),
    listState: LazyListState = rememberLazyListState(),
    gridState: LazyGridState = rememberLazyGridState(),
    header: (@Composable () -> Unit)? = null,
) {
    val selecting = selection.isNotEmpty()
    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(152.dp),
            state = gridState,
            modifier = modifier,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = contentPadding.calculateTopPadding() + 4.dp, bottom = contentPadding.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (header != null) item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
            items(items.size, key = { items[it].uuid }, contentType = { if (items[it].folder) 0 else 1 }) { i ->
                val item = items[i]
                ItemTile(
                    item = item,
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                    onMore = onMore?.let { { it(item) } },
                    selected = item.uuid in selection,
                    selecting = selecting,
                    offline = item.uuid in offline,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    } else {
        LazyColumn(state = listState, modifier = modifier, contentPadding = contentPadding) {
            if (header != null) item(key = "header") { header() }
            items(items.size, key = { items[it].uuid }, contentType = { if (items[it].folder) 0 else 1 }) { i ->
                val item = items[i]
                ItemRow(
                    item = item,
                    onClick = { onClick(item) },
                    onLongClick = { onLongClick(item) },
                    onMore = onMore?.let { { it(item) } },
                    selected = item.uuid in selection,
                    selecting = selecting,
                    offline = item.uuid in offline,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}
