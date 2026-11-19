package app.nubo.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nubo.api.ApiException
import app.nubo.app
import app.nubo.ui.Icons
import app.nubo.ui.components.PrivateTextInput
import app.nubo.ui.components.Toggle
import app.nubo.ui.components.openLink
import app.nubo.ui.components.userText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Email and password; a code field appears when the account has two-factor login. The password stays in
 * this screen's memory only (not saved across process death) and goes away after the login. DESIGN §7.
 * The link to make an account uses the referral of the developer while its switch is on. DESIGN §8.
 */
@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val account = context.app.account
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var needCode by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var referral by rememberSaveable { mutableStateOf(true) }
    val ready = email.contains('@') && password.isNotEmpty() && (!needCode || code.length >= 6) && !busy

    fun submit() {
        if (!ready) return
        busy = true
        error = null
        scope.launch {
            try {
                account.login(email, password, code.takeIf { needCode })
                password = ""
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.needs2fa) needCode = true else error = userText(e)
                if (e.code == "wrong_2fa" || e.code == "invalid_2fa") code = ""
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
            } catch (e: Exception) {
                error = userText(e)
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
            } finally {
                busy = false
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Cloud, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(20.dp))
                Text("Nubo", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Log in with your Filen account. Your password and your files are decrypted only on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                PrivateTextInput {
                    Column {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it.take(254); error = null },
                            label = { Text("Email") },
                            singleLine = true,
                            enabled = !busy && !needCode,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress + ContentType.Username },
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it.take(1024); error = null },
                            label = { Text("Password") },
                            singleLine = true,
                            enabled = !busy && !needCode,
                            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { shown = !shown }) {
                                    Icon(if (shown) Icons.VisibilityOff else Icons.Visibility, if (shown) "Hide password" else "Show password")
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (needCode) ImeAction.Next else ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { submit() }),
                            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
                        )
                        AnimatedVisibility(needCode) {
                            OutlinedTextField(
                                value = code,
                                onValueChange = { v -> code = v.filter(Char::isLetterOrDigit).take(32); error = null },
                                label = { Text("Two-factor code") },
                                supportingText = { Text("The code from your authenticator app, or a recovery key") },
                                singleLine = true,
                                enabled = !busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { submit() }),
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).semantics { contentType = ContentType.SmsOtpCode },
                            )
                        }
                    }
                }
                AnimatedVisibility(error != null) {
                    Text(
                        error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(onClick = ::submit, enabled = ready, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    if (busy) LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary) else Text("Log in")
                }
                Spacer(Modifier.height(12.dp))
                if (needCode) {
                    // A way back from the code step to the email and password (review 93).
                    TextButton(
                        onClick = {
                            needCode = false
                            code = ""
                            password = ""
                            error = null
                        },
                        enabled = !busy,
                    ) { Text("Use another account") }
                } else {
                    TextButton(onClick = { openLink(context, if (referral) REFERRAL_URL else SIGNUP_URL) }) {
                        Text("No account? Create one at filen.io")
                    }
                    Toggle(
                        Icons.PersonAdd,
                        "Use the referral of the Nubo developer",
                        "You and the developer can each get 10 GB more free storage. Filen then knows who invited you.",
                        referral,
                    ) { referral = it }
                }
            }
        }
    }
}

/** The Filen sign-up page, with no referral. */
private const val SIGNUP_URL = "https://filen.io"

/** The Filen referral link of the Nubo developer (owner decision 2026-11-19). DESIGN §8. */
private const val REFERRAL_URL = "https://filen.io/r/617af529fd2886d3d5be587d20f9184d"
