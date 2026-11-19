package app.nubo.ui.viewer

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.nubo.app
import app.nubo.drive.Item
import app.nubo.ui.Icons
import app.nubo.ui.components.FileTypeIcon
import kotlinx.coroutines.delay

/**
 * Video or audio. Loads and plays when the page is the current one; pauses when it is not, when the app
 * goes to the background, and when another app takes the audio. The video surface is secure while
 * "Block screenshots" is on.
 */
@Composable
internal fun MediaPage(item: Item, video: Boolean, current: Boolean, chrome: Boolean, onTap: () -> Unit) {
    val app = LocalContext.current.app
    var attempt by remember { mutableIntStateOf(0) }
    // The place in the file, in saved state (only a number): a rotation goes on from it (review 51).
    var place by rememberSaveable { mutableIntStateOf(0) }
    val player = remember(item.uuid, attempt) { Player(app, item, video, startAt = place) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) { snapshotFlow { player.position }.collect { place = it } }
    LaunchedEffect(player, current) { if (current) player.prepare(play = true) else player.pause() }
    LifecycleResumeEffect(player) { onPauseOrDispose { player.pause() } }
    LaunchedEffect(player, player.playing) {
        while (player.playing) {
            player.tick()
            delay(TICK_MS)
        }
    }
    val error = player.error
    if (error != null) {
        PageMessage(item, error) { attempt++ }
        return
    }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }, contentAlignment = Alignment.Center) {
        if (video) {
            val ratio = if (player.width > 0 && player.height > 0) player.width.toFloat() / player.height else 16f / 9f
            VideoSurface(player, secure = app.prefs.value.blockScreenshots, modifier = Modifier.fillMaxWidth().aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f))
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                FileTypeIcon(item.type, size = 160.dp)
                Spacer(Modifier.height(24.dp))
                Text(item.name, style = MaterialTheme.typography.titleLarge, color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!player.prepared || player.buffering) LoadingIndicator()
        AnimatedVisibility(
            visible = chrome || !video || !player.playing,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Controls(player)
        }
    }
}

@Composable
private fun VideoSurface(player: Player, secure: Boolean, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                setSecure(secure)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = player.setDisplay(holder)
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = player.setDisplay(null)
                })
            }
        },
    )
}

@Composable
private fun Controls(player: Player) {
    val haptics = LocalHapticFeedback.current
    var seeking by remember { mutableStateOf<Float?>(null) }
    val slider = remember { SliderState() }
    val duration = player.duration
    val live = if (duration > 0) (player.position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val fraction = seeking ?: live
    // The slider follows the player, except while the user drags it.
    LaunchedEffect(live, seeking) { if (seeking == null) slider.value = live }
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Slider(
            state = slider,
            onValueChange = {
                seeking = it
                slider.value = it
            },
            onValueChangeFinished = {
                seeking?.let { player.seekTo((it * duration).toInt()) }
                seeking = null
            },
            enabled = player.prepared && duration > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock((fraction * duration).toInt()), style = MaterialTheme.typography.labelMedium, color = Color.White)
            Text(clock(duration), style = MaterialTheme.typography.labelMedium, color = Color.White)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { player.skip(-SKIP_MS) }, enabled = player.prepared) { Icon(Icons.Replay10, "Back 10 seconds", tint = Color.White) }
            Spacer(Modifier.size(24.dp))
            FilledIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    player.toggle()
                },
                modifier = Modifier.size(64.dp),
            ) {
                Icon(if (player.playing) Icons.Pause else Icons.PlayArrowFill, if (player.playing) "Pause" else "Play", Modifier.size(32.dp))
            }
            Spacer(Modifier.size(24.dp))
            IconButton(onClick = { player.skip(SKIP_MS) }, enabled = player.prepared) { Icon(Icons.Forward10, "Forward 10 seconds", tint = Color.White) }
        }
    }
}

private const val TICK_MS = 250L
private const val SKIP_MS = 10_000
