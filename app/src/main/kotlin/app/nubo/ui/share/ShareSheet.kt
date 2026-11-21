package app.nubo.ui.share

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nubo.app
import app.nubo.drive.Item
import app.nubo.drive.ShareUser
import app.nubo.ui.Icons
import app.nubo.ui.components.ConfirmDialog
import app.nubo.ui.components.IconAction
import app.nubo.ui.components.LoadingBox
import app.nubo.ui.components.PrivateTextInput
import app.nubo.ui.components.SectionHeader
import app.nubo.ui.components.userText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Shares one own item with other Filen users, and lists and removes the users. Receivers get read-only
 * access. The writes run in the app scope, so that closing the sheet does not stop a folder share half way.
 */
@Composable
fun ShareSheet(item: Item, onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val sharing = app.drive.sharing
    val haptics = LocalHapticFeedback.current
    var users by remember { mutableStateOf<List<ShareUser>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ShareUser?>(null) }
    val mail = email.trim()
    val valid = mail.length in 3..254 && mail.indexOf('@') in 1 until mail.lastIndex && mail.none { it.isWhitespace() }

    LaunchedEffect(item.uuid, attempt) {
        error = null
        try {
            users = sharing.sharedWith(item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = userText(e)
        }
    }

    /** Runs a write in the app scope, then reads the user list again. */
    fun write(done: String, block: suspend () -> Unit) {
        busy = true
        error = null
        app.scope.launch {
            try {
                block()
                users = sharing.sharedWith(item)
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                app.messenger.show(done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = userText(e)
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
            } finally {
                busy = false
            }
        }
    }

    fun submit() {
        if (!valid || busy) return
        val to = mail
        write("Shared with $to") {
            sharing.share(item, to)
            email = ""
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = expandedSheet()) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetHeader(item, "Share with Filen users")
            HorizontalDivider()
            PrivateTextInput {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.take(254) },
                    label = { Text("Filen account email") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 16.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Button(onClick = ::submit, enabled = valid && !busy) {
                    if (busy) {
                        LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.PersonAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Share")
                    }
                }
            }
            AnimatedVisibility(error != null) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    if (users == null) {
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(onClick = { attempt++ }) { Text("Try again") }
                    }
                }
            }
            SectionHeader("Shared with", inset = 24.dp)
            val list = users
            when {
                list == null && error == null -> LoadingBox(Modifier.padding(vertical = 24.dp))
                list.isNullOrEmpty() -> Text(
                    "Nobody",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                else -> list.forEach { user ->
                    ListItem(
                        leadingContent = { Icon(Icons.Person, null) },
                        trailingContent = { IconAction(Icons.PersonRemove, "Stop sharing with ${user.email}", { removing = user }, enabled = !busy) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text(user.email, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
                }
            }
        }
    }
    removing?.let { user ->
        ConfirmDialog(
            title = "Stop sharing with ${user.email}?",
            text = "This user loses access to ${if (item.folder) "the folder" else "the file"}.",
            confirm = "Stop sharing",
            destructive = true,
            onConfirm = { write("Sharing stopped") { sharing.stopSharing(item, user) } },
            onDismiss = { removing = null },
        )
    }
}
