package app.nubo.transfer

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import app.nubo.NuboApp
import app.nubo.app
import app.nubo.work.Notifier
import kotlinx.coroutines.CancellationException
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
        queued = true
        a.notifier.setUp()
        setNotification(params, Notifier.TRANSFERS_ID, a.notifier.transfers(a.transfers.summary(a.transfers.list.value)), JOB_END_NOTIFICATION_POLICY_REMOVE)
        work = a.scope.launch {
            var stopped = false
            try {
                // At most one notification update a second: the system drops faster ones.
                while (true) {
                    val summary = a.transfers.summary(a.transfers.list.value)
                    if (summary.active == 0) break
                    setNotification(params, Notifier.TRANSFERS_ID, a.notifier.transfers(summary), JOB_END_NOTIFICATION_POLICY_REMOVE)
                    delay(1_000)
                }
            } catch (e: CancellationException) {
                stopped = true
                throw e
            } finally {
                // After onStopJob, a jobFinished would end the job with no new run (review 41).
                if (!stopped) {
                    queued = false
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    /**
     * The user stopped it in the Task Manager: stop the transfers too. Other stops (network, system): the system
     * runs the job again while transfers wait, so that the process does not run on with no job (review 41).
     */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        val again = params.stopReason != JobParameters.STOP_REASON_USER && app.transfers.summary(app.transfers.list.value).active > 0
        if (params.stopReason == JobParameters.STOP_REASON_USER) app.transfers.cancelAll()
        queued = again
        return again
    }

    companion object {
        private const val ID = 1

        /**
         * True from a good schedule until the job ends. [ensure] then makes no binder call, and it cannot miss a
         * job that ends at the same time (review 78). Main thread only, like [Transfers].
         */
        private var queued = false

        /** Schedules the job if it does not run. Works only while the app is visible; otherwise the queue runs while the process lives. */
        fun ensure(app: NuboApp) {
            if (queued) return
            val js = app.getSystemService(JobScheduler::class.java)
            val info = JobInfo.Builder(ID, ComponentName(app, TransferJob::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            queued = runCatching { js.schedule(info) == JobScheduler.RESULT_SUCCESS }.getOrDefault(false)
        }

        fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(ID)
    }
}
