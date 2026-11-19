package app.filenza.ui

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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.filenza.app
import app.filenza.data.AccountState
import app.filenza.ui.drive.UploadTarget
import app.filenza.ui.links.PublicLinkScreen
import app.filenza.ui.lock.AppLock
import app.filenza.ui.lock.LockScreen
import app.filenza.ui.login.LoginScreen
import app.filenza.ui.nav.LocalNav
import app.filenza.ui.nav.MainScaffold
import app.filenza.ui.nav.Navigator
import app.filenza.ui.nav.Route
import app.filenza.ui.nav.Tab

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
    AnimatedContent(state is AccountState.LoggedIn, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "root") { inside ->
        when {
            inside -> MainScaffold(nav)
            state == AccountState.LoggedOut -> {
                // A public link opens with no account, over the login screen.
                val link = nav.pendingLink
                if (link != null) PublicLinkScreen(link, onClose = { nav.pendingLink = null }) else LoginScreen()
                LaunchedEffect(Unit) { nav.reset() }
            }
            else -> Box(Modifier.fillMaxSize())
        }
    }
    if (state is AccountState.LoggedIn) {
        // Shares from other apps: a link opens in the Home tab; files ask for a folder.
        val link = nav.pendingLink
        if (link != null) LaunchedEffect(link) {
            nav.goIn(Tab.HOME, Route.PublicLink(link))
            nav.pendingLink = null
        }
        if (nav.pendingUploads.isNotEmpty()) {
            UploadTarget(nav.pendingUploads, onDone = { nav.pendingUploads = emptyList() })
        }
    }
}
