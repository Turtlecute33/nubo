package app.filenza.transfer

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.filenza.FilenzaApp
import app.filenza.app
import app.filenza.work.Notifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A user-initiated data transfer job that keeps the process running while transfers are in the queue, also
 * when the user leaves the app, and shows their progress. The transfers themselves run in [Transfers].
 */
class TransferJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val a = app
        a.notifier.setUp()
        setNotification(params, Notifier.TRANSFERS_ID, a.notifier.transfers(a.transfers.summary(a.transfers.list.value)), JOB_END_NOTIFICATION_POLICY_REMOVE)
        work = a.scope.launch {
            try {
                // At most one notification update a second: the system drops faster ones.
                while (true) {
                    val summary = a.transfers.summary(a.transfers.list.value)
                    if (summary.active == 0) break
                    setNotification(params, Notifier.TRANSFERS_ID, a.notifier.transfers(summary), JOB_END_NOTIFICATION_POLICY_REMOVE)
                    delay(1_000)
                }
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    /** The user stopped it in the Task Manager: stop the transfers too. Other stops: the transfers go on while the process lives. */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        if (params.stopReason == JobParameters.STOP_REASON_USER) app.transfers.cancelAll()
        return false
    }

    companion object {
        private const val ID = 1

        /** Schedules the job if it does not run. Works only while the app is visible; otherwise the queue runs while the process lives. */
        fun ensure(app: FilenzaApp) {
            val js = app.getSystemService(JobScheduler::class.java)
            if (js.getPendingJobReason(ID) == JobScheduler.PENDING_JOB_REASON_EXECUTING) return
            val info = JobInfo.Builder(ID, ComponentName(app, TransferJob::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            runCatching { js.schedule(info) }
        }

        fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(ID)
    }
}
