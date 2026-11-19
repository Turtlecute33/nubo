package app.nubo.util

import android.os.SystemClock

/** Lets one tap through in each [gapMs]: a double tap must not open two choosers (review 89). Main thread only. */
class Debounce(private val gapMs: Long = 800) {
    private var last = Long.MIN_VALUE

    fun pass(now: Long = SystemClock.uptimeMillis()): Boolean {
        if (last != Long.MIN_VALUE && now - last in 0 until gapMs) return false
        last = now
        return true
    }
}
