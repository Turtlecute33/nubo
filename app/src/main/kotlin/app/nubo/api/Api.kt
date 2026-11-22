package app.nubo.api

import app.nubo.crypto.Bytes
import app.nubo.crypto.hex
import android.util.JsonReader
import android.util.JsonToken
import android.util.MalformedJsonException
import app.nubo.net.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONException
import org.json.JSONObject
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder

/**
 * A failed API call. [code] is the server's error code ("enter_2fa", "api_key_not_found", …). The server text
 * is never shown: it can name items. DESIGN §8.
 */
class ApiException(val code: String, val http: Int) : IOException("Filen error $code ($http)") {
    /** Only the server's own code counts: a 401 from a proxy or a captive portal must not delete local data. */
    val sessionEnded: Boolean get() = code in SESSION_CODES
    val needs2fa: Boolean get() = code == "enter_2fa"

    /** A busy or failing server, not a refusal of the request: a later try can work (review 64). */
    val transient: Boolean get() = http >= 500 || http == 429 || code == "rate_limited"

    val userText: String
        get() = when {
            code == "enter_2fa" -> "Enter the code from your authenticator app"
            code == "wrong_2fa" || code == "invalid_2fa" -> "The two-factor code is wrong"
            code == "email_or_password_wrong" || code == "invalid_params" && http == 400 -> "The email or the password is wrong"
            code == "account_not_activated" || code == "email_not_activated" -> "Activate the account from the email that Filen sent"
            code == "account_locked" || code == "too_many_requests" || code == "rate_limited" || http == 429 -> "Too many tries. Wait a few minutes."
            code == "storage_limit_reached" || code == "max_storage_reached" -> "Your Filen storage is full"
            code == "file_not_found" || code == "folder_not_found" || http == 404 -> "The item is not there any more"
            code == "parent_folder_not_found" -> "The folder is not there any more"
            code == "link_password_wrong" || code == "wrong_password" -> "The link password is wrong"
            code == "subscription_needed" -> "Public links need a paid Filen plan"
            sessionEnded -> "The session ended. Log in again."
            http >= 500 -> "Filen is not available now. Try again later."
            else -> "Filen refused the request ($code)"
        }

    private companion object {
        /** Only the code that the research shows (F-30, F-83). An unknown code must not log out and delete offline files (review 71). */
        val SESSION_CODES = setOf("api_key_not_found")
    }
}

/**
 * The Filen API (research F-API): JSON over HTTPS to the gateway, chunk upload to ingest, chunk download from
 * egest. Every answer is `{status, message, code, data}`. [key] is the API key of the session, null before login.
 */
class Api(private val transport: Transport, @Volatile var key: String? = null) {
    /** Chunk requests of the whole app at a time, as in the Filen TS SDK (research F-87). */
    private val uploads = Semaphore(MAX_UPLOADS)
    private val downloads = Semaphore(MAX_DOWNLOADS)

    /** The same transport with another API key: a login checks a new key before the app uses it. */
    fun withKey(key: String) = Api(transport, key)

    /** POST with no API key: the calls of public-link visitors (research F-30). */
    suspend fun postAnon(path: String, body: JSONObject): JSONObject =
        retrying { parse(transport.send("POST", GATEWAY + path, mapOf("Content-Type" to "application/json"), body.toString().encodeToByteArray())) }
            .optJSONObject("data") ?: JSONObject()

    /** POST a JSON body to the gateway and return `data` (an empty object when there is none). */
    suspend fun post(path: String, body: JSONObject = JSONObject(), retry: Boolean = true): JSONObject =
        call("POST", GATEWAY + path, body.toString().encodeToByteArray(), retry)

    suspend fun get(path: String): JSONObject = call("GET", GATEWAY + path, null)

    /** Like [get], for an answer whose `data` is an array (notes, chats, contacts). */
    suspend fun getArray(path: String): org.json.JSONArray = callRaw("GET", GATEWAY + path, null).optJSONArray("data") ?: org.json.JSONArray()

