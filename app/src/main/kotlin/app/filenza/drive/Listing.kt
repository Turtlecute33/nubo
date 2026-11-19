package app.filenza.drive

import androidx.compose.runtime.Immutable

/** What a list screen shows. Keys of the listing cache and of folder routes. */
@Immutable
sealed interface Listing {
    /** A folder of the own drive (the root too). */
    data class Dir(val uuid: String) : Listing

    data object Recents : Listing
    data object Favorites : Listing
    data object Trash : Listing

    /** Own items that have a public link. */
    data object Links : Listing

    /** Items that other users shared with this account. */
    data object SharedIn : Listing

    /** Items that this account shares with other users. */
    data object SharedOut : Listing

    /** Files kept on the phone. */
    data object Offline : Listing

    /** A folder inside an item that another user shared with me. Read only. */
    data class SharedDir(val uuid: String) : Listing

    /** A folder inside a public folder link. [link] holds the key: [toString] leaves it out. */
    data class LinkDir(val link: FolderLink, val uuid: String) : Listing {
        override fun toString() = "LinkDir($uuid)"
    }

    /** True when the user can add, rename, move and trash items here. */
    val writable: Boolean get() = this is Dir

    /** The folder that new items go into, for writable listings. */
    val folder: String? get() = (this as? Dir)?.uuid
}

/** An open public folder link: its UUID, the decrypted link key text, and the hashed password ("empty" if none). */
@Immutable
data class FolderLink(val uuid: String, val key: String, val password: String) {
    override fun toString() = "FolderLink($uuid)"
}
