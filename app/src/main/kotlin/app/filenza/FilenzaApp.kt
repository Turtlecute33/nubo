package app.filenza

import android.app.Application
import android.content.Context
import android.os.StrictMode
import app.filenza.api.Api
import app.filenza.data.Account
import app.filenza.data.AccountState
import app.filenza.data.Prefs
import app.filenza.data.SessionStore
import app.filenza.data.Vault
import app.filenza.drive.Content
import app.filenza.drive.Drive
import app.filenza.drive.PublicLinks
import app.filenza.drive.Uploader
import app.filenza.media.FileProvider
import app.filenza.media.Thumbs
import app.filenza.net.Http
import app.filenza.offline.Offline
import app.filenza.transfer.Transfers
import app.filenza.ui.Messenger
import app.filenza.work.CameraJob
import app.filenza.work.Notifier
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
class FilenzaApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val messenger = Messenger()
    lateinit var prefs: Prefs
        private set

    private val vault by lazy { Vault() }
    val http by lazy { Http(this) }
    val api by lazy { Api(http) }
    val account by lazy { Account(api, SessionStore(noBackupFilesDir, vault), ::wipe) }
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
            offline.load()
            _ready.value = true
            // Camera upload follows the session. Only when no job waits: a schedule() would stop the job that
            // started this process (review 3).
            account.state.map { it is AccountState.LoggedIn }.distinctUntilChanged().collect { inside ->
                if (inside) CameraJob.ensure(this@FilenzaApp, prefs.value.camera)
            }
        }
    }

    /** Removes every local trace of the account: caches, thumbnails, offline files, queued transfers. */
    private suspend fun wipe() {
        transfers.cancelAll()
        FileProvider.clear()
        CameraJob.cancel(this)
        drive.clear()
        withContext(Dispatchers.IO) {
            thumbs.clear()
            offline.clear()
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            java.io.File(noBackupFilesDir, CameraJob.MARK_FILE).delete()
        }
        prefs.edit { it.copy(camera = it.camera.copy(enabled = false, folder = null, folderName = ""), folderViews = emptyMap()) }
    }

    /** Debug builds log disk and network work on the main thread, leaks and unsafe intents. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
    }
}

val Context.app: FilenzaApp get() = applicationContext as FilenzaApp
