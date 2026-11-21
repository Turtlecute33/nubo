package app.nubo.ui

import app.nubo.NuboApp
import app.nubo.ui.components.userText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** One short message for the snackbar, with an optional action. */
class Message(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

/** Messages from anywhere in the app to the one snackbar host. Old messages drop when many come at once. */
class Messenger {
    private val flow = MutableSharedFlow<Message>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<Message> = flow

    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        flow.tryEmit(Message(text, action, onAction))
    }
}

/**
 * Runs a write in the app scope: a screen that closes (a tap into a folder, a tab change) must not stop it halfway.
 * An error goes to the snackbar; after success, [done] with an optional undo.
 */
fun NuboApp.act(done: String? = null, undo: (suspend () -> Unit)? = null, block: suspend () -> Unit) {
    scope.launch {
        try {
            block()
            if (done != null) messenger.show(done, if (undo != null) "Undo" else null) { undo?.let { u -> act(block = u) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            messenger.show(userText(e))
        }
    }
}