    /** Like [post], for an answer whose `data` is an array. */
    suspend fun postArray(path: String, body: JSONObject = JSONObject()): org.json.JSONArray =
        callRaw("POST", GATEWAY + path, body.toString().encodeToByteArray()).optJSONArray("data") ?: org.json.JSONArray()

    private suspend fun call(method: String, url: String, body: ByteArray?, retry: Boolean = true): JSONObject =
        callRaw(method, url, body, retry).optJSONObject("data") ?: JSONObject()

    /**
     * POST for an answer too large to hold (the whole drive tree, review R-65): [read] gets a reader at the `data`
     * object while the answer comes in, and keeps only what it needs. No size limit.
     */
    suspend fun <T> postLarge(path: String, body: JSONObject, read: (JsonReader) -> T): T = retrying {
        transport.stream("POST", GATEWAY + path, headers(), body.toString().encodeToByteArray()) { code, input -> envelope(code, input, read) }
    }

    private suspend fun callRaw(method: String, url: String, body: ByteArray?, retry: Boolean = true): JSONObject {
        val headers = headers()
        return if (retry) retrying { parse(transport.send(method, url, headers, body)) } else parse(transport.send(method, url, headers, body))
    }

    private fun headers() = buildMap {
        put("Content-Type", "application/json")
        key?.let { put("Authorization", "Bearer $it") }
    }

    /**
     * Uploads one encrypted chunk. The server checks the SHA-512 of the body. Returns the region and bucket
     * that the server chose for the file.
     */
    suspend fun uploadChunk(uuid: String, index: Int, parent: String, uploadKey: String, data: ByteArray): Pair<String, String> {
        val q = "uuid=${enc(uuid)}&index=$index&parent=${enc(parent)}&uploadKey=${enc(uploadKey)}&hash=${Bytes.sha512(data).hex()}"
        // HttpEngine refuses a request body with no Content-Type (IllegalArgumentException at start).
        val headers = mapOf(
            "Authorization" to "Bearer ${key ?: throw ApiException("api_key_not_found", 401)}",
            "Content-Type" to "application/octet-stream",
        )
        val o = uploads.withPermit {
            retrying { attempt -> parse(transport.send("POST", "${host(INGESTS, uuid, index, attempt)}/v3/upload?$q", headers, data)) }
        }.optJSONObject("data") ?: JSONObject()
        return o.optText("region") to o.optText("bucket")
    }

    /** Downloads one encrypted chunk (at most 1 MiB + nonce + tag). No API key: the chunk is useless without the file key. */
    suspend fun downloadChunk(region: String, bucket: String, uuid: String, index: Int): ByteArray = downloads.withPermit {
        retrying { attempt ->
            // 2 MiB: a v1 chunk can be base64 text, about 1.4 MB for 1 MiB of data (research F-54, F-56).
            val r = transport.send("GET", "${host(EGESTS, uuid, index, attempt)}/${enc(region)}/${enc(bucket)}/${enc(uuid)}/$index", maxBytes = 2 * CHUNK)
            if (r.code != 200) throw ApiException("chunk_${r.code}", r.code)
            r.body
        }
    }

    private fun parse(r: Transport.Response): JSONObject {
        val o = try {
            JSONObject(r.text)
        } catch (_: JSONException) {
            throw ApiException(if (r.code >= 500) "server_error" else "bad_answer", r.code)
        }
        if (!o.optBoolean("status")) throw ApiException(o.optText("code").ifEmpty { "error" }, r.code)
        return o
    }

