package app.filenza.ui

import app.filenza.transfer.Transfer
import app.filenza.ui.nav.Navigator
import app.filenza.ui.nav.Route
import app.filenza.ui.transfers.failedText
import app.filenza.util.Debounce
import app.filenza.util.Format
import app.filenza.util.Memo
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Screen rules with no Android: the read memo, the double-tap guard, the failure text, back stacks, dates. */
class UiRulesTest {
    @Test
    fun memoKeepsValuesForTheTimeLimitOnly() {
        val memo = Memo<String, Int>(ttlMs = 1_000, max = 2)
        memo.put("a", 1, now = 10_000)
        assertEquals(1, memo.get("a", now = 10_999))
        assertNull(memo.get("a", now = 11_000))
        assertNull(memo.get("a", now = 9_000)) // A clock that goes back does not keep a value for ever.
        memo.put("a", 1, now = 20_000)
        memo.put("b", 2, now = 20_000)
        memo.put("c", 3, now = 20_000)
        assertNull(memo.get("a", now = 20_001)) // The oldest goes first.
        assertEquals(3, memo.get("c", now = 20_001))
    }

    @Test
    fun debounceLetsOneTapThrough() {
        val gate = Debounce(gapMs = 800)
        assertTrue(gate.pass(now = 0))
        assertFalse(gate.pass(now = 300))
        assertTrue(gate.pass(now = 800))
        assertFalse(gate.pass(now = 1_000))
    }

    @Test
    fun failureText() {
        assertEquals("1 upload failed", failedText(listOf(Transfer.Kind.UPLOAD)))
        assertEquals("2 downloads failed", failedText(listOf(Transfer.Kind.DOWNLOAD, Transfer.Kind.DOWNLOAD)))
        assertEquals("2 offline copies failed", failedText(listOf(Transfer.Kind.OFFLINE, Transfer.Kind.OFFLINE)))
        assertEquals("3 transfers failed", failedText(listOf(Transfer.Kind.UPLOAD, Transfer.Kind.DOWNLOAD, Transfer.Kind.UPLOAD)))
    }

    @Test
    fun aViewerDoesNotOpenOverAViewer() {
        val nav = Navigator()
        nav.go(Route.Viewer(session = 1, index = 0))
        nav.go(Route.Viewer(session = 2, index = 0)) // The second tap of a double tap.
        assertEquals(listOf(Route.Home, Route.Viewer(1, 0)), nav.backStack)
        nav.go(Route.Transfers)
        nav.go(Route.Transfers)
        assertEquals(listOf(Route.Home, Route.Viewer(1, 0), Route.Transfers), nav.backStack)
    }

    @Test
    fun datesUseTheSameTextAsANewFormatter() {
        val ms = 1_700_000_000_000L // DateUtils is a stub here: "Today" and "Yesterday" do not show.
        assertEquals("", Format.date(0))
        val before = Locale.getDefault()
        try {
            for (locale in listOf(Locale.UK, Locale.GERMANY)) {
                Locale.setDefault(locale)
                assertEquals(DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(ms)), Format.date(ms))
                assertEquals(DateFormat.getDateInstance(DateFormat.LONG, locale).format(Date(ms)), Format.day(ms))
            }
        } finally {
            Locale.setDefault(before)
        }
    }
}
