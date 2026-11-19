package app.filenza.media

import android.content.Context
import app.filenza.drive.Item

/** STUB: opens files in other apps and shares them, through proxy file descriptors (no plain copy on disk). */
object OpenWith {
    fun open(context: Context, item: Item) = Unit

    fun share(context: Context, items: List<Item>) = Unit
}
