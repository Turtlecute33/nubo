package app.filenza.ui.viewer

import app.filenza.drive.Item
import app.filenza.util.FileType

/**
 * The file lists that open viewers swipe through, by session ID. In memory only: after process death the app
 * opens on Home (DESIGN §4), so a lost session is never asked for.
 */
object Viewers {
    private var next = 1L
    private val sessions = object : LinkedHashMap<Long, List<Item>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Item>>) = size > 8
    }

    /** A new session with the files of [items] that the app can show, and the index of [start] in it. */
    @Synchronized
    fun open(items: List<Item>, start: Item): Pair<Long, Int> {
        val files = items.filter { !it.folder && viewable(it) }.ifEmpty { listOf(start) }
        val id = next++
        sessions[id] = files
        return id to files.indexOfFirst { it.uuid == start.uuid }.coerceAtLeast(0)
    }

    @Synchronized
    fun get(id: Long): List<Item>? = sessions[id]

    /** Files that an in-app viewer shows; other files open in another app. */
    fun viewable(item: Item): Boolean = item.type in setOf(FileType.IMAGE, FileType.VIDEO, FileType.AUDIO, FileType.PDF, FileType.TEXT, FileType.CODE)
}
