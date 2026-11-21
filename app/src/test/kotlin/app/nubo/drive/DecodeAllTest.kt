package app.nubo.drive

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The decode on all cores keeps the order and leaves out entries that do not decode (review P-01). */
class DecodeAllTest {
    @Test
    fun keepsTheOrderAndDropsBadEntries() = runTest {
        val list = (0 until 2000).toList()
        val items = Decode.all(list) { i -> if (i % 7 == 0) null else Item(uuid = "$i", parent = "", name = "n$i", folder = false) }
        assertEquals(list.filter { it % 7 != 0 }.map { "$it" }, items.map { it.uuid })
        assertEquals(emptyList(), Decode.all(emptyList<Int>()) { null })
    }
}
