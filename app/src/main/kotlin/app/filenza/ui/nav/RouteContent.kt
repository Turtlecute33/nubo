package app.filenza.ui.nav

import androidx.compose.runtime.Composable
import app.filenza.drive.Listing
import app.filenza.ui.drive.FolderScreen
import app.filenza.ui.home.HomeScreen
import app.filenza.ui.links.PublicLinkScreen
import app.filenza.ui.me.MeScreen
import app.filenza.ui.photos.CameraSettingsScreen
import app.filenza.ui.photos.PhotosScreen
import app.filenza.ui.search.SearchScreen
import app.filenza.ui.settings.SettingsScreen
import app.filenza.ui.transfers.TransfersScreen
import app.filenza.ui.viewer.ViewerScreen
import androidx.compose.ui.platform.LocalContext
import app.filenza.app

/** The screen of each route. */
@Composable
fun RouteContent(route: Route) {
    val nav = LocalNav.current
    when (route) {
        Route.Home -> HomeScreen()
        Route.Drive -> {
            val root = LocalContext.current.app.account.session?.baseFolder ?: return
            FolderScreen(Listing.Dir(root), "Drive", isRoot = true)
        }
        Route.Photos -> PhotosScreen()
        Route.Me -> MeScreen()
        is Route.Folder -> FolderScreen(route.listing, route.title)
        Route.Search -> SearchScreen()
        Route.Transfers -> TransfersScreen()
        Route.Settings -> SettingsScreen()
        Route.CameraSettings -> CameraSettingsScreen()
        is Route.PublicLink -> PublicLinkScreen(route.link, onClose = nav::back)
        is Route.Viewer -> ViewerScreen(route.session, route.index)
    }
}
