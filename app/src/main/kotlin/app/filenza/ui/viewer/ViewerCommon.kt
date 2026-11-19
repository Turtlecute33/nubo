package app.filenza.ui.viewer

import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.filenza.app
import app.filenza.drive.Item
import app.filenza.media.OpenWith
import app.filenza.ui.components.FileTypeIcon
import app.filenza.ui.components.userText
import kotlinx.coroutines.CancellationException
import java.util.Locale

/** A value that a page loads once, with its error and a retry. */
@Stable
internal class Loaded<T> {
    var value by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var attempt by mutableIntStateOf(0)
        private set

    fun retry() {
        error = null
        attempt++
    }
}

/**
 * The value of [key] from [cache], else runs [load] (again after [Loaded.retry]); errors become user text.
 * Only the [current] page puts its value into the cache, so that the page on the screen is the last one that
 * goes, and pages that load next to it do not push it out (review 51). [size] estimates the bytes of a value.
 */
@Composable
internal fun <T : Any> rememberLoaded(key: String, cache: PageCache, current: Boolean, size: (T) -> Long, load: suspend () -> T): Loaded<T> {
    val state = remember(key) { Loaded<T>().apply { value = cache.get<T>(key) } }
    LaunchedEffect(key, state.attempt) {
        if (state.value != null) return@LaunchedEffect
        try {
            state.value = load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.error = userText(e)
        }
    }
    val value = state.value
    LaunchedEffect(value, current) { if (current && value != null) cache.put(key, value, size(value)) }
    return state
}

/** The viewer is always dark: wallpaper colours in dark form, or a plain dark scheme. Black background. */
@Composable
internal fun ViewerTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val settings by context.app.prefs.state.collectAsStateWithLifecycle()
    val base = MaterialTheme.colorScheme
    val colors: ColorScheme = remember(base, settings.dynamicColor) {
        val dark = when {
            base.surface.luminance() < 0.5f -> base
            settings.dynamicColor -> dynamicDarkColorScheme(context)
            else -> darkColorScheme(primary = base.inversePrimary)
        }
        dark.copy(background = Color.Black, surface = Color.Black)
    }
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), typography = MaterialTheme.typography, content = content)
}

/**
 * Light system-bar icons on the black viewer, and the bars hidden while [visible] is false (a swipe from
 * the edge shows them for a moment). Both come back when the viewer closes.
 */
@Composable
internal fun ImmersiveBars(visible: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val controller = window.insetsController ?: return
    DisposableEffect(controller) {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val old = controller.systemBarsAppearance and mask
        val oldBehavior = controller.systemBarsBehavior
        controller.setSystemBarsAppearance(0, mask)
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior = oldBehavior
            controller.setSystemBarsAppearance(old, mask)
        }
    }
    LaunchedEffect(controller, visible) {
        if (visible) controller.show(WindowInsets.Type.systemBars()) else controller.hide(WindowInsets.Type.systemBars())
    }
}

/** A page that cannot load or show its file: the reason, a retry, and "Open with" for another app. */
@Composable
internal fun PageMessage(item: Item, message: String, onRetry: (() -> Unit)?) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FileTypeIcon(item.type, size = 72.dp)
        Spacer(Modifier.height(20.dp))
        Text(item.name, style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center, maxLines = 2)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Row {
            if (onRetry != null) {
                FilledTonalButton(onClick = onRetry) { Text("Try again") }
                Spacer(Modifier.width(12.dp))
            }
            OutlinedButton(onClick = { OpenWith.open(context, item) }) { Text("Open with") }
        }
    }
}

/** "1:05" or "1:02:03". */
internal fun clock(ms: Int): String {
    val s = (ms.coerceAtLeast(0) / 1000)
    val h = s / 3600
    val m = s / 60 % 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
}
