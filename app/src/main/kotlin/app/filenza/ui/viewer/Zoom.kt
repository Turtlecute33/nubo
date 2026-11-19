package app.filenza.ui.viewer

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Scale and offset of a zoomed page. The offset keeps the content on the screen. */
@Stable
internal class ZoomState(private val scope: CoroutineScope) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    val zoomed: Boolean get() = scale > 1.01f

    /** Zooms by [factor] around [centroid] (in layout pixels), then moves by [pan]. */
    fun change(centroid: Offset, factor: Float, pan: Offset, size: IntSize) {
        val next = (scale * factor).coerceIn(1f, MAX)
        val center = Offset(size.width / 2f, size.height / 2f)
        // The point under the fingers stays under the fingers.
        val moved = centroid - center - (centroid - center - offset) * (next / scale) + pan
        scale = next
        offset = clamp(moved, next, size)
    }

    /** Double tap: zoom in at [at], or back to the whole page. Animated. */
    fun toggle(at: Offset, size: IntSize) {
        val fromScale = scale
        val fromOffset = offset
        val toScale = if (zoomed) 1f else DOUBLE
        val center = Offset(size.width / 2f, size.height / 2f)
        val toOffset = if (toScale == 1f) Offset.Zero else clamp((center - at) * (toScale - 1f), toScale, size)
        scope.launch {
            animate(0f, 1f) { t, _ ->
                scale = fromScale + (toScale - fromScale) * t
                offset = fromOffset + (toOffset - fromOffset) * t
            }
        }
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    private fun clamp(o: Offset, s: Float, size: IntSize): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    private companion object {
        const val MAX = 6f
        const val DOUBLE = 2.5f
    }
}

@Composable
internal fun rememberZoom(): ZoomState {
    val scope = rememberCoroutineScope()
    return remember { ZoomState(scope) }
}

/**
 * Pinch to zoom and pan. With one finger and no zoom, the gesture goes on to the parent (the pager swipes
 * or the list scrolls). [oneFingerPan] false keeps one-finger moves for the parent also while zoomed (a
 * list that must scroll). With [allowDoubleTap], a double tap zooms; [onTap] gets single taps.
 */
internal fun Modifier.zoomable(
    state: ZoomState,
    onTap: () -> Unit,
    allowDoubleTap: Boolean = true,
    oneFingerPan: Boolean = true,
): Modifier = this
    .pointerInput(state, oneFingerPan) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                if (event.changes.any { it.isConsumed }) break
                if (event.changes.size > 1 || oneFingerPan && state.zoomed) {
                    state.change(event.calculateCentroid(useCurrent = false), event.calculateZoom(), event.calculatePan(), size)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .pointerInput(state, allowDoubleTap) {
        detectTapGestures(
            onTap = { onTap() },
            onDoubleTap = if (allowDoubleTap) { at -> state.toggle(at, size) } else null,
        )
    }

internal fun Modifier.zoomed(state: ZoomState): Modifier = graphicsLayer {
    scaleX = state.scale
    scaleY = state.scale
    translationX = state.offset.x
    translationY = state.offset.y
}
