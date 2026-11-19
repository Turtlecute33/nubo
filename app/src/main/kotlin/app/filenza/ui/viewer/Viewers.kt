package app.filenza.ui.viewer

import app.filenza.drive.Item
import app.filenza.util.FileType

/**
 * The file lists that open viewers swipe through, by session ID. In memory only: after process death the app
 * opens on Home (DESIGN §4), so a lost session is never asked for. Logout clears all sessions (review 68).
 */
object Viewers {
    /** The files of one viewer and the page data that loaded while it shows (review 51). */
    internal class Session(val items: List<Item>) {
        val cache = PageCache(CACHE_BYTES)
    }

    private var next = 1L
    private val sessions = object : LinkedHashMap<Long, Session>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Session>) = size > 8
    }

    /**
     * A new session with the files of [items] that the app can show, and the index of [start] in it. One cheap
     * pass with no allocation for each item, because the caller is on the main thread (review 101).
     */
    @Synchronized
    fun open(items: List<Item>, start: Item): Pair<Long, Int> {
        val files = ArrayList<Item>(items.size)
        var index = -1
        for (item in items) {
            if (!viewable(item)) continue
            if (index < 0 && item.uuid == start.uuid) index = files.size
            files += item
        }
        if (files.isEmpty()) files += start
        val id = next++
        sessions[id] = Session(files)
        return id to index.coerceAtLeast(0)
    }

    @Synchronized
    internal fun get(id: Long): Session? = sessions[id]

    /** The viewer of [id] closed: its page data goes. The file list stays for Back to the viewer. */
    @Synchronized
    fun release(id: Long) {
        sessions[id]?.cache?.clear()
    }

    /** At logout: no names, file keys or page data stay in memory (review 68). */
    @Synchronized
    fun clear() {
        sessions.values.forEach { it.cache.clear() }
        sessions.clear()
    }

    /** Files that an in-app viewer shows; other files open in another app. */
    fun viewable(item: Item): Boolean = item.type in VIEWABLE

    private val VIEWABLE = setOf(FileType.IMAGE, FileType.VIDEO, FileType.AUDIO, FileType.PDF, FileType.TEXT, FileType.CODE)

    /** About the current image and one more (an estimate: a 12 MP photo decodes to 20 to 40 MB here). */
    private const val CACHE_BYTES = 64L * 1024 * 1024
}
