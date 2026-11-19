package app.filenza.ui.viewer

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.filenza.app
import app.filenza.drive.Item
import app.filenza.media.OpenWith
import app.filenza.ui.Icons
import app.filenza.ui.nav.LocalNav
import app.filenza.util.FileType

/**
 * The full-screen viewer for the files of one [Viewers] session: swipe between files, tap to show or hide
 * the bars. Images zoom; video and audio stream through the platform player; text and PDF show in the app.
 * Plain data stays in memory or streams through a proxy file descriptor: nothing goes to the disk.
 * [onClose] replaces the navigation back step where no route holds the viewer (a public link with no login).
 */
@Composable
fun ViewerScreen(session: Long, index: Int, onClose: (() -> Unit)? = null) {
    val nav = LocalNav.current
    val close = onClose ?: nav::back
    val activity = LocalActivity.current
    val opened = remember(session) { Viewers.get(session) }
    val items = opened?.items
    if (opened == null || items.isNullOrEmpty()) {
        LaunchedEffect(Unit) { close() }
        return
    }
    if (onClose != null) BackHandler(onBack = onClose)
    // A rotation keeps the loaded pages; when the viewer closes, they go (review 51).
    DisposableEffect(session) { onDispose { if (activity?.isChangingConfigurations != true) Viewers.release(session) } }

    ViewerTheme {
        val pager = rememberPagerState(initialPage = index.coerceIn(0, items.lastIndex)) { items.size }
        var chrome by rememberSaveable { mutableStateOf(true) }
        var zoomed by remember { mutableStateOf(false) }
        // Text and PDF pages start below the top bar: its measured height, so a large font is not cut (review 94).
        var bar by remember { mutableIntStateOf(0) }
        val top = if (bar > 0) with(LocalDensity.current) { bar.toDp() } else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp
        LaunchedEffect(pager.settledPage) { zoomed = false }
        ImmersiveBars(visible = chrome)

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                key = { items[it].uuid },
                // The pages next to the current one stay: a swipe back does not load again (review 51).
                beyondViewportPageCount = 1,
                userScrollEnabled = !zoomed,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = items[page]
                val current = pager.settledPage == page
                val toggle = { chrome = !chrome }
                when (item.type) {
                    FileType.IMAGE -> ImagePage(item, current, opened.cache, toggle) { if (current) zoomed = it }
                    FileType.VIDEO -> MediaPage(item, video = true, current = current, chrome = chrome, onTap = toggle)
                    FileType.AUDIO -> MediaPage(item, video = false, current = current, chrome = chrome, onTap = toggle)
                    FileType.PDF -> PdfPage(item, current, top, toggle) { if (current) zoomed = it }
                    FileType.TEXT, FileType.CODE -> TextPage(item, mono = item.type == FileType.CODE, current = current, cache = opened.cache, top = top)
                    else -> PageMessage(item, "Filenza cannot show this type of file.", null)
                }
            }
            AnimatedVisibility(
                visible = chrome,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn() + slideInVertically { -it },
                exit = fadeOut() + slideOutVertically { -it },
            ) {
                TopBar(items[pager.currentPage], "${pager.currentPage + 1} / ${items.size}", close, Modifier.onSizeChanged { bar = it.height })
            }
        }
    }
}

@Composable
private fun TopBar(item: Item, counter: String, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val app = context.app
    Box(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
            .statusBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Action(Icons.ArrowBack, "Back", onBack)
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(counter, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            }
            Action(Icons.Download, "Download") {
                app.transfers.download(listOf(item))
                app.messenger.show("Saving to Download/Filenza")
            }
            Action(Icons.OpenInNew, "Open with") { OpenWith.open(context, item) }
            Action(Icons.Share, "Send a copy") { OpenWith.share(context, listOf(item)) }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
}
