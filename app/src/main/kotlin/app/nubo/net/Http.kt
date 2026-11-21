package app.nubo.net

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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One HTTP exchange. [Http] is the implementation; tests use a scripted fake. */
interface Transport {
    /**
     * Sends one request and reads the whole answer (at most [maxBytes], else [TooLarge]). Any status is a result;
     * only network errors throw (as [IOException]). No redirects: the Filen hosts do not use them, so a redirect is
     * an error. No retry here: `Api` is the only place that retries (review 45).
     */
    suspend fun send(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        maxBytes: Int = MAX_BODY,
    ): Response

    /**
     * Like [send], for an answer too large to hold (the whole drive tree, review R-65): [read] gets the status and
     * the body while it comes in, on an IO thread, with no size limit. The default reads the whole answer first
     * (for the scripted fake of the tests).
     */
    suspend fun <T> stream(method: String, url: String, headers: Map<String, String>, body: ByteArray?, read: (Int, InputStream) -> T): T {
        val r = send(method, url, headers, body, Int.MAX_VALUE)
        return read(r.code, r.body.inputStream())
    }

    class Response(val code: Int, val body: ByteArray) {
        val text: String get() = body.decodeToString()
    }

    /** The answer is larger than the limit. A new try gets the same answer, so it is not a network error (review 65). */
    class TooLarge : IOException("The answer from Filen is too large")

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
    // Built at the first request, off the main thread (it loads the platform network stack and makes a folder).
    private var engine: HttpEngine? = null
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
        var e = engine
        // shutdown() refuses while a request runs: then keep the old engine (two engines cannot share the storage path).
        if (e == null || active == 0 && now - lastUse > IDLE_MS && runCatching { e.shutdown() }.isSuccess) {
            e = build()
            engine = e
        }
        active++
        lastUse = now
        e
    }

    private fun release() = synchronized(lock) {
        active--
        lastUse = SystemClock.elapsedRealtime()
    }

    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Int): Transport.Response {
        requireHttps(url)
        return try {
            // On IO: the first request builds the engine, which reads and writes the disk. Callers such as the
            // login and the public link screen call from the main thread.
            withContext(Dispatchers.IO) { withIdleTimeout { activity -> once(method, url, headers, body, maxBytes, activity) } }
        } catch (e: HttpException) {
            throw readable(e)
        }
    }

    /**
     * The answer as an [InputStream] for [read], on this IO thread. The network side reads ahead up to [AHEAD]
     * buffers while [read] parses, so that the download and the parse overlap (review P-01); a full queue pauses
     * the download. The same idle limit as [send]; a cancel interrupts the blocked read and cancels the request.
     */
    override suspend fun <T> stream(method: String, url: String, headers: Map<String, String>, body: ByteArray?, read: (Int, InputStream) -> T): T {
        requireHttps(url)
        return try {
            withContext(Dispatchers.IO) {
                withIdleTimeout { activity ->
                    val engine = acquire()
                    val pipe = Pipe(activity)
                    try {
                        val request = engine.newUrlRequestBuilder(url, executor, pipe)
                            .setHttpMethod(method)
                            .setCacheDisabled(true)
                            .apply {
                                headers.forEach { (k, v) -> addHeader(k, v) }
                                if (body != null) setUploadDataProvider(BytesUpload(body, activity), executor)
                            }
                            .build()
                        request.start()
                        try {
                            runInterruptible { read(pipe.code(), pipe) }
                        } finally {
                            request.cancel() // nothing when the answer is complete
                        }
                    } finally {
                        release()
                    }
                }
            }
        } catch (e: HttpException) {
            throw readable(e)
        }
    }

    /**
     * Gives the body of a [stream] request to a blocking reader. The network thread starts the next read at once
     * while fewer than [AHEAD] buffers wait; else the reader starts it when the queue is half empty.
     */
    private class Pipe(private val activity: AtomicLong) : InputStream(), UrlRequest.Callback {
        /** Body parts ([ByteArray]), then [End] or the failure ([Throwable]). */
        private val queue = LinkedBlockingQueue<Any>()
        private val started = CountDownLatch(1)
        private val buffer = ByteBuffer.allocateDirect(BUFFER)
        private val lock = Any()

        /** The request while it waits for room in the queue. */
        private var paused: UrlRequest? = null

        @Volatile
        private var status = 0

        @Volatile
        private var failure: Throwable? = null
        private var part = ByteArray(0)
        private var at = 0

        /** The HTTP status; waits for the start of the answer. */
        fun code(): Int {
            started.await()
            if (status == 0) throw failure ?: IOException("No answer")
            return status
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (at == part.size) {
                val next = queue.take()
                if (next === End || next is Throwable) {
                    queue.put(next) // the same answer for a later read
                    if (next is Throwable) throw next
                    return -1
                }
                part = next as ByteArray
                at = 0
                synchronized(lock) {
                    val request = paused
                    if (request != null && queue.size <= AHEAD / 2) {
                        paused = null
                        request.read(buffer)
                    }
                }
            }
            val n = minOf(len, part.size - at)
            System.arraycopy(part, at, b, off, n)
            at += n
            activity.set(SystemClock.elapsedRealtime())
            return n
        }

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            failure = IOException("Unexpected redirect")
            request.cancel()
        }

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            activity.set(SystemClock.elapsedRealtime())
            status = info.httpStatusCode
            started.countDown()
            request.read(buffer)
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            activity.set(SystemClock.elapsedRealtime())
            byteBuffer.flip()
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer.get(bytes)
            byteBuffer.clear()
            if (bytes.isNotEmpty()) queue.put(bytes)
            synchronized(lock) { if (queue.size < AHEAD) request.read(byteBuffer) else paused = request }
        }

        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) = queue.put(End)

        override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) = end(error)

        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) = end(failure ?: IOException("Canceled"))

        private fun end(e: Throwable) {
            queue.put(failure ?: e.also { failure = it })
            started.countDown()
        }

        private object End
    }

    /**
     * Fails when no byte goes up or comes down for [IDLE_TIMEOUT_MS]. A total limit would fail every 1 MiB chunk
     * on a slow link; this one fails only a dead connection.
     */
    private suspend fun <T> withIdleTimeout(block: suspend (AtomicLong) -> T): T = coroutineScope {
        val activity = AtomicLong(SystemClock.elapsedRealtime())
        val watchdog = launch {
            while (true) {
                delay(IDLE_TIMEOUT_MS / 6)
                if (SystemClock.elapsedRealtime() - activity.get() > IDLE_TIMEOUT_MS) throw IOException("Connection timed out")
            }
        }
        try {
            block(activity)
        } finally {
            watchdog.cancel()
        }
    }

    private suspend fun once(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxBytes: Int,
        activity: AtomicLong,
    ): Transport.Response {
        val engine = acquire()
        try {
            return suspendCancellableCoroutine { cont ->
                val callback = Callback(cont, maxBytes, activity)
                val request = engine.newUrlRequestBuilder(url, executor, callback)
                    .setHttpMethod(method)
                    .setCacheDisabled(true)
                    .apply {
                        headers.forEach { (k, v) -> addHeader(k, v) }
                        if (body != null) setUploadDataProvider(BytesUpload(body, activity), executor)
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
    private class BytesUpload(private val data: ByteArray, private val activity: AtomicLong) : UploadDataProvider() {
        private var pos = 0

        override fun getLength(): Long = data.size.toLong()

        override fun read(sink: UploadDataSink, buffer: ByteBuffer) {
            val n = minOf(buffer.remaining(), data.size - pos)
            buffer.put(data, pos, n)
            pos += n
            activity.set(SystemClock.elapsedRealtime())
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
        private val activity: AtomicLong,
    ) : UrlRequest.Callback {
        private val buffer = ByteBuffer.allocateDirect(BUFFER)
        private val out = ByteArrayOutputStream()
        private var failure: Throwable? = null

        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) =
            abort(request, IOException("Unexpected redirect"))

        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            activity.set(SystemClock.elapsedRealtime())
            request.read(buffer)
        }

        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
            activity.set(SystemClock.elapsedRealtime())
            byteBuffer.flip()
            if (out.size() + byteBuffer.remaining() > maxBytes) return abort(request, Transport.TooLarge())
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
        const val USER_AGENT = "nubo"
        private const val BUFFER = 128 shl 10

        /** Buffers that a [stream] reads ahead at most: 4 MiB. */
        private const val AHEAD = 32
        private const val IDLE_TIMEOUT_MS = 60_000L
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
