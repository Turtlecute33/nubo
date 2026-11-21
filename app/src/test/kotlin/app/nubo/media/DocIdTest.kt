package app.nubo.media

import app.nubo.drive.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The document IDs and the mode rule of the file picker entry. */
class DocIdTest {
    private val uuid = "3c1a5e2b-8f4d-4e6a-9b7c-0d2e1f3a4b5c"

    @Test
    fun idsKeepTheKind() {
        val file = DocId.parse(DocId.of(Item(uuid, "p", "a.txt", folder = false)))!!
        assertFalse(file.folder)
        assertEquals(uuid, file.uuid)
        val folder = DocId.parse(DocId.folder(uuid))!!
        assertTrue(folder.folder)
        assertEquals(uuid, folder.uuid)
    }

    @Test
    fun badIdsAreRefused() {
        assertNull(DocId.parse(uuid))
        assertNull(DocId.parse("x:$uuid"))
        assertNull(DocId.parse("f:"))
        assertNull(DocId.parse("f:../etc"))
        assertNull(DocId.parse("d:recents"))
    }

    @Test
    fun onlyPlainReadOpens() {
        assertTrue(DocId.readOnly("r"))
        listOf("w", "rw", "wt", "wa", "rwt", "").forEach { assertFalse(DocId.readOnly(it), it) }
    }
}
