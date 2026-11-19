package app.filenza.ui.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copies a secret (a link with its key) as sensitive text: keyboards and the clipboard preview do not show
 * it. After [CLEAR_AFTER_MS] the clip is cleared if it is still this app's clip, or if the app cannot see
 * whose clip it is (in the background). DESIGN §7.
 */
object SecretClip {
    private val main = Handler(Looper.getMainLooper())
    private var copiedAt: Long? = null

    fun copy(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        clipboard.setPrimaryClip(clip)
        copiedAt = clipboard.primaryClipDescription?.timestamp
        val app = context.applicationContext
        main.removeCallbacksAndMessages(null)
        main.postDelayed({ clear(app) }, CLEAR_AFTER_MS)
    }

    private fun clear(context: Context) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val at = copiedAt
        // Null in the background, or when the clipboard is empty.
        val current = clipboard.primaryClipDescription
        if (current == null || at == null || current.timestamp == at) clipboard.clearPrimaryClip()
        copiedAt = null
    }

    private const val CLEAR_AFTER_MS = 60_000L
}
