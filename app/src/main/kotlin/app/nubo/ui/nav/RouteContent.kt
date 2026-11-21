package app.nubo.ui.nav

import androidx.compose.runtime.Composable
import app.nubo.drive.Listing
import app.nubo.ui.chat.ChatScreen
import app.nubo.ui.chat.ChatsScreen
import app.nubo.ui.chat.NewChatScreen
import app.nubo.ui.drive.FolderScreen
import app.nubo.ui.home.HomeScreen
import app.nubo.ui.links.PublicLinkScreen
import app.nubo.ui.me.MeScreen
import app.nubo.ui.notes.NoteScreen
import app.nubo.ui.notes.NotesScreen
import app.nubo.ui.photos.CameraSettingsScreen
import app.nubo.ui.photos.PhotosScreen
import app.nubo.ui.search.SearchScreen
import app.nubo.ui.settings.SettingsScreen
import app.nubo.ui.transfers.TransfersScreen
import app.nubo.ui.viewer.ViewerScreen
import androidx.compose.ui.platform.LocalContext
import app.nubo.app

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
        Route.Notes -> NotesScreen()
        is Route.Note -> NoteScreen(route.uuid)
        Route.Chats -> ChatsScreen()
        is Route.Chat -> ChatScreen(route.uuid)
        Route.NewChat -> NewChatScreen()
        Route.Transfers -> TransfersScreen()
        Route.Settings -> SettingsScreen()
        Route.CameraSettings -> CameraSettingsScreen()
        is Route.PublicLink -> PublicLinkScreen(route.link, onClose = nav::back)
        is Route.Viewer -> ViewerScreen(route.session, route.index)
    }
}
