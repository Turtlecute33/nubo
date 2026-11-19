package app.nubo

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nubo.drive.Links
import app.nubo.ui.AppRoot
import app.nubo.ui.lock.AppLock
import app.nubo.ui.nav.Navigator
import app.nubo.ui.share.SecretClip
import app.nubo.ui.theme.NuboTheme
import app.nubo.ui.theme.edgeToEdge

/** The only activity. Takes shares (files to upload, Filen links) and applies the window protections. */
class MainActivity : ComponentActivity() {
    private val nav: Navigator by viewModels()

    /** True from a stop (not a rotation) to the next focus: the user comes back to the app. */
    private var stopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        edgeToEdge(dark = false)
        super.onCreate(savedInstanceState)
        keepSplashUntilReady()
        // No overlay of another app over any window of this app, and a touch through one does nothing. DESIGN §7.
        window.setHideOverlayWindows(true)
        window.decorView.filterTouchesWhenObscured = true
        if (savedInstanceState == null) take(intent)
        setContent {
            val settings by app.prefs.state.collectAsStateWithLifecycle()
            LaunchedEffect(settings.blockScreenshots, settings.appLock) { protect(settings.blockScreenshots, settings.appLock) }
            NuboTheme(settings) { AppRoot(nav) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onStop() {
        super.onStop()
        // Not for a configuration change: the lock would show after each rotation.
        if (!isChangingConfigurations) {
            AppLock.onBackground(app.prefs.value.appLock)
            stopped = true
        }
    }

    /** The app can read the clipboard only with the focus: then it clears a copied link (review 49). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        SecretClip.onFocus(this, returned = stopped)
        stopped = false
    }

    /** No screenshots or screen recording while [secure]; no recents preview while [secure] or [locked] (review 50). */
    private fun protect(secure: Boolean, locked: Boolean) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setRecentsScreenshotEnabled(!(secure || locked))
    }

    /**
     * A share from another app: files to upload, or a Filen link. All incoming data is untrusted: only content
     * URIs of other apps, at most [MAX_SHARED] of them, and only the text of a link.
     */
    private fun take(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val uris = buildList {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::addAll)
                }.filter(::foreignContent).distinct().take(MAX_SHARED)
                if (uris.isNotEmpty()) {
                    nav.pendingUploads = uris
                    return
                }
                intent.getStringExtra(Intent.EXTRA_TEXT)?.take(4096)?.let(Links::find)?.let { nav.pendingLink = it }
            }
            Intent.ACTION_VIEW -> intent.dataString?.take(4096)?.let(Links::find)?.let { nav.pendingLink = it }
        }
    }

    /** A content URI of another app. A user prefix ("0@") can name this app's own provider: refuse an '@' (review 62). */
    private fun foreignContent(uri: Uri): Boolean {
        val authority = uri.authority ?: return false
        return uri.scheme == ContentResolver.SCHEME_CONTENT && '@' !in authority && !authority.startsWith(packageName)
    }

    /** Holds the system splash screen until the session file is read, so that no login screen flashes. */
    private fun keepSplashUntilReady() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!app.ready.value) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }

    private companion object {
        const val MAX_SHARED = 500
    }
}
