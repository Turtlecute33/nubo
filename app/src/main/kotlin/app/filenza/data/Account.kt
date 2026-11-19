package app.filenza.data

import app.filenza.api.Api
import app.filenza.api.ApiException
import app.filenza.api.Auth
import app.filenza.crypto.Keyring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

sealed interface AccountState {
    /** The session file is not read yet; the splash screen stays. */
    data object Loading : AccountState
    data object LoggedOut : AccountState
    data class LoggedIn(val session: Session) : AccountState
}

/**
 * The current session. Login saves it encrypted ([SessionStore]); logout ends it on the server (best effort)
 * and runs [onLogout], which deletes every local trace of the account (caches, offline files, transfers).
 */
class Account(
    private val api: Api,
    private val store: SessionStore,
    /** Runs on an IO thread before the session file goes: it marks the wipe as pending (review 74). */
    private val beforeLogout: () -> Unit,
    private val onLogout: suspend () -> Unit,
) {
    private val _state = MutableStateFlow<AccountState>(AccountState.Loading)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    val session: Session? get() = (_state.value as? AccountState.LoggedIn)?.session

    /** The account keys. Throws when nobody is logged in. */
    fun keys(): Keyring = session?.keys ?: throw ApiException("api_key_not_found", 401)

    /** Reads the saved session. Off the main thread (Keystore). */
    fun load() {
        val s = store.load()
        api.key = s?.apiKey
        _state.value = if (s != null) AccountState.LoggedIn(s) else AccountState.LoggedOut
    }

    suspend fun login(email: String, password: String, code: String?) {
        val s = Auth(api).login(email, password, code)
        withContext(Dispatchers.IO) { store.save(s) }
        api.key = s.apiKey
        _state.value = AccountState.LoggedIn(s)
    }

    /**
     * Ends the session on this phone. Filen has no logout or key-revoke call (research F-30): the API key
     * stays valid on the server until a password change, but no copy of it stays here.
     */
    suspend fun logout() {
        if (session == null) return
        withContext(Dispatchers.IO) {
            beforeLogout()
            store.clear()
        }
        api.key = null
        _state.value = AccountState.LoggedOut
        onLogout()
    }

    /** The server refused the key (expired or revoked): the same as a logout. */
    suspend fun ended() = logout()
}
