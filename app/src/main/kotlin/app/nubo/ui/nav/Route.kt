package app.nubo.ui.nav

import app.nubo.drive.Links
import app.nubo.drive.Listing

/** Screens. Each tab keeps its own back stack in [Navigator]. */
sealed interface Route {
    /** Routes that hide the tab bar and draw behind the system bars. */
    sealed interface FullScreen : Route

    data object Home : Route
    data object Drive : Route
    data object Photos : Route
    data object Me : Route

    /**
     * A folder of the own drive, a folder shared with me, or a folder in a public link. [toString] (the key of
     * the saved screen state) leaves out the [title], so that no folder name goes into the saved state.
     */
    data class Folder(val listing: Listing, val title: String) : Route {
        override fun toString() = "Folder($listing)"
    }

    data object Search : Route
    data object Notes : Route

    /** A note editor. Only the ID: no title goes into the saved state. */
    data class Note(val uuid: String) : Route
    data object Transfers : Route
    data object Settings : Route
    data object CameraSettings : Route

    /** Opens a public Filen link. [link] holds the key: [toString] leaves it out, so no saved state gets it. */
    data class PublicLink(val link: Links.Parsed) : Route {
        override fun toString() = "PublicLink(${link.uuid})"
    }

    /** The viewer for the files of [session] (held by [app.nubo.ui.viewer.Viewers]), from [index]. */
    data class Viewer(val session: Long, val index: Int) : FullScreen
}
