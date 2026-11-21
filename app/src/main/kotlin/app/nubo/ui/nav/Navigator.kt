package app.nubo.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel

/**
 * The tabs. Each tab has its own back stack, so a switch keeps the place in each tab. Notes and Chats show only
 * when the user turns them on in the settings.
 */
enum class Tab(val root: Route) {
    HOME(Route.Home), DRIVE(Route.Drive), PHOTOS(Route.Photos), NOTES(Route.Notes), CHATS(Route.Chats), ME(Route.Me),
}

/**
 * The back stacks. A ViewModel, so it survives configuration changes; after process death the app opens on
 * Home. Back on a tab root goes to Home, and Back on Home closes the app.
 */
class Navigator : ViewModel() {
    private val stacks: Map<Tab, SnapshotStateList<Route>> = Tab.entries.associateWith { mutableStateListOf(it.root) }

    var tab by mutableStateOf(Tab.HOME)
        private set

    /** True after Back on Home: the activity should finish. */
    var finished by mutableStateOf(false)
        private set

    val backStack: List<Route> get() = stacks.getValue(tab)

    /**
     * Files that other apps shared, one list for each share: the app asks for a folder for each in turn, then
     * uploads the files. A second share waits for its own question: it never changes the files of an open one.
     */
    val pendingUploads = mutableStateListOf<List<android.net.Uri>>()

    /** A Filen link that another app shared or opened: the app shows it. */
    var pendingLink by mutableStateOf<app.nubo.drive.Links.Parsed?>(null)

    fun stack(of: Tab): List<Route> = stacks.getValue(of)

    val current: Route get() = backStack.last()

    /**
     * Opens [route] in the current tab. A route that is on top already does not open twice, and a viewer does
     * not open over a viewer: the second tap of a double tap comes while the first viewer opens (review 89).
     */
    fun go(route: Route) {
        val stack = stacks.getValue(tab)
        val top = stack.last()
        if (top != route && !(route is Route.Viewer && top is Route.Viewer)) stack += route
    }

    /** Opens [route] above the root of [tab]. */
    fun goIn(tab: Tab, route: Route) {
        val stack = stacks.getValue(tab)
        stack.removeRange(1, stack.size)
        if (route != tab.root) stack += route
        this.tab = tab
    }

    fun back() {
        val stack = stacks.getValue(tab)
        when {
            stack.size > 1 -> stack.removeAt(stack.lastIndex)
            tab != Tab.HOME -> tab = Tab.HOME
            else -> finished = true
        }
    }

    /** A tap on the current tab goes back to its root. */
    fun select(tab: Tab) {
        if (tab == this.tab) stacks.getValue(tab).let { it.removeRange(1, it.size) } else this.tab = tab
    }

    /** After a logout: every stack back to its root, on Home. */
    fun reset() {
        stacks.forEach { (t, s) -> s.removeRange(0, s.size); s += t.root }
        tab = Tab.HOME
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("No navigator") }
