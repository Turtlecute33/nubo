package app.nubo.ui.settings

import android.app.KeyguardManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nubo.BuildConfig
import app.nubo.app
import app.nubo.data.ThemeMode
import app.nubo.ui.Icons
import app.nubo.ui.components.IconAction
import app.nubo.ui.components.SectionHeader
import app.nubo.ui.components.SettingRow
import app.nubo.ui.components.Toggle
import app.nubo.ui.components.openLink
import app.nubo.ui.nav.LocalNav
import app.nubo.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Appearance, the bottom bar, security, storage on this phone, support links and the licences. */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val s by app.prefs.state.collectAsStateWithLifecycle()
    val keyguard = remember { context.getSystemService(KeyguardManager::class.java) }
    var secure by remember { mutableStateOf(keyguard.isDeviceSecure) }
    var cacheSize by remember { mutableLongStateOf(-1L) }
    var offlineSize by remember { mutableLongStateOf(-1L) }
    // The user can set or remove the screen lock in the system settings and come back.
    LifecycleResumeEffect(Unit) {
        secure = keyguard.isDeviceSecure
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { context.cacheDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }
        offlineSize = app.offline.size()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconAction(Icons.ArrowBack, "Back", nav::back) }) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "h-look") { SectionHeader("Appearance") }
            item(key = "theme") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                    modes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = s.theme == mode,
                            onClick = { app.prefs.edit { it.copy(theme = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(label) }
                    }
                }
            }
            item(key = "dynamic") { Toggle(Icons.Palette, "Wallpaper colours", null, s.dynamicColor) { v -> app.prefs.edit { it.copy(dynamicColor = v) } } }
            // Pure black changes only the dark theme.
            item(key = "black") {
                Toggle(Icons.DarkMode, "Pure black", null, s.pureBlack, enabled = s.theme != ThemeMode.LIGHT) { v -> app.prefs.edit { it.copy(pureBlack = v) } }
            }

            item(key = "h-bar") { SectionHeader("Bottom bar") }
            item(key = "notes-tab") { Toggle(Icons.StickyNote, "Notes", null, s.notesTab) { v -> app.prefs.edit { it.copy(notesTab = v) } } }
            item(key = "chats-tab") { Toggle(Icons.Chat, "Chats", null, s.chatsTab) { v -> app.prefs.edit { it.copy(chatsTab = v) } } }

            item(key = "h-security") { SectionHeader("Security") }
            item(key = "lock") {
                Toggle(
                    Icons.Fingerprint,
                    "App lock",
                    if (secure) null else "Set a screen lock on this phone first",
                    s.appLock,
                    enabled = secure || s.appLock,
                ) { v -> app.prefs.edit { it.copy(appLock = v) } }
            }
            item(key = "screens") {
                Toggle(Icons.VisibilityOff, "Block screenshots", null, s.blockScreenshots) { v -> app.prefs.edit { it.copy(blockScreenshots = v) } }
            }

            item(key = "h-storage") { SectionHeader("On this phone") }
            item(key = "cache") {
                SettingRow(Icons.CleaningServices, "Clear cache", cacheSize.takeIf { it >= 0 }?.let(Format::size)) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            app.thumbs.clear()
                            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                        }
                        cacheSize = 0
                        app.messenger.show("Cache cleared")
                    }
                }
            }
            item(key = "offline") { SettingRow(Icons.OfflinePin, "Offline files", offlineSize.takeIf { it >= 0 }?.let(Format::size)) }

            item(key = "h-support") { SectionHeader("Support") }
            item(key = "donate") { SettingRow(Icons.CurrencyBitcoin, "Donate bitcoin", "btcpay.priorato.org", Icons.OpenInNew) { openLink(context, DONATE_URL) } }
            item(key = "x") { SettingRow(Icons.AlternateEmail, "X", "@turtlecute33", Icons.OpenInNew) { openLink(context, "https://x.com/turtlecute33") } }
            item(key = "nostr") { SettingRow(Icons.Hub, "Nostr", "turtlecute@primal.net", Icons.OpenInNew) { openLink(context, "https://primal.net/turtlecute") } }

            item(key = "h-about") { SectionHeader("About") }
            item(key = "version") { SettingRow(Icons.Info, "Nubo ${BuildConfig.VERSION_NAME}", "Unofficial client, not affiliated with Filen") }
            item(key = "licences") { SettingRow(Icons.Description, "Licences", "Nubo GPL-3.0 · Bouncy Castle MIT · Material Symbols Apache-2.0") }
        }
    }
}

/** Settings › Support. The browser opens the links; Nubo does not connect to these hosts. */
private const val DONATE_URL =
    "https://btcpay.priorato.org/api/v1/invoices?storeId=31ntjiLSTmSSuTC6FrR81z6AAku46bLF2FKAL3MFHtV5&orderId=Donation&checkoutDesc=Donation&currency=USD"
