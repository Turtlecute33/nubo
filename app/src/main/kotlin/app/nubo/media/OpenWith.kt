package app.nubo.media

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.nubo.app
import app.nubo.drive.Item

/**
 * Opens files in other apps and sends copies, through [FileProvider]: each app gets a read grant for one URI
 * and reads the plain data from a proxy file descriptor. No plain copy goes to the disk.
 */
object OpenWith {
    fun open(context: Context, item: Item) {
        if (item.folder) return
        val uri = FileProvider.uriFor(context, item)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, FileProvider.mime(item))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, intent, "No app on this phone opens this file")
    }

    fun share(context: Context, items: List<Item>) {
        val files = items.filter { !it.folder }
        if (files.isEmpty()) return
        val uris = files.map { FileProvider.uriFor(context, it) }
        val types = files.map { FileProvider.mime(it) }.distinct()
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }
        send.type = types.singleOrNull() ?: commonType(types)
        // The chooser passes the grant on only for URIs in the ClipData.
        send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(send, "Send a copy"), "No app on this phone takes this file")
    }

    /** The shared family ("image/" and a star) for images of different types, else any type. */
    private fun commonType(types: List<String>): String {
        val families = types.map { it.substringBefore('/') }.distinct()
        return if (families.size == 1) "${families[0]}/*" else "*/*"
    }

    private fun start(context: Context, intent: Intent, none: String) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            context.app.messenger.show(none)
        }
    }
}
