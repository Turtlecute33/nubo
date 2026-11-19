package app.filenza.util

import android.os.SystemClock

/**
 * Values with the time of their read, in memory only. A screen that shows again uses a value that is younger
 * than [ttlMs] and does not read it again (review 52). At most [max] values; the oldest goes first.
 */
class Memo<K : Any, V : Any>(private val ttlMs: Long, private val max: Int) {
    private class Entry<V>(val value: V, val at: Long)

    private val map = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>) = size > max
    }

    /** The value of [key] when it is younger than the time limit, else null. */
    @Synchronized
    fun get(key: K, now: Long = SystemClock.elapsedRealtime()): V? = map[key]?.takeIf { now - it.at in 0 until ttlMs }?.value

    @Synchronized
    fun put(key: K, value: V, now: Long = SystemClock.elapsedRealtime()) {
        map[key] = Entry(value, now)
    }
}
