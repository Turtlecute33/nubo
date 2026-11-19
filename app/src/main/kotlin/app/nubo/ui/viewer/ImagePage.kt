package app.nubo.ui.viewer

import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import app.nubo.app
import app.nubo.drive.Item
import app.nubo.ui.components.rememberThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * One image: decoded in memory at twice the size that fits the window (enough for a 2x zoom, no more memory),
 * with the thumbnail while it loads. Pinch, pan and double tap zoom; [onZoom] tells the pager to stop swiping.
 */
@Composable
internal fun ImagePage(item: Item, current: Boolean, cache: PageCache, onTap: () -> Unit, onZoom: (Boolean) -> Unit) {
    val app = LocalContext.current.app
    val window = LocalWindowInfo.current.containerSize
    val state = rememberLoaded(item.uuid + item.key, cache, current, ::bytesOf) {
        val bytes = app.content.bytes(item, MAX_BYTES)
        withContext(Dispatchers.Default) { decode(bytes, window.width, window.height) }
    }
    val zoom = rememberZoom()
    LaunchedEffect(current) { if (!current) zoom.reset() }
    LaunchedEffect(zoom.zoomed) { onZoom(zoom.zoomed) }
    val thumb = rememberThumb(item)

    Box(Modifier.fillMaxSize().zoomable(zoom, onTap), contentAlignment = Alignment.Center) {
        val bitmap = state.value
        val error = state.error
        when {
            bitmap != null -> Image(bitmap, item.name, Modifier.fillMaxSize().zoomed(zoom), contentScale = ContentScale.Fit)
            error != null -> PageMessage(item, error, state::retry)
            else -> {
                if (thumb != null) Image(thumb, null, Modifier.fillMaxSize().alpha(0.5f), contentScale = ContentScale.Fit)
                LoadingIndicator()
            }
        }
    }
}

private fun decode(bytes: ByteArray, windowWidth: Int, windowHeight: Int): ImageBitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val scale = decodeScale(w, h, windowWidth, windowHeight)
        if (scale < 1f) d.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
    }.asImageBitmap()

/** ARGB: 4 bytes for each pixel. */
private fun bytesOf(b: ImageBitmap): Long = b.width.toLong() * b.height * 4

/**
 * The decode scale of a [width] x [height] image in the window: twice the scale that fits the whole image in
 * the window, not more than 1, and not more than [MAX_PIXELS] (review 54). A window of size 0 (not measured
 * yet) counts as [FALLBACK_SIDE] square.
 */
internal fun decodeScale(width: Int, height: Int, windowWidth: Int, windowHeight: Int): Float {
    val w = width.coerceAtLeast(1).toFloat()
    val h = height.coerceAtLeast(1).toFloat()
    val fit = minOf((windowWidth.takeIf { it > 0 } ?: FALLBACK_SIDE) / w, (windowHeight.takeIf { it > 0 } ?: FALLBACK_SIDE) / h)
    return minOf(1f, 2f * fit, kotlin.math.sqrt(MAX_PIXELS / (w * h)))
}

private const val FALLBACK_SIDE = 1024
/** About 64 MB of ARGB pixels: four times a large phone screen. */
private const val MAX_PIXELS = 16_777_216f
/** Larger images open in another app: decoding needs the whole file in memory. */
private const val MAX_BYTES = 80L * 1000 * 1000
