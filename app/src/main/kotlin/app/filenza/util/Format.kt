package app.filenza.util

import android.text.format.DateUtils
import java.text.DateFormat
import java.util.Date
import java.util.Locale

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
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
    }

    fun dateTime(ms: Long): String =
        if (ms <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    /** A long date for headers in the photo timeline. */
    fun day(ms: Long): String = when {
        DateUtils.isToday(ms) -> "Today"
        DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.LONG).format(Date(ms))
    }
}
