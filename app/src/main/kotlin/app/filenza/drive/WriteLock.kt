package app.filenza.drive

import app.filenza.api.Api
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * The cooperative "drive-write" lock of all Filen clients (research F-57): it stops races with the desktop
 * sync of the same account. Taken once for any number of writes of this app at the same time, refreshed
 * every 15 s (the server lease is 60 s), and released when the last write ends.
 */
class WriteLock(private val api: Api, private val scope: CoroutineScope) {
    private val id = UUID.randomUUID().toString()
    private val mutex = Mutex()
    private var holders = 0
    private var refresher: Job? = null

    suspend fun <T> write(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            withContext(NonCancellable) { release() }
        }
    }

    private suspend fun acquire(): Unit = mutex.withLock {
        if (holders++ > 0) return@withLock
        try {
            repeat(TRIES) {
                if (call("acquire").optBoolean("acquired")) {
                    refresher = scope.launch {
                        while (isActive) {
                            delay(REFRESH_MS)
                            runCatching { call("refresh") }
                        }
                    }
                    return@withLock
                }
                delay(1_000)
            }
            throw IOException("Another Filen app is writing now. Try again in a minute.")
        } catch (e: Throwable) {
            holders--
            throw e
        }
    }

    private suspend fun release(): Unit = mutex.withLock {
        if (--holders > 0) return@withLock
        refresher?.cancel()
        refresher = null
        // In the mutex, so that a next acquire cannot reach the server before this release (review 19).
        runCatching { call("release") }
    }

    private suspend fun call(type: String): JSONObject =
        api.post("/v3/user/lock", JSONObject().put("uuid", id).put("type", type).put("resource", "drive-write"))

    private companion object {
        const val TRIES = 30
        const val REFRESH_MS = 15_000L
    }
}
