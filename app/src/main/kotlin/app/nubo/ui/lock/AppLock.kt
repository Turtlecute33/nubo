package app.nubo.ui.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The optional app lock (DESIGN §7): a screen lock over the UI, set at each process start and each time the
 * app goes to the background. It does not encrypt anything; the session stays usable for transfers.
 */
object AppLock {
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun onBackground(enabled: Boolean) {
        if (enabled) _locked.value = true
    }

    fun unlock() {
        _locked.value = false
    }
}
