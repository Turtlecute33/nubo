package app.filenza.net

import android.content.Context
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.NetworkException
import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import android.os.SystemClock
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One HTTP exchange. [Http] is the implementation; tests use a scripted fake. */
interface Transport {
    /**
     * Sends one request and reads the whole answer (at most [maxBytes]). Any status is a result; only network
     * errors throw (as [IOException]). No redirects: the Filen hosts do not use them, so a redirect is an error.
     */
    suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        maxBytes: Int = MAX_BODY,
    ): Response

    class Response(val code: Int, val body: ByteArray) {
        val text: String get() = body.decodeToString()
    }

    companion object {
        const val MAX_BODY = 32 shl 20
    }
}

/**
 * HTTPS through the platform's HttpEngine (Cronet in the Connectivity module): HTTP/3 and HTTP/2 multiplexing
 * with no library and no native code in the APK. One engine for the whole app, no HTTP cache, no cookies, a
 * User-Agent with no device data (DESIGN §8). Only https URLs.
 *
 * After [IDLE_MS] with no request, the next request gets a new engine: a VPN or NAT can drop the connections of
 * a frozen app, and the old engine then waits about 10 s for each dead HTTP/2 connection.
 */
class Http(private val context: Context) : Transport {
    private val lock = Any()
    private var engine: HttpEngine = build()
    private var active = 0
    private var lastUse = SystemClock.elapsedRealtime()
    private val executor = Dispatchers.IO.asExecutor()

    private fun build(): HttpEngine = HttpEngine.Builder(context)
        // Only connection data (HTTP/3 support of hosts), so that QUIC works from the first request after a restart.
        .setStoragePath(File(context.noBackupFilesDir, "http").apply { mkdirs() }.path)
        .setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_DISABLED, 0)
        .setEnableQuic(true)
        .setEnableHttp2(true)
        .setEnableBrotli(true)
        .setUserAgent(USER_AGENT)
        .build()

    private fun acquire(): HttpEngine = synchronized(lock) {
        val now = SystemClock.elapsedRealtime()
        // shutdown() refuses while a request runs: then keep the old engine (two engines cannot share the storage path).
        if (active == 0 && now - lastUse > IDLE_MS && runCatching { engine.shutdown() }.isSuccess) engine = build()
        active++
        lastUse = now
        engine
    }

    private fun release() = synchronized(lock) {
        active--
        lastUse = SystemClock.elapsedRealtime()
    }

    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Int): Transport.Response {
        requireHttps(url)
        var attempt = 0
        while (true) {
            try {
                return withTimeoutOrNull(TIMEOUT_MS) { once(method, url, headers, body, maxBytes) } ?: throw IOException("Connection timed out")
            } catch (e: HttpException) {
                if (e !is NetworkException || !e.isImmediatelyRetryable || ++attempt > 1) throw readable(e)
            }
        }
    }

    private suspend fun once(method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Int): Transport.Response {
        val engine = acquire()
        try {
            return suspendCancellableCoroutine { cont ->
                val callback = Callback(cont, maxBytes)
                val request = engine.newUrlRequestBuilder(url, executor, callback)
                    .setHttpMethod(method)
                    .setCacheDisabled(true)
                    .apply {
                        headers.forEach { (k, v) -> addHeader(k, v) }
                        if (body != null) setUploadDataProvider(BytesUpload(body), executor)
                    }
                    .build()
                cont.invokeOnCancellation { request.cancel() }
                request.start()
            }
        } finally {
            release()
        }
    }

    /** A request body from memory (chunks are at most 1 MiB + 28 bytes). Rewinds for a retry after a redirect or a reset. */
    private class BytesUpload(private val data: ByteArray) : UploadDataProvider() {
        private var pos = 0

        override fun getLength(): Long = data.size.toLong()

        override fun read(sink: UploadDataSink, buffer: ByteBuffer) {
            val n = minOf(buffer.remaining(), data.size - pos)
            buffer.put(data, pos, n)
            pos += n
            sink.onReadSucceeded(false)
        }

        override fun rewind(sink: UploadDataSink) {
            pos = 0
            sink.onRewindSucceeded()
        }
    }

    private class Callback(
        private val cont: CancellableContinuation<Transport.Response>,
        private val maxBytes: Int,
    ) : UrlRequest.Callback {
        private val buffer = ByteBuffer.allocateDirect(BUFFER)
        private val out = ByteArrayOutputStream()
        private var failure: Throwable? = null

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) =
            abort(request, IOException("Unexpected redirect"))

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) = request.read(buffer)

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            byteBuffer.flip()
            if (out.size() + byteBuffer.remaining() > maxBytes) return abort(request, IOException("Answer too large"))
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)
            out.write(bytes)
            byteBuffer.clear()
            request.read(byteBuffer)
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) =
            cont.resume(Transport.Response(info.httpStatusCode, out.toByteArray()))

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) = cont.resumeWithException(error)

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            val f = failure
            if (f != null) cont.resumeWithException(f) else cont.cancel()
        }

        private fun abort(request: UrlRequest, e: Throwable) {
            failure = e
            request.cancel()
        }
    }

    companion object {
        /** The same on every device: no model, OS version or app version. */
        const val USER_AGENT = "Filenza"
        private const val BUFFER = 128 shl 10
        private const val TIMEOUT_MS = 60_000L
        private const val IDLE_MS = 5 * 60_000L

        fun requireHttps(url: String) {
            val uri = runCatching { URI(url) }.getOrNull()
            if (uri?.scheme != "https" || uri.host.isNullOrEmpty()) throw IOException("Only https links are allowed")
        }

        private fun readable(e: HttpException): IOException {
            val text = when ((e as? NetworkException)?.errorCode) {
                NetworkException.ERROR_HOSTNAME_NOT_RESOLVED -> "Server not found"
                NetworkException.ERROR_INTERNET_DISCONNECTED -> "No internet connection"
                NetworkException.ERROR_TIMED_OUT, NetworkException.ERROR_CONNECTION_TIMED_OUT -> "Connection timed out"
                NetworkException.ERROR_CONNECTION_REFUSED -> "Connection refused"
                NetworkException.ERROR_CONNECTION_RESET, NetworkException.ERROR_CONNECTION_CLOSED -> "Connection lost"
                NetworkException.ERROR_NETWORK_CHANGED -> "The network changed"
                NetworkException.ERROR_ADDRESS_UNREACHABLE -> "Server unreachable"
                else -> "Network error"
            }
            return IOException(text, e)
        }
    }
}
