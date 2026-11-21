package app.nubo.util

import android.text.format.DateUtils
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Text for sizes, speeds and dates. */
object Format {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    /** 1000-based, like the Filen apps: "1.2 MB". */
    fun size(bytes: Long): String {
        if (bytes < 1000) return "$bytes B"
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1000 && i < units.lastIndex) {
            v /= 1000
            i++
        }
        return if (v >= 100) "${v.toInt()} ${units[i]}" else String.format(Locale.ROOT, "%.1f %s", v, units[i])
    }

    fun speed(bytesPerSecond: Long): String = size(bytesPerSecond) + "/s"

    /** "Today", "Yesterday" or a medium date, for a time in ms. */
    fun date(ms: Long): String = when {
        ms <= 0 -> ""
        DateUtils.isToday(ms) -> "Today"
        DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> format(ms, DateFormat.MEDIUM)
    }

    fun dateTime(ms: Long): String = if (ms <= 0) "" else format(ms, DateFormat.MEDIUM, DateFormat.SHORT)

    /** The time for today, else the date and the time: chat messages. */
    fun time(ms: Long): String = when {
        ms <= 0 -> ""
        DateUtils.isToday(ms) -> format(ms, -1, DateFormat.SHORT)
        else -> dateTime(ms)
    }

    /** A long date for headers in the photo timeline. */
    fun day(ms: Long): String = when {
        DateUtils.isToday(ms) -> "Today"
        DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> format(ms, DateFormat.LONG)
    }

    private val formats = HashMap<Int, DateFormat>()
    private var formatsLocale: Locale? = null

    /**
     * One formatter for each style, not one for each row (review 101). A new locale makes them again.
     * DateFormat is not thread-safe, and the photo timeline formats off the main thread: synchronized.
     */
    @Synchronized
    private fun format(ms: Long, date: Int, time: Int = -1): String {
        val locale = Locale.getDefault()
        if (locale != formatsLocale) {
            formats.clear()
            formatsLocale = locale
        }
        val f = formats.getOrPut(date * 10 + time + 1) {
            when {
                time < 0 -> DateFormat.getDateInstance(date, locale)
                date < 0 -> DateFormat.getTimeInstance(time, locale)
                else -> DateFormat.getDateTimeInstance(date, time, locale)
            }
        }
        f.timeZone = TimeZone.getDefault()
        return f.format(Date(ms))
    }
}
