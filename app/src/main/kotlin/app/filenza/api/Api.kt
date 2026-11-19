package app.filenza.api

import app.filenza.crypto.Bytes
import app.filenza.crypto.hex
import app.filenza.net.Transport
import kotlinx.coroutines.delay
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * A failed API call. [code] is the server's error code ("enter_2fa", "api_key_not_found", …). The server text
 * is never shown: it can name items. DESIGN §8.
 */
class ApiException(val code: String, val http: Int) : IOException("Filen error $code ($http)") {
    /** Only the server's own code counts: a 401 from a proxy or a captive portal must not delete local data. */
    val sessionEnded: Boolean get() = code in SESSION_CODES
    val needs2fa: Boolean get() = code == "enter_2fa"

    val userText: String
        get() = when {
            code == "enter_2fa" -> "Enter the code from your authenticator app"
            code == "wrong_2fa" || code == "invalid_2fa" -> "The two-factor code is wrong"
            code == "email_or_password_wrong" || code == "invalid_params" && http == 400 -> "The email or the password is wrong"
            code == "account_not_activated" || code == "email_not_activated" -> "Activate the account from the email that Filen sent"
            code == "account_locked" || code == "too_many_requests" || http == 429 -> "Too many tries. Wait a few minutes."
            code == "storage_limit_reached" || code == "max_storage_reached" -> "Your Filen storage is full"
            code == "file_not_found" || code == "folder_not_found" || http == 404 -> "The item is not there any more"
            code == "link_password_wrong" || code == "wrong_password" -> "The link password is wrong"
            sessionEnded -> "The session ended. Log in again."
            http >= 500 -> "Filen is not available now. Try again later."
            else -> "Filen refused the request ($code)"
        }

    private companion object {
        val SESSION_CODES = setOf("api_key_not_found", "invalid_api_key", "unauthorized")
    }
}

/**
 * The Filen API (research F-API): JSON over HTTPS to the gateway, chunk upload to ingest, chunk download from
 * egest. Every answer is `{status, message, code, data}`. [key] is the API key of the session, null before login.
 */
class Api(private val transport: Transport, @Volatile var key: String? = null) {
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

    /** Like [post], for an answer whose `data` is an array. */
    suspend fun postArray(path: String, body: JSONObject = JSONObject()): org.json.JSONArray =
        callRaw("POST", GATEWAY + path, body.toString().encodeToByteArray()).optJSONArray("data") ?: org.json.JSONArray()

    private suspend fun call(method: String, url: String, body: ByteArray?, retry: Boolean = true): JSONObject =
        callRaw(method, url, body, retry).optJSONObject("data") ?: JSONObject()

    private suspend fun callRaw(method: String, url: String, body: ByteArray?, retry: Boolean = true): JSONObject {
        val headers = buildMap {
            put("Content-Type", "application/json")
            key?.let { put("Authorization", "Bearer $it") }
        }
        return if (retry) retrying { parse(transport.send(method, url, headers, body)) } else parse(transport.send(method, url, headers, body))
    }

    /**
     * Uploads one encrypted chunk. The server checks the SHA-512 of the body. Returns the region and bucket
     * that the server chose for the file.
     */
    suspend fun uploadChunk(uuid: String, index: Int, parent: String, uploadKey: String, data: ByteArray): Pair<String, String> {
        val q = "uuid=${enc(uuid)}&index=$index&parent=${enc(parent)}&uploadKey=${enc(uploadKey)}&hash=${Bytes.sha512(data).hex()}"
        val headers = mapOf("Authorization" to "Bearer ${key ?: throw ApiException("api_key_not_found", 401)}")
        val o = retrying { parse(transport.send("POST", "$INGEST/v3/upload?$q", headers, data)) }.optJSONObject("data") ?: JSONObject()
        return o.optString("region") to o.optString("bucket")
    }

    /** Downloads one encrypted chunk (at most 1 MiB + nonce + tag). No API key: the chunk is useless without the file key. */
    suspend fun downloadChunk(region: String, bucket: String, uuid: String, index: Int): ByteArray = retrying {
        // 2 MiB: a v1 chunk can be base64 text, about 1.4 MB for 1 MiB of data (research F-54, F-56).
        val r = transport.send("GET", "$EGEST/${enc(region)}/${enc(bucket)}/${enc(uuid)}/$index", maxBytes = 2 * CHUNK)
        if (r.code != 200) throw ApiException("chunk_${r.code}", r.code)
        r.body
    }

    private fun parse(r: Transport.Response): JSONObject {
        val o = try {
            JSONObject(r.text)
        } catch (_: JSONException) {
            throw ApiException(if (r.code >= 500) "server_error" else "bad_answer", r.code)
        }
        if (!o.optBoolean("status")) throw ApiException(o.optString("code").ifEmpty { "error" }, r.code)
        return o
    }

    /** Network errors and 5xx/429 get two more tries with a short wait; API errors do not. */
    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                val transient = e !is ApiException || e.http >= 500 || e.http == 429
                if (!transient || ++attempt > 2) throw e
                delay(700L * attempt * attempt)
            }
        }
    }

    companion object {
        const val GATEWAY = "https://gateway.filen.io"
        const val INGEST = "https://ingest.filen.io"
        const val EGEST = "https://egest.filen.io"
        const val CHUNK = 1024 * 1024

        private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    }
}
