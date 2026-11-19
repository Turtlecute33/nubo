package app.filenza.drive

import app.filenza.FakeTransport
import app.filenza.api.Api
import app.filenza.crypto.DataKey
import app.filenza.crypto.hex
import app.filenza.net.Transport
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.digests.Blake3Digest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
    fun theLocalCopyComesFirst() = runTest {
        val c = chunks(plain)
        val fake = server(c)
        val out = ByteArrayOutputStream()
        Content(Api(fake), { _, i -> c[i] }).write(item(blake3(plain)), out) {}
        assertContentEquals(plain, out.toByteArray())
        assertTrue(fake.requests.isEmpty())
    }
}
