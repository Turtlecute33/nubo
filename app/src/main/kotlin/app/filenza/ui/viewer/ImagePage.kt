package app.filenza.ui.viewer

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
import app.filenza.app
import app.filenza.drive.Item
import app.filenza.ui.components.rememberThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * One image: decoded in memory at about twice the screen size (enough for a 2x zoom, no more memory), with
 * the thumbnail while it loads. Pinch, pan and double tap zoom; [onZoom] tells the pager to stop swiping.
 */
@Composable
internal fun ImagePage(item: Item, current: Boolean, onTap: () -> Unit, onZoom: (Boolean) -> Unit) {
    val app = LocalContext.current.app
    val window = LocalWindowInfo.current.containerSize
    val target = (maxOf(window.width, window.height) * 2).coerceIn(MIN_SIDE, MAX_SIDE)
    val state = rememberLoaded(item.uuid + item.key) {
        val bytes = app.content.bytes(item, MAX_BYTES)
        withContext(Dispatchers.Default) { decode(bytes, target) }
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

private fun decode(bytes: ByteArray, target: Int): ImageBitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val scale = target.toFloat() / maxOf(1, maxOf(w, h))
        if (scale < 1f) d.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
    }.asImageBitmap()

private const val MIN_SIDE = 1024
private const val MAX_SIDE = 6144
/** Larger images open in another app: decoding needs the whole file in memory. */
private const val MAX_BYTES = 80L * 1000 * 1000
