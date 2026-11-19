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
    val drive by lazy { Drive(api, account, offline, scope) }
    val uploader by lazy { Uploader(drive) }
    val links by lazy { PublicLinks(api) }
    val thumbs by lazy { Thumbs(this, content, { account.session?.cacheKey }) }
    val transfers by lazy { Transfers(this) }
    val notifier by lazy { Notifier(this) }

    override fun onCreate() {
        if (BuildConfig.DEBUG) enableStrictMode()
        super.onCreate()
        prefs = Prefs(filesDir, scope)
        scope.launch(Dispatchers.IO) {
            prefs.load()
            account.load()
            offline.load()
        }
        // Camera upload follows the session: it starts after a login and at each start with a saved session.
        scope.launch {
            account.state.map { it is AccountState.LoggedIn }.distinctUntilChanged().collect { inside ->
                if (inside) CameraJob.schedule(this@FilenzaApp, prefs.value.camera)
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
