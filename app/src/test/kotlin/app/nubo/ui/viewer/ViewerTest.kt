package app.nubo.ui.viewer

import app.nubo.drive.Item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The page cache, the text blocks, the image decode size and the viewer sessions. Synthetic data only. */
class ViewerTest {
    @Test
    fun cacheDropsTheOldestOverTheBudget() {
        val cache = PageCache(budget = 100)
        cache.put("a", "A", 40)
        cache.put("b", "B", 40)
        assertEquals("A", cache.get<String>("a")) // "a" is now the newest
        cache.put("c", "C", 40)
        assertNull(cache.get<String>("b"))
        assertEquals("A", cache.get<String>("a"))
        assertEquals("C", cache.get<String>("c"))
        cache.put("big", "X", 101)
        assertNull(cache.get<String>("big"))
        assertEquals("C", cache.get<String>("c"))
        cache.put("c", "C2", 100) // a new value for a key replaces the old size
        assertEquals("C2", cache.get<String>("c"))
        assertNull(cache.get<String>("a"))
        cache.clear()
        assertNull(cache.get<String>("c"))
    }

    @Test
    fun textBlocksCutByLinesAndByLength() {
        val lines = (1..130).joinToString("\n") { "line $it" }
        val blocks = textBlocks(lines)
        assertEquals(3, blocks.size)
        assertEquals((1..60).joinToString("\n") { "line $it" }, blocks[0])
        assertEquals("line 121", blocks[2].lines().first())
        assertEquals(lines, blocks.joinToString("\n"))

        // One minified line of 10k characters: no block is longer than the limit.
        val minified = "{\"k\":1}".repeat(1500)
        val parts = textBlocks(minified)
        assertTrue(parts.all { it.length <= 4_000 })
        assertEquals(minified, parts.joinToString(""))

        // A cut does not split a surrogate pair.
        val emoji = "a" + "😀".repeat(10)
        val pieces = textBlocks(emoji, chars = 4)
        assertTrue(pieces.none { it.isNotEmpty() && Character.isHighSurrogate(it.last()) })
        assertEquals(emoji, pieces.joinToString(""))

        assertEquals(listOf("a", "b"), textBlocks("a\r\nb", lines = 1))
        assertEquals(listOf(""), textBlocks(""))
    }

    @Test
    fun imagesDecodeAtTwiceTheFittedSize() {
        // A 50 MP landscape photo on a 1344 x 2992 portrait screen: it fits the width, so 2 x 1344 wide.
        val scale = decodeScale(8160, 6120, 1344, 2992)
        assertEquals(2688, (8160 * scale).toInt(), absolute = 1)
        // A small image is never made larger.
        assertEquals(1f, decodeScale(800, 600, 1344, 2992))
        // A window that is not measured yet counts as 1024 square.
        assertEquals(2048, (4096 * decodeScale(4096, 4096, 0, 0)).toInt(), absolute = 1)
        // A very large window: at most about 16.8 million pixels.
        val s = decodeScale(20_000, 20_000, 10_000, 10_000)
        assertTrue(20_000f * s * 20_000f * s <= 16_777_216f * 1.01f)
    }

    @Test
    fun sessionsKeepOnlyViewableFiles() {
        val folder = Item(uuid = "f", parent = "p", name = "Folder", folder = true)
        val photo = Item(uuid = "1", parent = "p", name = "beach.jpg", folder = false)
        val zip = Item(uuid = "2", parent = "p", name = "old.zip", folder = false)
        val notes = Item(uuid = "3", parent = "p", name = "notes.txt", folder = false)
        val (id, index) = Viewers.open(listOf(folder, photo, zip, notes), notes)
        assertEquals(listOf("1", "3"), Viewers.get(id)?.items?.map { it.uuid })
        assertEquals(1, index)
        Viewers.clear()
        assertNull(Viewers.get(id))
    }

    private fun assertEquals(expected: Int, actual: Int, absolute: Int) =
        assertTrue(kotlin.math.abs(expected - actual) <= absolute, "expected $expected, got $actual")
}
