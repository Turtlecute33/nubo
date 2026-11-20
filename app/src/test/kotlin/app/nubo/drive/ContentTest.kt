package app.nubo.drive

import app.nubo.FakeTransport
import app.nubo.api.Api
import app.nubo.crypto.DataKey
import app.nubo.crypto.hex
import app.nubo.net.Transport
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.digests.Blake3Digest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Downloads: chunk order, the size check and the BLAKE3 check against a scripted egest server. */
class ContentTest {
    private val key = DataKey.newFileKey(2)
    private val plain = ByteArray(Api.CHUNK * 2 + 1234).also { kotlin.random.Random(7).nextBytes(it) }

    private fun chunks(data: ByteArray) = (0 until (data.size + Api.CHUNK - 1) / Api.CHUNK).map { i ->
        val from = i * Api.CHUNK
        key.encryptChunk(data.copyOfRange(from, minOf(data.size, from + Api.CHUNK)))
    }

    private fun blake3(data: ByteArray) = Blake3Digest(256).run {
        update(data, 0, data.size)
        ByteArray(32).also { doFinal(it, 0) }.hex()
    }

    private fun item(hash: String, size: Long = plain.size.toLong()) = Item(
        uuid = "file-uuid", parent = "p", name = "a.bin", folder = false, size = size, key = key.text(2),
        region = "de-1", bucket = "b1", chunks = 3, version = 2, hash = hash,
    )

    private fun server(served: List<ByteArray>) = FakeTransport { r ->
        val index = r.url.substringAfterLast('/').toInt()
        Transport.Response(200, served[index])
    }

    @Test
    fun writesThePlainFileInOrderAndChecksTheHash() = runTest {
        val fake = server(chunks(plain))
        val out = ByteArrayOutputStream()
        Content(Api(fake), { _, _ -> null }).write(item(blake3(plain)), out) {}
        assertContentEquals(plain, out.toByteArray())
        assertTrue(fake.requests.all { it.host == "egest.filen.io" })
        // Downloads carry no API key (research F-30).
        assertTrue(fake.requests.all { it.headers["Authorization"] == null })
        assertNull(fake.requests.firstOrNull { it.method != "GET" })
    }

    @Test
    fun aSwappedChunkFailsTheHashCheck() = runTest {
        val c = chunks(plain)
        // Each chunk alone is valid GCM; only the whole-file hash sees the swap (research F-55).
        val swapped = listOf(c[1], c[0], c[2])
        assertFailsWith<GeneralSecurityException> { Content(Api(server(swapped)), { _, _ -> null }).write(item(blake3(plain)), ByteArrayOutputStream()) {} }
    }

    @Test
    fun aMissingChunkFailsTheSizeCheck() = runTest {
        val c = chunks(plain)
        val short = listOf(c[0], c[1], key.encryptChunk(ByteArray(10)))
        assertFailsWith<IOException> { Content(Api(server(short)), { _, _ -> null }).write(item(""), ByteArrayOutputStream()) {} }
    }

    @Test
    fun aChunkCountThatDoesNotFitTheSizeIsRefusedFirst() = runTest {
        val fake = server(chunks(plain))
        // Review 80: the server says 2 chunks for a file of 3 chunks; nothing downloads.
        assertFailsWith<IOException> { Content(Api(fake), { _, _ -> null }).write(item(blake3(plain)).copy(chunks = 2), ByteArrayOutputStream()) {} }
        assertTrue(fake.requests.isEmpty())
        assertEquals(3, Content.chunkCount(item("")))
        assertEquals(0, Content.chunkCount(item("", size = 0).copy(chunks = 0)))
        assertEquals(1, Content.chunkCount(item("", size = Api.CHUNK.toLong()).copy(chunks = 1)))
    }

    @Test
    fun theLocalCopyComesFirst() = runTest {
        val c = chunks(plain)
        val fake = server(c)
        val out = ByteArrayOutputStream()
        Content(Api(fake), { _, i -> c[i] }).write(item(blake3(plain)), out) {}
        assertContentEquals(plain, out.toByteArray())
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun aNegativeSizeIsRefusedBeforeTheArray() = runTest {
        val fake = server(chunks(plain))
        // -2^31 - 1 passes "size > max", and toInt() makes it Int.MAX_VALUE: a 2 GB array.
        val bad = item("", size = -2_147_483_649L)
        assertFailsWith<IOException> { Content(Api(fake), { _, _ -> null }).bytes(bad, 80_000_000) }
        assertFailsWith<IOException> { Content.chunkCount(bad) }
        assertTrue(fake.requests.isEmpty())
    }
}
