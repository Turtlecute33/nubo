package app.filenza.ui.viewer

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.filenza.app
import app.filenza.drive.Item
import app.filenza.media.ChunkReader
import app.filenza.media.ProxyFd
import app.filenza.ui.components.userText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A PDF through the platform PdfRenderer. The renderer reads a proxy file descriptor ([ProxyFd]) that
 * decrypts only the chunks it asks for: no plain copy on the disk. Pages render at the screen width when
 * they come into view and go away when they leave it. Two fingers zoom and pan; one finger scrolls.
 */
@Composable
internal fun PdfPage(item: Item, current: Boolean, onTap: () -> Unit, onZoom: (Boolean) -> Unit) {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item.uuid, attempt) {
        error = null
        val opened = try {
            // Not cancellable: a document that opens after the page left must still close.
            withContext(Dispatchers.IO + NonCancellable) { PdfDoc.open(context, item) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            error = "This PDF has a password. Open it in another app."
            return@LaunchedEffect
        } catch (e: Exception) {
            error = userText(e)
            return@LaunchedEffect
        }
        if (!isActive) return@LaunchedEffect opened.closeLater(context)
        doc = opened
        try {
            awaitCancellation()
        } finally {
            doc = null
            opened.closeLater(context)
        }
    }
    val zoom = rememberZoom()
    LaunchedEffect(current) { if (!current) zoom.reset() }
    LaunchedEffect(zoom.zoomed) { onZoom(zoom.zoomed) }

    val d = doc
    val e = error
    when {
        d != null -> BoxWithConstraints(Modifier.fillMaxSize().zoomable(zoom, onTap, allowDoubleTap = false, oneFingerPan = false)) {
            val width = with(LocalDensity.current) { maxWidth.roundToPx() }
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyColumn(
                Modifier.fillMaxSize().zoomed(zoom),
                contentPadding = PaddingValues(top = top + 72.dp, bottom = bottom + 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(d.count, key = { it }) { i -> PdfPageImage(d, i, width) }
            }
        }
        e != null -> PageMessage(item, e) { attempt++ }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
    }
}

@Composable
private fun PdfPageImage(doc: PdfDoc, index: Int, width: Int) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var ratio by remember { mutableFloatStateOf(A4) }
    LaunchedEffect(doc, index, width) {
        runCatching { doc.render(index, width) }.getOrNull()?.let { (b, r) ->
            ratio = r
            bitmap = b
        }
    }
    DisposableEffect(doc, index) {
        onDispose { bitmap?.asAndroidBitmap()?.recycle() }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f / ratio).background(Color.White), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b, "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) else LoadingIndicator()
    }
}

/** One open PDF. PdfRenderer is not thread-safe: every page and the close go through [mutex]. */
private class PdfDoc(private val pfd: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    private val mutex = Mutex()
    private var closed = false
    val count: Int = renderer.pageCount

    /** The page as a bitmap [width] pixels wide, and its height/width ratio. */
    suspend fun render(index: Int, width: Int): Pair<ImageBitmap, Float> = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed)
            renderer.openPage(index).use { page ->
                val ratio = page.height.toFloat() / page.width.coerceAtLeast(1)
                // A very tall page (a receipt) gets a narrower bitmap, so that no page takes more than MAX_PIXELS.
                val fit = kotlin.math.sqrt(MAX_PIXELS / ratio.coerceAtLeast(0.01f)).toInt()
                val w = width.coerceIn(1, MAX_WIDTH).coerceAtMost(fit.coerceAtLeast(1))
                val bitmap = createBitmap(w, (w * ratio).toInt().coerceIn(1, MAX_PIXELS))
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap() to ratio
            }
        }
    }

    /** Closes after the page that renders now. */
    fun closeLater(context: Context) {
        context.app.scope.launch(Dispatchers.IO) {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                runCatching { renderer.close() }
                runCatching { pfd.close() }
            }
        }
    }

    companion object {
        fun open(context: Context, item: Item): PdfDoc {
            val pfd = ProxyFd.open(context, ChunkReader(context.app.content, item))
            return try {
                PdfDoc(pfd, PdfRenderer(pfd))
            } catch (e: Exception) {
                runCatching { pfd.close() }
                throw e
            }
        }
    }
}

private const val A4 = 1.414f
/** A page bitmap of at most this width: about 12 MB for an A4 page. */
private const val MAX_WIDTH = 1600

/** About 32 MB of ARGB pixels for one page. */
private const val MAX_PIXELS = 8_000_000
