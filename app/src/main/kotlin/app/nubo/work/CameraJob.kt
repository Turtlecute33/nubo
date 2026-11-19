package app.nubo.work

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.AtomicFile
import app.nubo.NuboApp
import app.nubo.api.ApiException
import app.nubo.app
import app.nubo.data.AccountState
import app.nubo.data.CameraUpload
import app.nubo.drive.NameRules
import app.nubo.drive.UploadSource
import app.nubo.util.FileType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Camera upload (DESIGN §9): a job that runs when MediaStore has new photos or videos under DCIM, on the
 * network type that the user allows. It uploads the media added after [CameraUpload.since], in the order
 * they were added, and keeps a mark of the last one, so that each item uploads once. Android removes the
 * GPS position from the files: the app does not ask for media location access.
 */
class CameraJob : JobService() {
    private var work: Job? = null

    /** Why the system stopped the run. Only the time limit counts as a try of the item (review 40). */
    @Volatile
    private var stopReason = JobParameters.STOP_REASON_UNDEFINED

    override fun onStartJob(params: JobParameters): Boolean {
        val a = app
        stopReason = JobParameters.STOP_REASON_UNDEFINED
        work = a.scope.launch {
            var again = false
            var stopped = false
            var more = false
            var active = false
            try {
                // The job can start the process: wait until the settings and the session are read from the disk.
                a.account.state.first { it != AccountState.Loading }
                val settings = a.prefs.value.camera
                val folder = settings.folder
                if (settings.enabled && folder != null && a.account.session != null && allowed(a, settings)) {
                    active = true
                    more = uploadNew(a, settings, folder)
                }
            } catch (e: CancellationException) {
                stopped = true
                throw e
            } catch (e: Exception) {
                // An account or folder problem: tell the user, and try again at the next new photo with no back-off
                // (review 38). A network or server problem: the system runs the job again with back-off.
                val problem = accountProblem(e)
                if (problem != null) a.notifier.cameraStopped(problem) else again = true
            } finally {
                // After onStopJob the system runs the job again itself: no second schedule here.
                if (!stopped) {
                    jobFinished(params, again)
                    val now = a.prefs.value.camera
                    when {
                        again || !active -> Unit
                        more -> runNow(a, now)
                        else -> schedule(a, now)
                    }
                }
            }
        }
        return true
    }

    /** The system stops the job (time limit, network lost): run it again later. */
    override fun onStopJob(params: JobParameters): Boolean {
        stopReason = params.stopReason
        work?.cancel()
        return true
    }

    /**
     * Uploads the pending media in order and moves the mark after each one. A file that cannot be read or has
     * a name that Filen refuses is skipped; any other error stops the run (the job runs again later). Returns
     * true when more media wait than one run takes.
     */
    private suspend fun uploadNew(a: NuboApp, settings: CameraUpload, folder: String): Boolean {
        val began = SystemClock.elapsedRealtime()
        val mark = withContext(Dispatchers.IO) { Mark(a, folder) }
        val entries = withContext(Dispatchers.IO) { pending(a, settings, mark) }
        var good = false
        for (entry in entries) {
            // A job has about 10 min. Stop before the time limit and run again at once: a time-out would grow
            // the back-off (review 83).
            if (SystemClock.elapsedRealtime() - began > RUN_MS) return true
            // An item that failed or ran out of job time MAX_TRIES times (a very large video) gives way, so
            // that it does not block every later photo (review 8).
            if (mark.spent(entry.id)) {
                withContext(Dispatchers.IO) { mark.finish(entry.id, entry.added) }
                continue
            }
            // A cloud file with this name: the same photo (same size) is skipped, another file gets a free
            // name, because an upload with the same name replaces the cloud file (review 9, F-52).
            val name = a.drive.ops.freeName(folder, entry.name, entry.size)
            if (name == null) {
                withContext(Dispatchers.IO) { mark.finish(entry.id, entry.added) }
                continue
            }
            val src = UploadSource(name, entry.mime, entry.modified, entry.taken) {
                try {
                    a.contentResolver.openInputStream(entry.uri) ?: throw Unreadable()
                } catch (_: FileNotFoundException) {
                    throw Unreadable()
                } catch (_: SecurityException) {
                    throw Unreadable()
                }
            }
            if (NameRules.problem(name) == null) {
                val uploaded = try {
                    // The try counts just before the upload: a stop in freeName is not a try (review 40).
                    withContext(Dispatchers.IO + NonCancellable) { mark.attempt(entry.id) }
                    a.uploader.upload(folder, src) {}
                    true
                } catch (_: Unreadable) {
                    false // deleted or moved since the query, or no longer readable: nothing to upload
                } catch (e: CancellationException) {
                    // Wi-Fi lost, "Check now" or a setting change is not the item's fault; the time limit is (a
                    // very large video). After a logout the mark is gone: do not write it again (review 40).
                    if (stopReason != JobParameters.STOP_REASON_TIMEOUT && a.account.session != null) {
                        withContext(Dispatchers.IO + NonCancellable) { mark.refund() }
                    }
                    throw e
                } catch (e: Exception) {
                    // Only an error of this item counts as a try (review 38).
                    if (!itemError(e)) withContext(Dispatchers.IO + NonCancellable) { mark.refund() }
                    throw e
                }
                // The first good upload of a run removes an old "Camera upload stopped" notification.
                if (uploaded && !good) a.notifier.cameraWorks()
                good = good || uploaded
            }
            withContext(Dispatchers.IO) { mark.finish(entry.id, entry.added) }
        }
        return entries.size >= MAX_PER_RUN
    }

