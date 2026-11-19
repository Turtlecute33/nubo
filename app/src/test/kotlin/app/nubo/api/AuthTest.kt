package app.nubo.api

import app.nubo.FakeTransport
import app.nubo.FakeTransport.Companion.fail
import app.nubo.FakeTransport.Companion.ok
import app.nubo.crypto.MasterKey
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The v2 login against a scripted server, with the known-answer derivation of the Rust SDK. */
class AuthTest {
    private val kat = javaClass.classLoader!!.getResourceAsStream("filen-kat.txt")!!.bufferedReader().readLines()
        .filter { '=' in it && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private val salt = "filen-fixed-salt-0123456789abcdef"
    private val mk = "c387243a52f7a94cbb5850b90b89acd56dd0a4bbd4c0fd14f10c9b3e06a07dac"
    private val loginPassword =
        "ae2319242cf549bc15e965d9142cc66ce9981a0914971fb5726a27158ef4ffb6185589dd665824853c140d757eeff197d2c589df8a16787c40f876c5a08f7709"

    private fun server(twoFactor: Boolean): FakeTransport {
        val key = MasterKey(mk.encodeToByteArray())
        return FakeTransport { r ->
            when (r.path) {
                "/v3/auth/info" -> ok(JSONObject().put("authVersion", 2).put("salt", salt))
                "/v3/login" -> when {
                    r.json.getString("password") != loginPassword -> fail("email_or_password_wrong")
                    twoFactor && r.json.getString("twoFactorCode") == "XXXXXX" -> fail("enter_2fa")
                    twoFactor && r.json.getString("twoFactorCode") != "123456" -> fail("wrong_2fa")
                    else -> ok(JSONObject().put("apiKey", "api-key-1"))
                }
                "/v3/user/masterKeys" -> {
                    // The client sends its key encrypted with itself; the server answers with the whole list.
                    assertEquals(mk, key.decrypt(r.json.getString("masterKeys")))
                    ok(JSONObject().put("keys", key.encrypt("${kat["FIRST_KEY"]}|$mk")))
                }
                "/v3/user/keyPair/info" -> ok(
                    JSONObject().put("publicKey", kat["KEY_A_SPKI"]).put("privateKey", key.encrypt(kat.getValue("KEY_A_PKCS8"))),
                )
                "/v3/user/baseFolder" -> ok(JSONObject().put("uuid", "root-uuid"))
                "/v3/user/info" -> ok(JSONObject().put("id", 42).put("email", "user@example.org"))
                else -> fail("invalid_endpoint", 404)
            }
        }
    }

    @Test
    fun v2LoginSendsOnlyTheDerivedPassword() = runTest {
        val fake = server(twoFactor = false)
        val session = Auth(Api(fake)).login(" user@example.org ", "password", null)
        assertEquals("api-key-1", session.apiKey)
        assertEquals("root-uuid", session.baseFolder)
        assertEquals(42, session.userId)
        assertEquals(2, session.keys.masterKeys.size)
        assertEquals(mk, session.keys.masterKeys[0].raw.decodeToString())
        // The account key list decrypts old metadata (written with FIRST_KEY).
        assertEquals("""{"name":"a","size":1}""", session.keys.decryptMeta(kat.getValue("V2_META_FIRST_KEY")))
        val login = fake.requests.first { it.path == "/v3/login" }
        assertEquals("user@example.org", login.json.getString("email"))
        assertEquals("XXXXXX", login.json.getString("twoFactorCode"))
        // No request carries the password itself.
        assertTrue(fake.requests.none { it.body?.decodeToString()?.contains("\"password\"") == true && it.json.optString("password") == "password" })
        // Unauthenticated calls carry no API key; later ones carry the new key.
        assertNull(fake.requests.first { it.path == "/v3/auth/info" }.headers["Authorization"])
        assertEquals("Bearer api-key-1", fake.requests.first { it.path == "/v3/user/baseFolder" }.headers["Authorization"])
    }

    @Test
    fun twoFactorCodeIsAskedThenSent() = runTest {
        val fake = server(twoFactor = true)
        val e = assertFailsWith<ApiException> { Auth(Api(fake)).login("user@example.org", "password", null) }
        assertTrue(e.needs2fa)
        val wrong = assertFailsWith<ApiException> { Auth(Api(fake)).login("user@example.org", "password", "000000") }
        assertEquals("wrong_2fa", wrong.code)
        val session = Auth(Api(fake)).login("user@example.org", "password", "123 456")
        assertEquals("api-key-1", session.apiKey)
    }

    @Test
    fun wrongPasswordIsAnApiError() = runTest {
        val e = assertFailsWith<ApiException> { Auth(Api(server(false))).login("user@example.org", "nope", null) }
        assertEquals("email_or_password_wrong", e.code)
        assertEquals("The email or the password is wrong", e.userText)
    }
}
