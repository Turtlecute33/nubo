package app.nubo.ui.viewer

/**
 * The loaded data of the pages of one viewer session (decoded images, text blocks), so that a swipe back or a
 * rotation does not download and decode again (review 51). Memory only. The least recently used values go
 * first when the total size is more than [budget] bytes; a value larger than [budget] is not kept.
 */
internal class PageCache(private val budget: Long) {
    private class Entry(val value: Any, val size: Long)

    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var used = 0L

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T> get(key: String): T? = entries[key]?.value as T?

    @Synchronized
    fun put(key: String, value: Any, size: Long) {
        entries.remove(key)?.let { used -= it.size }
        if (size > budget) return
        entries[key] = Entry(value, size)
        used += size
        val oldest = entries.entries.iterator()
        while (used > budget) {
            val e = oldest.next()
            used -= e.value.size
            oldest.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        used = 0
    }
}
