package app.filenza.ui.links

import androidx.compose.runtime.Composable
import app.filenza.drive.Links
import app.filenza.ui.Icons
import app.filenza.ui.components.EmptyState

/** STUB: opens a public Filen link (file or folder), asks for its password, and shows the content. */
@Composable
fun PublicLinkScreen(link: Links.Parsed, onClose: () -> Unit) {
    EmptyState(Icons.Link, "Public link")
}
