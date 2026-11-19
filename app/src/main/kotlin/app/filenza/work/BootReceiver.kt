package app.filenza.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.filenza.app
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Schedules camera upload again after a reboot: the system cannot keep a job with content triggers over a
 * reboot (review 37). Not exported: only the system sends BOOT_COMPLETED. An app update keeps the job.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val a = context.app
        // goAsync keeps the process alive until the settings and the session are read off the main thread.
        val result = goAsync()
        a.scope.launch {
            try {
                a.ready.first { it }
                if (a.account.session != null) CameraJob.ensure(a, a.prefs.value.camera)
            } finally {
                result.finish()
            }
        }
    }
}
