package app.nubo

import android.app.Application
import android.content.Context
import android.os.StrictMode
import app.nubo.api.Api
import app.nubo.data.Account
import app.nubo.data.AccountState
import app.nubo.data.Prefs
import app.nubo.data.SessionStore
import app.nubo.data.Vault
import app.nubo.drive.Content
import app.nubo.drive.Drive
import app.nubo.drive.PublicLinks
import app.nubo.drive.Uploader
import app.nubo.media.FileProvider
import app.nubo.media.Thumbs
import app.nubo.net.Http
import app.nubo.offline.Offline
import app.nubo.transfer.Transfers
import app.nubo.ui.Messenger
import app.nubo.ui.links.LinkSessions
import app.nubo.ui.viewer.Viewers
import app.nubo.work.CameraJob
import app.nubo.work.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-wide objects (no DI framework). Heavy parts (the HTTP engine, the Keystore) start on first use, off the
 * main thread. DESIGN §4.
 */
class NuboApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val messenger = Messenger()
    lateinit var prefs: Prefs
        private set

    private val vault by lazy { Vault() }
    val http by lazy { Http(this) }
    val api by lazy { Api(http) }
    val account by lazy { Account(api, SessionStore(noBackupFilesDir, vault), { java.io.File(noBackupFilesDir, WIPE_MARK).createNewFile() }, ::wipe) }
    val offline by lazy { Offline(this, { account.session?.cacheKey }) }
    val content by lazy { Content(api, offline) }
    val drive by lazy { Drive(api, account, offline, scope, messenger::show) }
    val uploader by lazy { Uploader(drive) }
    val links by lazy { PublicLinks(api) }
    val thumbs by lazy { Thumbs(this, content, { account.session?.cacheKey }) }
    val transfers by lazy { Transfers(this) }
    val notifier by lazy { Notifier(this) }

    private val _ready = MutableStateFlow(false)

    /** True after the settings and the session are read. The UI and the splash screen wait for it. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    override fun onCreate() {
        if (BuildConfig.DEBUG) enableStrictMode()
        super.onCreate()
        prefs = Prefs({ filesDir }, scope)
        // The Keystore, the session file and the HTTP objects start here, never on the main thread.
        scope.launch(Dispatchers.IO) {
            prefs.load()
            account.load()
            // A logout that a process death stopped: finish it before the UI shows (review 74).
            if (account.session == null && java.io.File(noBackupFilesDir, WIPE_MARK).exists()) withContext(Dispatchers.Main) { wipe() }
            _ready.value = true
            // The first screen does not wait for the offline index (review 103).
            offline.load()
            // Camera upload follows the session. Only when no job waits: a schedule() would stop the job that
            // started this process (review 3).
            account.state.map { it is AccountState.LoggedIn }.distinctUntilChanged().collect { inside ->
                if (inside) CameraJob.ensure(this@NuboApp, prefs.value.camera)
            }
        }
    }

    /** Removes every local trace of the account: caches, thumbnails, offline files, queued transfers. */
    private suspend fun wipe() {
        // A mark until the wipe ends: the next start finishes a wipe that a process death stopped. A logout makes
        // it before it deletes the session (review 74).
        val pending = java.io.File(noBackupFilesDir, WIPE_MARK)
        withContext(Dispatchers.IO) { pending.createNewFile() }
        transfers.cancelAll()
        FileProvider.clear()
        CameraJob.cancel(this)
        drive.clear()
        Viewers.clear()
        LinkSessions.clear()
        withContext(Dispatchers.IO) {
            thumbs.clear()
            offline.clear()
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            java.io.File(noBackupFilesDir, CameraJob.MARK_FILE).delete()
        }
        prefs.edit { it.copy(camera = it.camera.copy(enabled = false, folder = null), folderViews = emptyMap()) }
        withContext(Dispatchers.IO) { pending.delete() }
    }

    /** Debug builds log disk and network work on the main thread, leaks and unsafe intents. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }
}

val Context.app: NuboApp get() = applicationContext as NuboApp

/** In noBackupFilesDir while a logout wipe runs (review 74). */
private const val WIPE_MARK = "wipe-pending"
