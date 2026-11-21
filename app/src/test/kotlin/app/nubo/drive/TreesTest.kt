package app.nubo.drive

import app.nubo.crypto.DataKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The tree cache: the disk copy, its checks, the part of the root read, and the memory rules (review P-03). */
class TreesTest {
    private val dir = Files.createTempDirectory("trees").toFile()
    private val key = DataKey(ByteArray(32) { it.toByte() })

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
    }

    private fun folder(uuid: String, parent: String) = Item(uuid = uuid, parent = parent, name = "Folder $uuid", folder = true, created = 5, color = "blue")

    private fun file(uuid: String, parent: String) = Item(
        uuid = uuid, parent = parent, name = "Été $uuid.jpg", folder = false, size = 12_345, mime = "image/jpeg", modified = 1, created = 2,
        uploaded = 3, favorite = uuid.endsWith("7"), key = "k".repeat(32), region = "de-1", bucket = "filen-1", chunks = 1, version = 2,
        hash = "ab".repeat(32),
    )

    /** root › A › B, root › C; 5,000 files (three records on the disk). */
    private val items = listOf(folder("A", "root"), folder("B", "A"), folder("C", "root")) +
        (0 until 5000).map { file("f$it", listOf("root", "A", "B", "C")[it % 4]) }

    private suspend fun put(trees: Trees, tree: Tree, root: String = "root") {
        val scope = CoroutineScope(Job())
        trees.put(tree, root, scope)
        scope.coroutineContext.job.children.toList().joinAll()
    }

    @Test
    fun theDiskCopyReadsBackOld() = runTest {
        put(Trees(dir, { key }), Tree("root", items, 100))
        val back = Trees(dir, { key }).get("root", "root")!!
        assertEquals(items, back.items)
        assertEquals(0, back.at)
    }

    @Test
    fun aFolderGetsItsPartOfTheRootRead() = runTest {
        put(Trees(dir, { key }), Tree("root", items, 100))
        val part = Trees(dir, { key }).get("A", "root")!!
        assertEquals(items.filter { it.parent == "A" || it.parent == "B" }, part.items)
        assertNull(Trees(dir, { key }).get("gone", "root"))
    }

    @Test
    fun anotherKeyOrADamagedFileGivesNothingAndIsDeleted() = runTest {
        put(Trees(dir, { key }), Tree("root", items, 100))
        assertNull(Trees(dir, { DataKey(ByteArray(32)) }).get("root", "root"))
        assertTrue(dir.listFiles().isNullOrEmpty())

        put(Trees(dir, { key }), Tree("root", items, 100))
        val file = dir.listFiles()!!.single()
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        file.writeBytes(bytes)
        assertNull(Trees(dir, { key }).get("root", "root"))

        put(Trees(dir, { key }), Tree("root", items, 100))
        val whole = dir.listFiles()!!.single()
        whole.writeBytes(whole.readBytes().copyOf(whole.length().toInt() - 100))
        assertNull(Trees(dir, { key }).get("root", "root"))
    }

    @Test
    fun theNewestReadWins() = runTest {
        val trees = Trees(dir, { key })
        val root = Tree("root", items, 10)
        put(trees, root)
        assertSame(root, trees.get("root", "root"))
        // A newer own read of A wins over the part of the older root read.
        val a = Tree("A", items.filter { it.parent == "A" || it.parent == "B" }, 20)
        put(trees, a)
        assertSame(a, trees.get("A", "root"))
        // A newer root read replaces it, and its part serves A.
        val changed = items.dropLast(1)
        put(trees, Tree("root", changed, 30))
        assertEquals(30, trees.get("A", "root")!!.at)
        val file = dir.listFiles()!!.single()
        // A read with no change does not write the file again (each write has a new file ID).
        val bytes = file.readBytes()
        put(trees, Tree("root", changed.toList(), 40))
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test
    fun aLoopInTheFoldersEnds() {
        val tree = Tree("root", listOf(folder("X", "Y"), folder("Y", "X"), file("f", "X")), 1)
        assertEquals(listOf("Y", "f"), tree.sub("X")!!.items.map { it.uuid })
    }
}