    /** A media file that went away between the query and the upload. */
    private class Unreadable : IOException("The file is gone")

    private class Entry(
        val uri: Uri,
        val id: Long,
        val added: Long,
        val name: String,
        val mime: String,
        val modified: Long,
        val taken: Long,
        val size: Long,
    )

    /**
     * Media under DCIM that is not done, added after the time when the user turned camera upload on. The query
     * looks back [Mark.WINDOW_S] before the newest done item: a long video is added as pending and appears
     * later with its first time, after newer photos (review 14).
     */
    private fun pending(a: NuboApp, s: CameraUpload, mark: Mark): List<Entry> {
        val collections = buildList {
            add(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
            if (s.videos) add(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
        }
        val since = maxOf(s.since / 1000, mark.added - Mark.WINDOW_S)
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.SIZE,
        )
        val where = "${MediaStore.MediaColumns.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/%'"
        val out = ArrayList<Entry>()
        for (collection in collections) {
            a.contentResolver.query(collection, cols, where, arrayOf(since.toString()), null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val added = c.getLong(1)
                    if (mark.isDone(id)) continue
                    val name = c.getString(2)?.substringAfterLast('/') ?: continue
                    out += Entry(
                        ContentUris.withAppendedId(collection, id), id, added, name,
                        c.getString(3) ?: FileType.mime(name), c.getLong(4) * 1000, if (c.isNull(5)) 0 else c.getLong(5),
                        if (c.isNull(6)) -1 else c.getLong(6),
                    )
                }
            }
        }
        return out.sortedWith(compareBy({ it.added }, { it.id })).take(MAX_PER_RUN)
    }

    /**
     * What is done, for one cloud folder (another folder starts empty): the newest DATE_ADDED (s) and the IDs
     * done in the last [WINDOW_S] before it, and the tries of the item that runs now. No-backup storage.
     */
    private class Mark(context: Context, private val folder: String) {
        private val file = AtomicFile(File(context.noBackupFilesDir, MARK_FILE))
        var added = 0L
            private set
        private val done = HashMap<Long, Long>()
        private var tryId = -1L
        private var tries = 0

        init {
            runCatching {
                val o = JSONObject(file.readFully().decodeToString())
                if (o.optString("folder") == folder) {
                    added = o.optLong("added")
                    o.optJSONObject("done")?.let { d -> d.keys().forEach { k -> done[k.toLong()] = d.getLong(k) } }
                    tryId = o.optLong("tryId", -1)
                    tries = o.optInt("tries")
                }
            }
        }

