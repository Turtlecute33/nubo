package app.nubo.ui.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copies a secret (a link with its key) as sensitive text: keyboards and the clipboard preview do not show
 * it. The app can read the clipboard only while one of its windows has the focus. So the app clears the clip
 * when the user comes back to it ([onFocus]), or after [CLEAR_AFTER_MS] while it shows. It clears the clip only
 * while it is still this app's clip (the same timestamp): a clip that the user copied later in another app
 * stays. DESIGN §7, review 49.
 */
object SecretClip {
    private val main = Handler(Looper.getMainLooper())

    /** The timestamp of this app's clip until the app clears it. */
    private var copiedAt: Long? = null

    /** True after [CLEAR_AFTER_MS]: then each focus of the app tries to clear the clip. */
    private var due = false

    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        copiedAt = clipboard.primaryClipDescription?.timestamp
        due = false
        val app = context.applicationContext
        main.removeCallbacksAndMessages(null)
        main.postDelayed({
            due = true
            clear(app)
        }, CLEAR_AFTER_MS)
    }

    /**
     * Call when a window of the app gets the focus. [returned]: the app was in the background before, so the
     * user pasted the link or does not need it now. Not at each focus: a closed sheet gives the focus back too.
     */
    fun onFocus(context: Context, returned: Boolean) {
        if (returned || due) clear(context.applicationContext)
    }

    private fun clear(context: Context) {
        val at = copiedAt ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        // Null with no read access (no focus) or with an empty clipboard: try again at the next focus.
        val current = clipboard.primaryClipDescription ?: return
        if (current.timestamp == at) clipboard.clearPrimaryClip()
        copiedAt = null
        due = false
    }

    private const val CLEAR_AFTER_MS = 60_000L
}
