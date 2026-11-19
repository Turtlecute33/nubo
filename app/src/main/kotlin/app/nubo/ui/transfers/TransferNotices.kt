package app.nubo.ui.transfers

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.nubo.app
import app.nubo.transfer.Transfer
import app.nubo.ui.nav.Navigator
import app.nubo.ui.nav.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Tells the user about failed transfers (review 39): one snackbar "2 uploads failed" with "Show" for the
 * failures that come close together. At the first transfer, the app asks one time for the notification
 * permission, so that the transfer job can show its progress.
 */
@Composable
fun TransferNotices(nav: Navigator) {
    val app = LocalContext.current.app
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var asked by rememberSaveable { mutableStateOf(false) }
    var reported by rememberSaveable { mutableStateOf(LongArray(0)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (asked) return@LaunchedEffect
        app.transfers.list.first { it.isNotEmpty() }
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        asked = true
        val a = activity ?: return@LaunchedEffect
        val p = Manifest.permission.POST_NOTIFICATIONS
        // After one refusal the system sets the rationale flag: then the app does not ask again.
        if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED && !a.shouldShowRequestPermissionRationale(p)) {
            permission.launch(p)
        }
    }

    LaunchedEffect(Unit) {
        // Failures in the background show when the user comes back.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            app.transfers.list.map { l -> l.filter { it.state == Transfer.State.FAILED } }.distinctUntilChanged().collectLatest { failed ->
                delay(GROUP_MS)
                val new = failed.filter { it.id !in reported }
                reported = LongArray(failed.size) { failed[it].id }
                if (new.isNotEmpty() && nav.current != Route.Transfers) {
                    app.messenger.show(failedText(new.map { it.kind }), "Show") { nav.go(Route.Transfers) }
                }
            }
        }
    }
}

/** "1 upload failed", "2 downloads failed", or "3 transfers failed" when the kinds are not the same. */
internal fun failedText(kinds: List<Transfer.Kind>): String {
    val (one, many) = when (kinds.distinct().singleOrNull()) {
        Transfer.Kind.UPLOAD -> "upload" to "uploads"
        Transfer.Kind.DOWNLOAD -> "download" to "downloads"
        Transfer.Kind.OFFLINE -> "offline copy" to "offline copies"
        null -> "transfer" to "transfers"
    }
    return "${kinds.size} ${if (kinds.size == 1) one else many} failed"
}

/** Failures in this time make one message. */
private const val GROUP_MS = 1_000L
