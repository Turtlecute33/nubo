package app.filenza.drive

import app.filenza.crypto.DataKey
import app.filenza.crypto.MasterKey
import app.filenza.crypto.hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Link URLs (both forms) and link password hashes (research F-76, F-77). */
class LinksTest {
    private val uuid = "0f8c1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b"
    private val key = "abcdefghijklmnopqrstuvwxyz012-_9"
    private val hexKey = key.encodeToByteArray().hex()

    private val kat: Map<String, String> = javaClass.classLoader!!.getResourceAsStream("filen-kat.txt")!!.bufferedReader()
        .readLines().filter { '=' in it && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun newFormFileAndFolder() {
        val file = assertNotNull(Links.parse("https://app.filen.io/f/$uuid#$hexKey"))
        assertFalse(file.folder)
        assertEquals(uuid, file.uuid)
        assertEquals(key, file.key)
        val folder = assertNotNull(Links.parse("https://app.filen.io/d/$uuid#$hexKey"))
        assertTrue(folder.folder)
        assertEquals(key, folder.key)
    }

    @Test
    fun oldFormSwapsTheLetters() {
        // Old web app: d = download (a file), f = folder; the key after an encoded '#'.
        val file = assertNotNull(Links.parse("https://app.filen.io/#/d/$uuid%23$key"))
        assertFalse(file.folder)
        assertEquals(key, file.key)
        val folder = assertNotNull(Links.parse("https://drive.filen.io/#/f/$uuid%23$hexKey"))
        assertTrue(folder.folder)
        assertEquals(key, folder.key)
        // A browser can decode the %23 into a second '#'.
        val decoded = assertNotNull(Links.parse("https://app.filen.io/#/d/$uuid#$key"))
        assertEquals(key, decoded.key)
        assertEquals(uuid, decoded.uuid)
    }

    @Test
    fun uppercaseUuidIsNormalised() {
        assertEquals(uuid, Links.parse("https://app.filen.io/f/${uuid.uppercase()}#$hexKey")?.uuid)
    }

    @Test
    fun refusesWhatIsNotAFilenLinkWithAKey() {
        assertNull(Links.parse("http://app.filen.io/f/$uuid#$hexKey"))
        assertNull(Links.parse("https://app.filen.io.evil.example/f/$uuid#$hexKey"))
        assertNull(Links.parse("https://evil.example/f/$uuid#$hexKey"))
        assertNull(Links.parse("https://app.filen.io/f/not-a-uuid#$hexKey"))
        assertNull(Links.parse("https://app.filen.io/f/$uuid"))
        assertNull(Links.parse("https://app.filen.io/f/$uuid#abc"))
        assertNull(Links.parse("https://app.filen.io/x/$uuid#$hexKey"))
        // Hex that does not give printable key text.
        assertNull(Links.parse("https://app.filen.io/f/$uuid#${"00".repeat(32)}"))
        assertNull(Links.parse("not a link"))
    }

    @Test
    fun findsTheLinkInText() {
        val found = assertNotNull(Links.find("Here: https://app.filen.io/d/$uuid#$hexKey thanks"))
        assertTrue(found.folder)
        assertNull(Links.find("https://example.com and https://app.filen.io/f/$uuid"))
    }

    @Test
    fun urlRoundTrip() {
        for (folder in listOf(false, true)) {
            val parsed = assertNotNull(Links.parse(Links.url(folder, uuid, key)))
            assertEquals(folder, parsed.folder)
            assertEquals(uuid, parsed.uuid)
            assertEquals(key, parsed.key)
        }
    }

    @Test
    fun toStringHasNoKey() {
        val parsed = assertNotNull(Links.parse("https://app.filen.io/f/$uuid#$hexKey"))
        assertFalse(key in parsed.toString())
        assertFalse(hexKey in parsed.toString())
        assertFalse(key in FolderLink(uuid, key, "empty").toString())
    }

    @Test
    fun metaKeyFollowsTheKeyForm() {
        assertIs<MasterKey>(Links.metaKey(key))
        assertIs<DataKey>(Links.metaKey("ab".repeat(32)))
        val k = Links.metaKey(key)
        assertEquals("""{"name":"x"}""", k.decrypt(k.encrypt("""{"name":"x"}""")))
    }

    @Test
    fun passwordHashes() {
        assertEquals("empty", Links.hashPassword(null, "anything"))
        assertEquals("empty", Links.hashPassword("", "anything"))
        // No salt: the old SHA-1 over the hex of SHA-512 (filen-rs known-answer value).
        assertEquals("4e253244a9e480cc2224913923a626ef28d0a5b9", Links.hashPassword("password", ""))
        // 32-character salt: PBKDF2-SHA512, 200 000 rounds (filen-rs crypto/connect.rs test vector).
        assertEquals(
            "ce273dada8b49ee004a002a9c46c44e789c644e8ad32446f031e73abadc83e257b571103d314088a87c21dbdc094c96e94f01bbb453110c6cec22b5644797376",
            Links.hashPassword("password", "x__X9T9953af53zes9u-4pIhAqhxOjBx"),
        )
        // 512 hex characters: Argon2id, the same 64 bytes as the v3 login derivation.
        assertEquals(kat.getValue("V3_DERIVED"), Links.hashPassword(kat.getValue("V3_PASSWORD"), kat.getValue("V3_SALT")))
    }

    @Test
    fun newSaltIsTheCurrentForm() {
        val salt = Links.newSalt()
        assertEquals(512, salt.length)
        assertTrue(salt.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