        fun isDone(id: Long) = id in done

        /** True when [id] had [MAX_TRIES] tries. */
        fun spent(id: Long) = tryId == id && tries >= MAX_TRIES

        /** Counts a try of [id]. */
        fun attempt(id: Long) {
            if (tryId == id) tries++ else {
                tryId = id
                tries = 1
            }
            save()
        }

        fun refund() {
            if (tries > 0) tries--
            save()
        }

        fun finish(id: Long, at: Long) {
            added = maxOf(added, at)
            done[id] = at
            done.entries.removeAll { it.value < added - WINDOW_S }
            tryId = -1
            tries = 0
            save()
        }

        private fun save() {
            val o = JSONObject().put("folder", folder).put("added", added).put("tryId", tryId).put("tries", tries)
                .put("done", JSONObject().apply { done.forEach { (k, v) -> put(k.toString(), v) } })
            val out = file.startWrite()
            try {
                out.write(o.toString().encodeToByteArray())
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        }

        companion object {
            const val WINDOW_S = 24 * 3600L
            const val MAX_TRIES = 3
        }
    }

    companion object {
        private const val ID = 2
        private const val MAX_PER_RUN = 200

        /** About 8 min of the 10 min that the system gives a job (review 83). */
        private const val RUN_MS = 8 * 60_000L

        /** The mark file. A logout must delete it with the other account data. */
        const val MARK_FILE = "camera-mark.json"

        fun allowed(context: Context, s: CameraUpload): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                (!s.videos || context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED)

        /**
         * Schedules only when no job is there: at a process start, a schedule() would stop the job that started
         * the process (review 3).
         */
        fun ensure(context: Context, s: CameraUpload) {
            if (s.enabled && s.folder != null && context.getSystemService(JobScheduler::class.java).getPendingJob(ID) != null) return
            schedule(context, s)
        }

        /** Schedules the next run for the next media change; cancels when camera upload is off. */
        fun schedule(context: Context, s: CameraUpload) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (!s.enabled || s.folder == null) return js.cancel(ID)
            val flags = JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
            val info = JobInfo.Builder(ID, ComponentName(context, CameraJob::class.java))
                .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, flags))
                .apply { if (s.videos) addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, flags)) }
                .setTriggerContentUpdateDelay(5_000)
                .setTriggerContentMaxDelay(60_000)
                .setRequiredNetworkType(if (s.unmeteredOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .build()
            runCatching { js.schedule(info) }
        }

        /** Runs once now (after the user turns camera upload on), on the allowed network. */
        fun runNow(context: Context, s: CameraUpload) {
            if (!s.enabled || s.folder == null) return
            val info = JobInfo.Builder(ID, ComponentName(context, CameraJob::class.java))
                .setRequiredNetworkType(if (s.unmeteredOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .build()
            runCatching { context.getSystemService(JobScheduler::class.java).schedule(info) }
        }

        fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(ID)
    }
}

private val STORAGE_FULL = setOf("max_storage_reached", "storage_limit_reached")
private val FOLDER_GONE = setOf("folder_not_found", "invalid_folder")

/**
 * Short text for an error of the account or the cloud folder, not of one item: storage full, folder gone,
 * session ended. Null for other errors. Such an error stops the run with no try counted (review 38).
 */
internal fun accountProblem(e: Throwable): String? = when {
    e !is ApiException -> null
    e.sessionEnded -> "The session ended. Log in again."
    e.code in STORAGE_FULL -> "Your Filen storage is full"
    e.code in FOLDER_GONE -> "The camera upload folder is not there any more"
    else -> null
}

/**
 * True for an error that comes from the item, so that it counts as a try: a refusal of this file by the
 * server. A lost network, a busy server and the errors of [accountProblem] do not count. An error that is not
 * an API error is not the item's fault either: before review 34, such an error (a bug) skipped every photo
 * (review 38).
 */
internal fun itemError(e: Throwable): Boolean =
    e is ApiException && accountProblem(e) == null && e.http < 500 && e.http != 429 && e.code != "rate_limited" && e.code != "internal_error"
