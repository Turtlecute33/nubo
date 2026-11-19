package app.filenza.ui.share

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.filenza.api.ApiException
import app.filenza.app
import app.filenza.drive.Item
import app.filenza.drive.LinkState
import app.filenza.drive.Sharing
import app.filenza.ui.Icons
import app.filenza.ui.components.ConfirmDialog
import app.filenza.ui.components.ItemThumb
import app.filenza.ui.components.LoadingBox
import app.filenza.ui.components.PrivateTextInput
import app.filenza.ui.components.userText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The public link of one own item: make it, copy or send it, change its expiry and password, or remove it.
 * The writes run in the app scope, so that closing the sheet does not stop a folder link half way. The link
 * holds the key: it goes only to the clipboard (as sensitive text) or to the app that the user picks.
 */
@Composable
fun LinkSheet(item: Item, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val sharing = app.drive.sharing
    val haptics = LocalHapticFeedback.current
    val linkable = remember(item) { sharing.canLink(item) }
    var state by remember { mutableStateOf<LinkState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // A refusal for the plan: a new try gets the same answer, so the sheet shows no "Try again" (review 84).
    var final by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var expiration by remember { mutableStateOf("never") }
    var protect by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf(false) }

    fun show(s: LinkState) {
        state = s
        expiration = known(s.expiration)
        protect = s.hasPassword
        password = ""
    }

    LaunchedEffect(item.uuid, attempt) {
        if (!linkable) return@LaunchedEffect
        error = null
        try {
            show(sharing.linkState(item))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = userText(e)
            final = (e as? ApiException)?.code == "subscription_needed"
        }
    }

    /** Runs a write in the app scope; the sheet shows the result if it is still open. */
    fun write(block: suspend () -> LinkState?) {
        busy = true
        error = null
        app.scope.launch {
            try {
                show(block() ?: LinkState(false))
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = expandedSheet()) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SheetHeader(item, "Public link")
            HorizontalDivider()
            val s = state
            when {
                !linkable -> Note(
                    Icons.LinkOff,
                    "Public links for this file are not available. Its key is longer than the key that a Filen link can hold.",
                )
                s == null && error != null -> Column(Modifier.padding(24.dp)) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    if (!final) {
                        Spacer(Modifier.height(12.dp))
                        FilledTonalButton(onClick = { attempt++ }) { Text("Try again") }
                    }
                }
                s == null -> LoadingBox(Modifier.padding(vertical = 48.dp))
                else -> {
                    if (s.enabled) {
                        LinkBox(
                            s.url,
                            onCopy = {
                                SecretClip.copy(context, "Filen link", s.url)
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                app.messenger.show("Link copied. The app clears it when you come back, or after 1 minute in the app.")
                            },
                            onSend = {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.url)
                                runCatching { context.startActivity(Intent.createChooser(send, null)) }
                            },
                        )
                    }
                    Settings(
                        expiration = expiration,
                        onExpiration = { expiration = it },
                        protect = protect,
                        onProtect = { on ->
                            protect = on
                            if (!on) password = ""
                        },
                        password = password,
                        onPassword = { password = it },
                        hadPassword = s.enabled && s.hasPassword,
                        enabled = !busy,
                    )
                    AnimatedVisibility(error != null) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    }
                    val changed = !s.enabled || expiration != known(s.expiration) || protect != s.hasPassword || password.isNotEmpty()
                    val ready = !busy && changed && (!protect || password.isNotEmpty())
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (s.enabled) {
                            TextButton(onClick = { removing = true }, enabled = !busy) {
                                Text("Remove link", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { write { sharing.setLink(item, expiration, password.takeIf { protect }) } }, enabled = ready) {
                            if (busy) LoadingIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                            else Text(if (s.enabled) "Save changes" else "Make a public link")
                        }
                    }
                    if (busy && item.folder && !s.enabled) {
                        Text(
                            "A folder link copies the name and key of each item inside. A large folder takes a while. You can close this sheet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }
            }
            Note(
                Icons.PrivacyTip,
                "Anyone with the link can open ${if (item.folder) "this folder and everything in it" else "this file"}. " +
                    "The key is in the link, after the “#”. Filen never gets it, so keep the link private.",
            )
        }
    }
    if (removing) {
        ConfirmDialog(
            title = "Remove the public link?",
            text = "The link stops working for everyone who has it.",
            confirm = "Remove",
            destructive = true,
            onConfirm = {
                write {
                    sharing.removeLink(item)
                    null
                }
            },
            onDismiss = { removing = false },
        )
    }
}

/** A sheet that opens fully, with no half-open step: these sheets hold text fields. */
@Composable
internal fun expandedSheet(): SheetState =
    rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))

/** The server's expiry, when it is one of the values that the app can send; else "never". */
private fun known(expiration: String): String = expiration.takeIf { it in Sharing.EXPIRATIONS } ?: "never"

/** The thumbnail, the title of the sheet and the item name. */
@Composable
internal fun SheetHeader(item: Item, title: String) {
    Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ItemThumb(item, size = 48.dp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

/** A short explanation with an icon. */
@Composable
internal fun Note(icon: ImageVector, text: String) {
    Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LinkBox(url: String, onCopy: () -> Unit, onSend: () -> Unit) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Link, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(url, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
                Icon(Icons.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Copy")
            }
            OutlinedButton(onClick = onSend, modifier = Modifier.weight(1f)) {
                Icon(Icons.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Send")
            }
        }
    }
}

@Composable
private fun Settings(
    expiration: String,
    onExpiration: (String) -> Unit,
    protect: Boolean,
    onProtect: (Boolean) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    hadPassword: Boolean,
    enabled: Boolean,
) {
    Text(
        "Expires",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 8.dp),
    )
    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(Sharing.EXPIRATIONS, key = { it }) { code ->
            FilterChip(selected = expiration == code, onClick = { onExpiration(code) }, enabled = enabled, label = { Text(expiryLabel(code)) })
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Key, null)
        Spacer(Modifier.width(16.dp))
        Text("Password", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = protect, onCheckedChange = onProtect, enabled = enabled)
    }
    AnimatedVisibility(protect) {
        PrivateTextInput {
            OutlinedTextField(
                value = password,
                onValueChange = { onPassword(it.take(256)) },
                label = { Text("Link password") },
                supportingText = { Text(if (hadPassword) "Enter the password again to keep it, or a new one" else "Visitors must type it to open the link") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

private fun expiryLabel(code: String): String = when (code) {
    "never" -> "Never"
    "1h" -> "1 hour"
    "6h" -> "6 hours"
    "1d" -> "1 day"
    "3d" -> "3 days"
    "7d" -> "7 days"
    "14d" -> "14 days"
    "30d" -> "30 days"
    else -> code
}
