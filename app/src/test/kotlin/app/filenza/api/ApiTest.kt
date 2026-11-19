package app.filenza.api

import app.filenza.FakeTransport
import app.filenza.FakeTransport.Companion.ok
import app.filenza.net.Transport
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The retry rules, the JSON null rule and the error codes of the API envelope. */
class ApiTest {
    @Test
    fun aLoginIsSentOnlyOnce() = runTest {
        // Review 45: the transport does not retry, and post(retry = false) does not either.
        val fake = FakeTransport { throw IOException("Connection lost") }
        assertFailsWith<IOException> { Api(fake).post("/v3/login", JSONObject(), retry = false) }
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun networkErrorsGetTwoMoreTries() = runTest {
        var calls = 0
        val fake = FakeTransport { if (++calls < 3) throw IOException("Connection lost") else ok(JSONObject().put("uuid", "x")) }
        assertEquals("x", Api(fake).get("/v3/user/baseFolder").getString("uuid"))
        assertEquals(3, fake.requests.size)
    }

    @Test
    fun aTooLargeAnswerIsNotDownloadedAgain() = runTest {
        // Review 65: a new try gets the same answer.
        val fake = FakeTransport { throw Transport.TooLarge() }
        assertFailsWith<Transport.TooLarge> { Api(fake).post("/v3/dir/download", JSONObject()) }
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun optTextGivesNoNullText() {
        // Review 61: Android's org.json gives "null" here; the helper must not, whatever org.json the tests use.
        val o = JSONObject().put("mime", JSONObject.NULL).put("size", 12).put("region", "de-1")
        assertEquals("", o.optText("mime"))
        assertEquals("never", o.optText("mime", "never"))
        assertEquals("", o.optText("missing"))
        assertEquals("12", o.optText("size"))
        assertEquals("de-1", o.optText("region"))
    }

    @Test
    fun onlyTheKnownCodeEndsTheSession() {
        // Review 71: only the code of the research (F-30, F-83) logs out and deletes the offline files.
        assertTrue(ApiException("api_key_not_found", 200).sessionEnded)
        assertFalse(ApiException("unauthorized", 401).sessionEnded)
        assertFalse(ApiException("invalid_api_key", 200).sessionEnded)
    }

    @Test
    fun errorTexts() {
        assertEquals("Public links need a paid Filen plan", ApiException("subscription_needed", 200).userText)
        assertEquals("Too many tries. Wait a few minutes.", ApiException("rate_limited", 200).userText)
        assertTrue(ApiException("internal_error", 503).transient)
        assertTrue(ApiException("rate_limited", 200).transient)
        assertFalse(ApiException("wrong_password", 200).transient)
    }
}
