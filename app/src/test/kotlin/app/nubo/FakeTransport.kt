package app.nubo

import app.nubo.net.Transport
import org.json.JSONObject

/** A scripted Filen server: each handler gets the request and gives the answer. Records every request. */
class FakeTransport(private val handler: (Request) -> Transport.Response) : Transport {
    class Request(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray?) {
        val path: String get() = url.substringAfter("://").substringAfter('/').let { "/" + it.substringBefore('?') }
        val json: JSONObject get() = JSONObject(body?.decodeToString() ?: "{}")
        val host: String get() = url.substringAfter("://").substringBefore('/')
    }

    val requests = mutableListOf<Request>()

    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Int): Transport.Response {
        val r = Request(method, url, headers, body)
        synchronized(requests) { requests += r }
        return handler(r)
    }

    companion object {
        fun ok(data: JSONObject = JSONObject()) =
            Transport.Response(200, JSONObject().put("status", true).put("message", "").put("code", "").put("data", data).toString().encodeToByteArray())

        fun fail(code: String, http: Int = 200) =
            Transport.Response(http, JSONObject().put("status", false).put("message", "x").put("code", code).toString().encodeToByteArray())
    }
}
