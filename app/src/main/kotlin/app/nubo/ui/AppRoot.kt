package app.nubo.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nubo.app
import app.nubo.data.AccountState
import app.nubo.ui.drive.UploadTarget
import app.nubo.ui.links.PublicLinkScreen
import app.nubo.ui.lock.AppLock
import app.nubo.ui.lock.LockScreen
import app.nubo.ui.login.LoginScreen
import app.nubo.ui.nav.LocalNav
import app.nubo.ui.nav.MainScaffold
import app.nubo.ui.nav.Navigator
import app.nubo.ui.nav.Route
import app.nubo.ui.nav.Tab

/** Login or the app, the lock over both, and the shares that other apps sent. */
@Composable
fun AppRoot(nav: Navigator) {
    val app = LocalContext.current.app
    val ready by app.ready.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    if (nav.finished) LaunchedEffect(Unit) { activity?.finish() }
    // Nothing reads the session or the settings before they are loaded (the splash screen shows until then).
    if (!ready) return
    val state by app.account.state.collectAsStateWithLifecycle()
    val settings by app.prefs.state.collectAsStateWithLifecycle()
    val locked by AppLock.locked.collectAsStateWithLifecycle()
    val holder = rememberSaveableStateHolder()

    CompositionLocalProvider(LocalNav provides nav) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            // While locked, the app is not composed at all: dialogs and sheets are windows above the lock
            // screen, so they must not exist (review 2). The saveable state comes back after the unlock.
            if (settings.appLock && locked) {
                LockScreen(onUnlocked = AppLock::unlock)
                return@Surface
            }
            holder.SaveableStateProvider("app") { Content(nav, state) }
        }
    }
    if (!settings.appLock) DisposableEffect(Unit) {
        AppLock.unlock()
        onDispose {}
    }
}

@Composable
private fun Content(nav: Navigator, state: AccountState) {
    val motion = MaterialTheme.motionScheme
    val enter = motion.defaultEffectsSpec<Float>()
    val exit = motion.fastEffectsSpec<Float>()
    // Each branch reads its own target state: during the fade the old content keeps the state that it shows
    // (review 100).
    AnimatedContent(state, transitionSpec = { fadeIn(enter) togetherWith fadeOut(exit) }, contentKey = { it is AccountState.LoggedIn }, label = "root") { target ->
        when (target) {
            is AccountState.LoggedIn -> MainScaffold(nav)
            AccountState.LoggedOut -> {
                // A public link opens with no account, over the login screen.
                val link = nav.pendingLink
                if (link != null) PublicLinkScreen(link, onClose = { nav.pendingLink = null }) else LoginScreen()
                LaunchedEffect(Unit) { nav.reset() }
            }
            AccountState.Loading -> Box(Modifier.fillMaxSize())
        }
    }
    if (state is AccountState.LoggedIn) {
        // Shares from other apps: a link opens in the Home tab; files ask for a folder.
        val link = nav.pendingLink
        if (link != null) LaunchedEffect(link) {
            nav.goIn(Tab.HOME, Route.PublicLink(link))
            nav.pendingLink = null
        }
        // One dialog for each share, in turn. The key gives the next share a new dialog, not the folder of the last one.
        nav.pendingUploads.firstOrNull()?.let { uris ->
            key(uris) { UploadTarget(uris, onDone = { nav.pendingUploads.remove(uris) }) }
        }
    }
}