    /**
     * The only retry of the app (review 45): network errors and 5xx/429 get two more tries with a short wait; API
     * errors and a too large answer (review 65) do not.
     */
    private suspend fun <T> retrying(block: suspend (attempt: Int) -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block(attempt)
            } catch (e: IOException) {
                val again = e !is Transport.TooLarge && (e !is ApiException || e.http >= 500 || e.http == 429)
                if (!again || ++attempt > 2) throw e
                delay(700L * attempt * attempt)
            }
        }
    }

    companion object {
        const val GATEWAY = "https://gateway.filen.io"
        const val CHUNK = 1024 * 1024
        const val MAX_UPLOADS = 16
        const val MAX_DOWNLOADS = 32

        /**
         * The upload and download hosts of Filen (research F-13, F-14). The first one is the main host. Each domain has
         * its own certificate, so each host gets its own connection: with one host, all chunks share one HTTP/2
         * connection (and the API calls too: same addresses, same certificate).
         */
        val INGESTS = listOf("filen.io", "filen.net", "filen-1.net", "filen-2.net", "filen-3.net", "filen-4.net", "filen-5.net", "filen-6.net")
            .map { "https://ingest.$it" }
        val EGESTS = INGESTS.map { it.replace("://ingest.", "://egest.") }

        /** The host of a chunk: spread by file and chunk. A retry goes to the main host, if a network blocks a domain. */
        internal fun host(hosts: List<String>, uuid: String, index: Int, attempt: Int): String =
            if (attempt > 0) hosts[0] else hosts[Math.floorMod(uuid.hashCode() + index, hosts.size)]

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

        /**
         * Reads the envelope `{status, code, data}` of [postLarge] in one pass. Filen sends `status` first; then the
         * `data` of a refused call is not read. Bad JSON is "bad_answer" (or "server_error" for a 5xx), as in [parse].
         */
        internal fun <T> envelope(code: Int, input: InputStream, read: (JsonReader) -> T): T {
            var status: Boolean? = null
            var error = ""
            var data: T? = null
            try {
                val r = JsonReader(input.reader())
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "status" -> status = when (r.peek()) {
                            JsonToken.BOOLEAN -> r.nextBoolean()
                            JsonToken.STRING -> r.nextString() == "true"
                            else -> false.also { r.skipValue() }
                        }
                        "code" -> error = if (r.peek() == JsonToken.STRING) r.nextString() else "".also { r.skipValue() }
                        "data" -> if (status != false && r.peek() == JsonToken.BEGIN_OBJECT) data = read(r) else r.skipValue()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
            } catch (e: Exception) {
                // A network error in the stream keeps its type (a retry can help); anything else, also JSON that
                // ends too soon, is a bad answer. A cancel interrupts a [read] that waits for its decode workers.
                if (e is InterruptedException) throw e
                if (e is IOException && e !is MalformedJsonException && e !is EOFException) throw e
                throw ApiException(if (code >= 500) "server_error" else "bad_answer", code)
            }
            if (status != true) throw ApiException(error.ifEmpty { "error" }, code)
            return data ?: throw ApiException("bad_answer", code)
        }
    }
}

/** The object at [this] reader as a [JSONObject] of its plain values; nested values are skipped. */
fun JsonReader.nextObject(): JSONObject {
    val o = JSONObject()
    beginObject()
    while (hasNext()) {
        val name = nextName()
        when (peek()) {
            JsonToken.STRING -> o.put(name, nextString())
            // Not a whole number: the text (org.json reads it as a number when asked; an infinity cannot fail here).
            JsonToken.NUMBER -> nextString().let { o.put(name, it.toLongOrNull() ?: it) }
            JsonToken.BOOLEAN -> o.put(name, nextBoolean())
            else -> skipValue() // null, or a nested object or array
        }
    }
    endObject()
    return o
}

/** Each object of the array at [this] reader; a null instead of the array is an empty array. */
inline fun JsonReader.eachObject(block: (JSONObject) -> Unit) {
    if (peek() == JsonToken.NULL) return skipValue()
    beginArray()
    while (hasNext()) if (peek() == JsonToken.BEGIN_OBJECT) block(nextObject()) else skipValue()
    endArray()
}

/**
 * Like [JSONObject.optString], but a JSON null gives [fallback]. Android's org.json gives the text "null" for it
 * (review 61). The JVM tests use another org.json, so a test must put [JSONObject.NULL] itself.
 */
fun JSONObject.optText(name: String, fallback: String = ""): String {
    val v: Any? = opt(name)
    return if (v == null || v == JSONObject.NULL) fallback else v.toString()
}
