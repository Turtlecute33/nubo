package app.nubo.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.nubo.MainActivity
import app.nubo.R
import app.nubo.transfer.Summary
import app.nubo.util.Format

/**
 * Two quiet channels: transfers and camera upload; and one channel that alerts once when camera upload stops
 * for a reason that the user must fix. Notifications show counts, percentages and short text only, never
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
                NotificationChannel(PROBLEMS, "Camera upload problems", NotificationManager.IMPORTANCE_DEFAULT),
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

    /** Camera upload stopped: storage full, folder gone, session ended (review 38). [text] has no file names. */
    fun cameraStopped(text: String) {
        // The system drops a notification with no permission: do not post it.
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        setUp()
        nm.notify(PROBLEM_ID, builder(PROBLEMS).setContentTitle("Camera upload stopped").setContentText(text).setAutoCancel(true).build())
    }

    /** Camera upload works again: removes the notification of [cameraStopped]. */
    fun cameraWorks() = nm.cancel(PROBLEM_ID)

    /** [count] photos or videos did not upload and wait for the user (Camera upload screen). Only the count. */
    fun cameraSkipped(count: Int) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        setUp()
        val title = if (count == 1) "1 photo or video did not upload" else "$count photos or videos did not upload"
        nm.notify(SKIPPED_ID, builder(PROBLEMS).setContentTitle(title).setContentText("Open Camera upload in Nubo to try again").setAutoCancel(true).build())
    }

    /** No item waits for the user any more. */
    fun cameraSkippedDone() = nm.cancel(SKIPPED_ID)

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
        const val PROBLEMS = "problems"
        const val TRANSFERS_ID = 1
        const val CAMERA_ID = 2
        const val PROBLEM_ID = 3
        const val SKIPPED_ID = 4
    }
}
