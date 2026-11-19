package app.nubo.ui.search

import app.nubo.drive.Item
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Search order, accent folding and result paths, with a made-up tree. */
class SearchIndexTest {
    private fun folder(uuid: String, parent: String, name: String) = Item(uuid = uuid, parent = parent, name = name, folder = true)
    private fun file(uuid: String, parent: String, name: String) = Item(uuid = uuid, parent = parent, name = name, folder = false)

    private val tree = listOf(
        folder("a", "root", "Holidays"),
        folder("b", "a", "Été 2026"),
        folder("c", "b", "Beach"),
        folder("d", "c", "Day one"),
        file("1", "root", "summer été.txt"),
        file("2", "b", "Été photo.jpg"),
        file("3", "d", "ete-deep.png"),
        folder("4", "root", "Été plans"),
        file("5", "a", "notes.md"),
    )

    @Test
    fun findsWithoutCaseOrAccentsInOrder() = runTest {
        val hits = SearchIndex.of(tree).find("  ETE ", 10)
        // Names that start with the text first, folders before files, then by name.
        assertEquals(listOf("b", "4", "2", "3", "1"), hits.map { it.item.uuid })
        assertEquals(2, SearchIndex.of(tree).find("ete", 2).size)
        assertTrue(SearchIndex.of(tree).find("   ", 10).isEmpty())
    }

    @Test
    fun resultsShowTheFolderPath() = runTest {
        val paths = SearchIndex.of(tree).find("e", 20).associate { it.item.uuid to it.path }
        assertEquals("Drive", paths["1"])
        assertEquals("Drive › Holidays", paths["b"])
        assertEquals("Drive › Holidays › Été 2026", paths["2"])
        // Deep paths keep the last three folders.
        assertEquals("… › Été 2026 › Beach › Day one", paths["3"])
    }

    @Test
    fun aLoopInTheTreeEnds() = runTest {
        val loop = listOf(folder("x", "y", "X"), folder("y", "x", "Y"), file("f", "x", "file.txt"))
        val hit = SearchIndex.of(loop).find("file", 5).single()
        assertTrue(hit.path.startsWith("… › "))
    }
}
