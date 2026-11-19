package app.nubo.ui

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

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
