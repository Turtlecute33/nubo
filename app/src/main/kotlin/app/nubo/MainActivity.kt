package app.nubo

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.BadParcelableException
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
            SecretClip.onBackground()
        }
    }

    /** The app can read the clipboard only with the focus: then it clears a copied link (review 49). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) SecretClip.onFocus(this)
    }

    /** No screenshots or screen recording while [secure]; no recents preview while [secure] or [locked] (review 50). */
    private fun protect(secure: Boolean, locked: Boolean) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setRecentsScreenshotEnabled(!(secure || locked))
    }

    /**
     * A share from another app: files to upload, or a Filen link. All incoming data is untrusted: only content
     * URIs of other apps that the sender granted, at most [MAX_SHARED] of them, and only the text of a link.
     */
    private fun take(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val uris = granted(intent).filter(::foreignContent).distinct().take(MAX_SHARED)
                if (uris.isNotEmpty()) {
                    nav.pendingUploads.add(uris)
                    return
                }
                // Any app can send an extra that does not load (an unknown Parcelable): ignore the share, no crash.
                val text = try {
                    intent.getStringExtra(Intent.EXTRA_TEXT)
                } catch (_: BadParcelableException) {
                    null
                }
                text?.take(4096)?.let(Links::find)?.let { nav.pendingLink = it }
            }
            Intent.ACTION_VIEW -> intent.dataString?.take(4096)?.let(Links::find)?.let { nav.pendingLink = it }
        }
    }

    /**
     * The URIs of a share that come with a read grant. For every sender the system copies EXTRA_STREAM to the
     * ClipData with a grant, and then checks that the sender can read them (MediaStore asks for this check
     * always). A bare EXTRA_STREAM can name a photo that only this app can read, with its media permission:
     * the app would upload it for the sender.
     */
    private fun granted(intent: Intent): List<Uri> {
        val clip = intent.clipData ?: return emptyList()
        if ((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
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
