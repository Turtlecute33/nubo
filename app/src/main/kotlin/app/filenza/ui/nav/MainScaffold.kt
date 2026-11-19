package app.filenza.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import app.filenza.app
import app.filenza.ui.Icons
import app.filenza.util.Format

private class TabInfo(val tab: Tab, val label: String, val icon: ImageVector, val selected: ImageVector)

private val tabs = listOf(
    TabInfo(Tab.HOME, "Home", Icons.Home, Icons.HomeFill),
    TabInfo(Tab.DRIVE, "Drive", Icons.Cloud, Icons.CloudFill),
    TabInfo(Tab.PHOTOS, "Photos", Icons.PhotoLibrary, Icons.PhotoLibrary),
    TabInfo(Tab.ME, "Me", Icons.AccountCircle, Icons.AccountCircleFill),
)

/**
 * The four tabs, each with its own back stack. The state of a tab (scroll places, open dialogs) stays while
 * another tab shows. Full-screen routes hide the tab bar.
 */
@Composable
fun MainScaffold(nav: Navigator) {
    val app = LocalContext.current.app
    val haptics = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val holder = rememberSaveableStateHolder()
    val full = nav.current is Route.FullScreen

    LaunchedEffect(Unit) {
        app.messenger.messages.collect { m ->
            val result = snackbar.showSnackbar(m.text, m.action, withDismissAction = m.action == null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) m.onAction?.invoke()
        }
    }

    // Back on the root of another tab goes to Home; on Home's root the system closes the app.
    BackHandler(enabled = nav.backStack.size == 1 && nav.tab != Tab.HOME) { nav.select(Tab.HOME) }

    Box(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                Crossfade(nav.tab, label = "tab") { tab ->
                    holder.SaveableStateProvider(tab.name) { TabStack(nav, tab) }
                }
                TransferPill(
                    visible = !full && nav.current != Route.Transfers,
                    onClick = { nav.go(Route.Transfers) },
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                )
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
            }
            AnimatedVisibility(!full, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                NavigationBar {
                    tabs.forEach { t ->
                        val selected = nav.tab == t.tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                nav.select(t.tab)
                            },
                            icon = { Icon(if (selected) t.selected else t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabStack(nav: Navigator, tab: Tab) {
    val forward = sharedAxis<Scene<Route>>(forward = true)
    val backward = sharedAxis<Scene<Route>>(forward = false)
    NavDisplay(
        backStack = nav.stack(tab),
        onBack = nav::back,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        transitionSpec = forward,
        popTransitionSpec = backward,
        predictivePopTransitionSpec = { backward() },
        entryProvider = { route -> NavEntry(route, contentKey = route.toString()) { RouteContent(it) } },
    )
}

private typealias Transition<S> = AnimatedContentTransitionScope<S>.() -> ContentTransform

/** Material shared-axis X with the theme's expressive motion: a short slide plus a cross-fade. */
@Composable
private fun <S> sharedAxis(forward: Boolean): Transition<S> {
    val motion = MaterialTheme.motionScheme
    val slide = motion.defaultSpatialSpec<IntOffset>()
    val fadeIn = motion.defaultEffectsSpec<Float>()
    val fadeOut = motion.fastEffectsSpec<Float>()
    val sign = if (forward == (LocalLayoutDirection.current == LayoutDirection.Ltr)) 1 else -1
    val offset = sign * with(LocalDensity.current) { 48.dp.roundToPx() }
    return {
        (slideInHorizontally(slide) { offset } + fadeIn(fadeIn)) togetherWith
            (slideOutHorizontally(slide) { -offset } + fadeOut(fadeOut))
    }
}

/** A small floating progress for running transfers; opens the Transfers screen. */
@Composable
private fun TransferPill(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val transfers = LocalContext.current.app.transfers
    val list by transfers.list.collectAsStateWithLifecycle()
    val s = remember(list) { transfers.summary(list) }
    AnimatedVisibility(
        visible = visible && s.active > 0,
        modifier = modifier,
        enter = scaleIn() + fadeIn() + slideInVertically { it },
        exit = scaleOut() + fadeOut() + slideOutVertically { it },
    ) {
        Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, shadowElevation = 6.dp) {
            Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    CircularWavyProgressIndicator(progress = { s.progress }, modifier = Modifier.size(36.dp))
                    Icon(if (s.uploads * 2 >= s.active) Icons.ArrowUpward else Icons.ArrowDownward, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("${(s.progress * 100).toInt()}%" + if (s.speed > 0) " · ${Format.speed(s.speed)}" else "", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
