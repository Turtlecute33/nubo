package app.filenza.work

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
import android.provider.MediaStore
import android.util.AtomicFile
import app.filenza.FilenzaApp
import app.filenza.app
import app.filenza.data.CameraUpload
import app.filenza.drive.UploadSource
import app.filenza.util.FileType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Camera upload (DESIGN §9): a job that runs when MediaStore has new photos or videos under DCIM, on the
 * network type that the user allows. It uploads the media added after [CameraUpload.since], in the order
 * they were added, and keeps a mark of the last one, so that each item uploads once. Android removes the
 * GPS position from the files: the app does not ask for media location access.
 */
class CameraJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val a = app
        val settings = a.prefs.value.camera
        val folder = settings.folder
        if (!settings.enabled || folder == null || a.account.session == null || !allowed(a, settings)) return false
        work = a.scope.launch {
            var again = false
            try {
                uploadNew(a, settings, folder)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                again = true // network or server problem: the system runs the job again with back-off
            } finally {
                jobFinished(params, again)
                if (!again) schedule(a, a.prefs.value.camera)
            }
        }
        return true
    }

    /** The system stops the job (time limit, network lost): run it again later. */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    private suspend fun uploadNew(a: FilenzaApp, settings: CameraUpload, folder: String) {
        val mark = Mark(a)
        for (entry in withContext(Dispatchers.IO) { pending(a, settings, mark.read()) }) {
            val src = UploadSource(entry.name, entry.mime, entry.modified, entry.taken) {
                a.contentResolver.openInputStream(entry.uri) ?: throw IOException("The file is gone")
            }
            a.uploader.upload(folder, src) {}
            withContext(Dispatchers.IO) { mark.write(entry.added, entry.id) }
        }
    }

    private class Entry(val uri: Uri, val id: Long, val added: Long, val name: String, val mime: String, val modified: Long, val taken: Long)

    /** New media under DCIM, after the mark and after the time when the user turned camera upload on. */
    private fun pending(a: FilenzaApp, s: CameraUpload, mark: Pair<Long, Long>): List<Entry> {
        val collections = buildList {
            add(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
            if (s.videos) add(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
        }
        val since = maxOf(s.since / 1000, mark.first)
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_TAKEN,
        )
        val where = "${MediaStore.MediaColumns.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE 'DCIM/%'"
        val out = ArrayList<Entry>()
        for (collection in collections) {
            a.contentResolver.query(collection, cols, where, arrayOf(since.toString()), null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val added = c.getLong(1)
                    if (added < mark.first || added == mark.first && id <= mark.second) continue
                    val name = c.getString(2)?.substringAfterLast('/') ?: continue
                    out += Entry(
                        ContentUris.withAppendedId(collection, id), id, added, name,
                        c.getString(3) ?: FileType.mime(name), c.getLong(4) * 1000, if (c.isNull(5)) 0 else c.getLong(5),
                    )
                }
            }
        }
        return out.sortedWith(compareBy({ it.added }, { it.id })).take(MAX_PER_RUN)
    }

    /** The last uploaded item: (DATE_ADDED in s, MediaStore ID). Stored in no-backup storage. */
    private class Mark(context: Context) {
        private val file = AtomicFile(File(context.noBackupFilesDir, "camera-mark.json"))

        fun read(): Pair<Long, Long> = runCatching {
            JSONObject(file.readFully().decodeToString()).let { it.getLong("added") to it.getLong("id") }
        }.getOrDefault(0L to 0L)

        fun write(added: Long, id: Long) {
            val out = file.startWrite()
            try {
                out.write(JSONObject().put("added", added).put("id", id).toString().encodeToByteArray())
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        }
    }

    companion object {
        private const val ID = 2
        private const val MAX_PER_RUN = 200

        fun allowed(context: Context, s: CameraUpload): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                (!s.videos || context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED)

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
