package app.filenza.ui.me

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.filenza.FilenzaApp
import app.filenza.app
import app.filenza.drive.Usage
import app.filenza.ui.components.userText
import app.filenza.util.Memo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The storage use for Home and Me. A read of the last minute shows again with no new read (review 52); a read
 * that fails gives [error], and the screen shows "Try again" (review 48).
 */
@Stable
class UsageModel(private val app: FilenzaApp, private val scope: CoroutineScope) {
    private val user = app.account.session?.userId ?: 0L
    var usage by mutableStateOf(last.get(user))
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Reads the use when no recent read is in memory, or always when [force]. */
    fun load(force: Boolean = false) {
        if (!force && usage != null) return
        error = null
        scope.launch {
            try {
                usage = app.drive.usage().also { last.put(user, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = userText(e)
            }
        }
    }

    private companion object {
        /** Keyed by the user ID: another login does not see this account's use. */
        val last = Memo<Long, Usage>(ttlMs = 60_000, max = 1)
    }
}

@Composable
fun rememberUsage(): UsageModel {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val model = remember { UsageModel(app, scope) }
    LaunchedEffect(model) { model.load() }
    return model
}
