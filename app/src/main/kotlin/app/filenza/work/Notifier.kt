package app.filenza.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.filenza.MainActivity
import app.filenza.R
import app.filenza.transfer.Summary
import app.filenza.util.Format

/**
 * Two quiet channels: transfers and camera upload. Notifications show counts and percentages only, never
 * file names, and nothing on a secure lock screen (VISIBILITY_SECRET). They stay on the phone (local only).
 */
class Notifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    /** Creates the channels. Cheap; call before the first notification of each process. */
    fun setUp() {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(TRANSFERS, "Transfers", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CAMERA, "Camera upload", NotificationManager.IMPORTANCE_MIN),
            ),
        )
    }

    fun transfers(s: Summary): Notification {
        val title = when {
            s.active == 0 -> "Transfers done"
            s.uploads == s.active -> plural(s.active, "upload")
            s.uploads == 0 -> plural(s.active, "download")
            else -> plural(s.active, "transfer")
        }
        val text = "${(s.progress * 100).toInt()}%" + if (s.speed > 0) " · ${Format.speed(s.speed)}" else ""
        return builder(TRANSFERS).setContentTitle(title).setContentText(text)
            .setProgress(100, (s.progress * 100).toInt(), s.size <= 0)
            .setOngoing(true)
            .build()
    }

    fun camera(left: Int): Notification =
        builder(CAMERA).setContentTitle("Camera upload").setContentText(if (left > 0) "${plural(left, "item")} to upload" else "Checking for new photos")
            .setOngoing(true).build()

    private fun builder(channel: String) = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setVisibility(Notification.VISIBILITY_SECRET)
        .setLocalOnly(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    companion object {
        const val TRANSFERS = "transfers"
        const val CAMERA = "camera"
        const val TRANSFERS_ID = 1
        const val CAMERA_ID = 2
    }
}
