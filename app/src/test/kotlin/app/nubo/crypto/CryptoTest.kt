package app.nubo.crypto

import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The crypto against the known-answer values of the official Rust SDK (test resource filen-kat.txt). */
class CryptoTest {
    private val kat: Map<String, String> = javaClass.classLoader!!.getResourceAsStream("filen-kat.txt")!!.bufferedReader()
        .readLines().filter { '=' in it && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun k(name: String) = kat.getValue(name)
    private val meta = """{"name":"a","size":1}"""
    private fun counting(n: Int) = ByteArray(n) { it.toByte() }

    @Test
    fun v1Metadata() {
        assertEquals(meta, MasterKey(k("FIRST_KEY").encodeToByteArray()).decrypt(k("V1_META_FIRST_KEY")))
    }

    @Test
    fun v2MetadataAndKeyOrder() {
        val first = MasterKey(k("FIRST_KEY").encodeToByteArray())
        val second = MasterKey(k("SECOND_KEY").encodeToByteArray())
        assertEquals(meta, first.decrypt(k("V2_META_FIRST_KEY")))
        assertFailsWith<GeneralSecurityException> { first.decrypt(k("V2_META_SECOND_KEY")) }
        val ring = Keyring(2, listOf(first, second), null, Keyring.privateKey(Bytes.unb64(k("KEY_A_PKCS8"))), k("KEY_A_SPKI"))
        assertEquals(meta, ring.decryptMeta(k("V2_META_SECOND_KEY")))
        assertEquals(meta, ring.decryptMeta(k("V1_META_SECOND_KEY")))
        // The last good key goes first for `002`; the other keys are still tried (review P-02).
        assertEquals(meta, ring.decryptMeta(k("V2_META_FIRST_KEY")))
        assertEquals(meta, ring.decryptMeta(k("V2_META_SECOND_KEY")))
        assertEquals(meta, ring.decryptMeta(k("V1_META_FIRST_KEY")))
    }

    @Test
    fun chunkAssociatedData() {
        val key = DataKey(counting(32))
        val sealed = key.encryptChunk(counting(100), aad = byteArrayOf(1, 2))
        assertContentEquals(counting(100), key.decryptChunk(sealed, byteArrayOf(1, 2)))
        assertFailsWith<GeneralSecurityException> { key.decryptChunk(sealed, byteArrayOf(1, 3)) }
        assertFailsWith<GeneralSecurityException> { key.decryptChunk(sealed) }
        // The same thread decrypts again after a failed tag check.
        assertContentEquals(counting(100), key.decryptChunk(sealed, byteArrayOf(1, 2)))
    }

    @Test
    fun v3Metadata() {
        assertEquals(meta, DataKey(ByteArray(32) { 0x42 }).decrypt(k("V3_META")))
    }

    @Test
    fun metadataRoundTrip() {
        val master = MasterKey("0123456789abcdef0123456789abcdef".encodeToByteArray())
        val text = "Ünïcode ✓ name.txt"
        assertTrue(master.encrypt(text).startsWith("002"))
        assertEquals(text, master.decrypt(master.encrypt(text)))
        val data = DataKey(Bytes.random(32))
        assertTrue(data.encrypt(text).startsWith("003"))
        assertEquals(text, data.decrypt(data.encrypt(text)))
    }

    @Test
    fun fileData() {
        val v2 = DataKey.parse(k("FIRST_KEY"))
        val v3 = DataKey(ByteArray(32) { 0x11 })
        for (n in listOf(1, 17, 33)) {
            assertContentEquals(counting(n), v2.decryptChunk(k("V2_DATA_$n").unhex()))
            assertContentEquals(counting(n), v3.decryptChunk(k("V3_DATA_$n").unhex()))
        }
        val chunk = counting(1000)
        assertContentEquals(chunk, v3.decryptChunk(v3.encryptChunk(chunk)))
        val damaged = v3.encryptChunk(chunk).also { it[40] = (it[40] + 1).toByte() }
        assertFailsWith<GeneralSecurityException> { v3.decryptChunk(damaged) }
    }

    @Test
    fun passwordDerivation() {
        val v2 = Kdf.v2("password", "filen-fixed-salt-0123456789abcdef")
        assertEquals("c387243a52f7a94cbb5850b90b89acd56dd0a4bbd4c0fd14f10c9b3e06a07dac", v2.key.decodeToString())
        assertEquals(
            "ae2319242cf549bc15e965d9142cc66ce9981a0914971fb5726a27158ef4ffb6185589dd665824853c140d757eeff197d2c589df8a16787c40f876c5a08f7709",
            v2.password,
        )
        val v1 = Kdf.v1("password123")
        assertEquals(k("HASH_PASSWORD123"), v1.key.decodeToString())
        assertEquals(
            "7465e95234c0f7fed7608be0039f95b3570dc56cdd825ea61bc103c35828e054e2c063ab054b3341d11efd171c68d58971f34aa630387b50c2ad2cbcdd226dbcd42138444bf07a71f21e00a72a3cf09d3f80855d3fdf447765cd31df70d3bb6a7e2c680359d0ca717681a809129f936c411b88ae114fefe86d39678bb7376e91",
            v1.password,
        )
        val v3 = Kdf.v3(k("V3_PASSWORD"), k("V3_SALT"))
        assertEquals(k("V3_DERIVED").substring(0, 64), v3.key.hex())
        assertEquals(k("V3_DERIVED").substring(64), v3.password)
    }

    @Test
    fun nameHashes() {
        assertEquals("455f3194ec552fbc452397fc4d6676427fe1bb96", Names.v2Hash("docs".encodeToByteArray()))
        val ringA = Keyring(3, emptyList(), DataKey(ByteArray(32)), Keyring.privateKey(Bytes.unb64(k("KEY_A_PKCS8"))), k("KEY_A_SPKI"))
        val ringB = Keyring(3, emptyList(), DataKey(ByteArray(32)), Keyring.privateKey(Bytes.unb64(k("KEY_B_PKCS8"))), k("KEY_B_SPKI"))
        assertEquals(k("KEY_A_HMAC_DOCS"), ringA.hashName("Docs"))
        assertEquals(k("KEY_B_HMAC_DOCS"), ringB.hashName("docs"))
        assertEquals(k("KEY_A_HMAC_EMPTY"), Names.hmac(ringA.hmacKey, ByteArray(0)))
        val ring2 = Keyring(2, listOf(MasterKey("x".encodeToByteArray())), null, ringA.privateKey, k("KEY_A_SPKI"))
        assertEquals("455f3194ec552fbc452397fc4d6676427fe1bb96", ring2.hashName("Docs"))
    }

    @Test
    fun rsa() {
        val ringA = Keyring(3, emptyList(), DataKey(ByteArray(32)), Keyring.privateKey(Bytes.unb64(k("KEY_A_PKCS8"))), k("KEY_A_SPKI"))
        val ringB = Keyring(3, emptyList(), DataKey(ByteArray(32)), Keyring.privateKey(Bytes.unb64(k("KEY_B_PKCS8"))), k("KEY_B_SPKI"))
        assertEquals("filen", ringA.decryptShared(k("RSA_FILEN")))
        assertEquals("filen", ringB.decryptShared(k("RSA_FILEN_KEY_B")))
        assertEquals("hello ✓", ringA.decryptShared(Keyring.encryptFor(k("KEY_A_SPKI"), "hello ✓")))
        // Shared metadata: a real U+FFFD is valid UTF-8 and is not read again as Latin-1 (that is for invalid bytes).
        assertEquals("report \uFFFD.txt", app.nubo.drive.Drive.shared(ringA, Keyring.encryptFor(k("KEY_A_SPKI"), "report \uFFFD.txt")))
    }

    @Test
    fun privateKeyFromServer() {
        val first = MasterKey(k("FIRST_KEY").encodeToByteArray())
        assertEquals(k("KEY_A_PKCS8"), first.decrypt(k("ENCRYPTED_PRIVATE_KEY_A")))
    }

    @Test
    fun keyringSurvivesJson() {
        val ring = Keyring(
            2,
            listOf(MasterKey(k("FIRST_KEY").encodeToByteArray()), MasterKey(k("SECOND_KEY").encodeToByteArray())),
            null,
            Keyring.privateKey(Bytes.unb64(k("KEY_A_PKCS8"))),
            k("KEY_A_SPKI"),
        )
        val back = Keyring.fromJson(org.json.JSONObject(ring.toJson().toString()))
        assertEquals(meta, back.decryptMeta(k("V2_META_SECOND_KEY")))
        assertEquals(ring.hashName("x"), back.hashName("x"))
    }

    @Test
    fun masterListPutsCurrentFirstOnce() {
        val current = MasterKey(k("FIRST_KEY").encodeToByteArray())
        val list = Keyring.masterList(current, k("SECOND_KEY") + "|" + k("FIRST_KEY"))
        assertEquals(2, list.size)
        assertTrue(list[0] === current)
    }

    @Test
    fun fileKeyText() {
        val v2 = DataKey.newFileKey(2)
        assertEquals(32, v2.text(2).length)
        assertContentEquals(v2.bytes, DataKey.parse(v2.text(2)).bytes)
        val v3 = DataKey.newFileKey(3)
        assertEquals(64, v3.text(3).length)
        assertContentEquals(v3.bytes, DataKey.parse(v3.text(3)).bytes)
    }
}
