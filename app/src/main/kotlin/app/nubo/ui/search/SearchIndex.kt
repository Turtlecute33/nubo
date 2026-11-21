package app.nubo.ui.search

import androidx.compose.runtime.Immutable
import app.nubo.drive.Item
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.text.Normalizer

/** A search result and the path of its folder ("Drive › Photos › 2026"). */
@Immutable
class Hit(val item: Item, val path: String)

/**
 * The names of one drive tree, folded once (review 101), and its folders by UUID for the result paths
 * (review 88). Make it and search it off the main thread: both stop soon after a cancel, so a new key press
 * does not wait for the old search.
 */
internal class SearchIndex private constructor(val items: List<Item>, private val names: Array<String>) {
    private val folders: Map<String, Item> = items.filter { it.folder }.associateBy { it.uuid }

    /** At most [max] items whose name has [query]: names that start with it first, then folders, then by name. */
    suspend fun find(query: String, max: Int): List<Hit> {
        val q = fold(query.trim())
        if (q.isEmpty()) return emptyList()
        val found = ArrayList<Int>()
        for (i in names.indices) {
            if (i % CHECK_EVERY == 0) currentCoroutineContext().ensureActive()
            if (names[i].contains(q)) found += i
        }
        currentCoroutineContext().ensureActive()
        return found
            .sortedWith(compareBy<Int>({ !names[it].startsWith(q) }, { !items[it].folder }, { names[it] }))
            .take(max)
            .map { Hit(items[it], path(items[it].parent)) }
    }

    /** "Drive" and the folder names down to [parent]; only the last [PATH_PARTS] when the path is deep. */
    private fun path(parent: String): String {
        val parts = ArrayList<String>()
        var uuid = parent
        // The depth limit also stops a loop in bad server data.
        while (parts.size < MAX_DEPTH) {
            val folder = folders[uuid] ?: break
            parts += folder.name
            uuid = folder.parent
        }
        parts.reverse()
        val shown = if (parts.size > PATH_PARTS) listOf("…") + parts.takeLast(PATH_PARTS) else listOf("Drive") + parts
        return shown.joinToString(" › ")
    }

    companion object {
        private val MARKS = Regex("\\p{Mn}+")
        private const val CHECK_EVERY = 512
        private const val MAX_DEPTH = 64
        private const val PATH_PARTS = 3

        suspend fun of(items: List<Item>): SearchIndex {
            val names = Array(items.size) { i ->
                if (i % CHECK_EVERY == 0) currentCoroutineContext().ensureActive()
                fold(items[i].name)
            }
            return SearchIndex(items, names)
        }

        /** The case- and accent-insensitive form of a name. ASCII has no accents: no NFD and no regex (review P-04). */
        fun fold(s: String): String =
            if (s.all { it < '\u0080' }) s.lowercase() else MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()
    }
}
