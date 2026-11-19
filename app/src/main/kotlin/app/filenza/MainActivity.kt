package app.filenza

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
import app.filenza.data.AccountState
import app.filenza.drive.Links
import app.filenza.ui.AppRoot
import app.filenza.ui.lock.AppLock
import app.filenza.ui.nav.Navigator
import app.filenza.ui.theme.FilenzaTheme
import app.filenza.ui.theme.edgeToEdge

/** The only activity. Takes shares (files to upload, Filen links) and applies the window protections. */
class MainActivity : ComponentActivity() {
    private val nav: Navigator by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        edgeToEdge(dark = false)
        super.onCreate(savedInstanceState)
        keepSplashUntilReady()
        // A touch that goes through an overlay of another app (tapjacking) does nothing. DESIGN §7.
        window.decorView.filterTouchesWhenObscured = true
        if (savedInstanceState == null) take(intent)
        setContent {
            val settings by app.prefs.state.collectAsStateWithLifecycle()
            LaunchedEffect(settings.blockScreenshots) { protect(settings.blockScreenshots) }
            FilenzaTheme(settings) { AppRoot(nav) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onStop() {
        super.onStop()
        // Not for a configuration change: the lock would show after each rotation.
        if (!isChangingConfigurations) AppLock.onBackground(app.prefs.value.appLock)
    }

    /** No screenshots, screen recording or recents preview while [on]. */
    private fun protect(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setRecentsScreenshotEnabled(!on)
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

    private fun foreignContent(uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority?.startsWith(packageName) == false

    /** Holds the system splash screen until the session file is read, so that no login screen flashes. */
    private fun keepSplashUntilReady() {
        val content = findViewById<View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (app.account.state.value == AccountState.Loading) return false
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }

    private companion object {
        const val MAX_SHARED = 500
    }
}
