package app.filenza.ui.nav

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel

/** The four tabs. Each tab has its own back stack, so a switch keeps the place in each tab. */
enum class Tab(val root: Route) { HOME(Route.Home), DRIVE(Route.Drive), PHOTOS(Route.Photos), ME(Route.Me) }

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

    /** Files that another app shared: the app asks for a folder, then uploads them. */
    var pendingUploads by mutableStateOf<List<android.net.Uri>>(emptyList())

    /** A Filen link that another app shared or opened: the app shows it. */
    var pendingLink by mutableStateOf<app.filenza.drive.Links.Parsed?>(null)

    fun stack(of: Tab): List<Route> = stacks.getValue(of)

    val current: Route get() = backStack.last()

    /** Opens [route] in the current tab. A route that is on top already does not open twice. */
    fun go(route: Route) {
        val stack = stacks.getValue(tab)
        if (stack.last() != route) stack += route
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
