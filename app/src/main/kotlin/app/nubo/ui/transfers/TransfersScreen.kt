package app.nubo.ui.transfers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nubo.app
import app.nubo.transfer.Transfer
import app.nubo.ui.Icons
import app.nubo.ui.components.EmptyState
import app.nubo.ui.components.FileTypeIcon
import app.nubo.ui.components.IconAction
import app.nubo.ui.nav.LocalNav
import app.nubo.util.FileType
import app.nubo.util.Format

/** Running and finished transfers of this session, with cancel and retry. */
@Composable
fun TransfersScreen() {
    val app = LocalContext.current.app
    val nav = LocalNav.current
    val list by app.transfers.list.collectAsStateWithLifecycle()
    val summary = remember(list) { app.transfers.summary(list) }
    val ordered = remember(list) { list.sortedWith(compareBy<Transfer>({ !it.active }, { -it.id })) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfers") },
                navigationIcon = { IconAction(Icons.ArrowBack, "Back", nav::back) },
                actions = { if (list.any { !it.active }) IconAction(Icons.DeleteSweep, "Clear finished", app.transfers::clearFinished) },
            )
        },
    ) { pad ->
        if (list.isEmpty()) {
            EmptyState(Icons.SwapVert, "No transfers", Modifier.fillMaxSize().padding(pad), message = "Uploads, downloads and offline copies show here.")
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (summary.active > 0) item(key = "summary") {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(
                            "${summary.active} running" + if (summary.speed > 0) " · ${Format.speed(summary.speed)}" else "",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearWavyProgressIndicator(progress = { summary.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("${Format.size(summary.done)} / ${Format.size(summary.size)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(ordered.size, key = { ordered[it].id }) { i -> Row(ordered[i], Modifier.animateItem()) }
        }
    }
}

@Composable
private fun Row(t: Transfer, modifier: Modifier) {
    val transfers = LocalContext.current.app.transfers
    ListItem(
        modifier = modifier,
        leadingContent = {
            Box {
                FileTypeIcon(FileType.of(t.name), size = 44.dp)
                val badge = when (t.kind) {
                    Transfer.Kind.UPLOAD -> Icons.ArrowUpward
                    Transfer.Kind.DOWNLOAD -> Icons.ArrowDownward
                    Transfer.Kind.OFFLINE -> Icons.OfflinePin
                }
                Icon(badge, null, Modifier.size(16.dp).padding(0.dp), tint = MaterialTheme.colorScheme.primary)
            }
        },
        supportingContent = {
            Column {
                if (t.state == Transfer.State.RUNNING) {
                    LinearWavyProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                }
                Text(
                    when (t.state) {
                        Transfer.State.QUEUED -> "Waiting"
                        Transfer.State.RUNNING -> "${Format.size(t.done)} / ${Format.size(t.size)}" + if (t.speed > 0) " · ${Format.speed(t.speed)}" else ""
                        Transfer.State.DONE -> "Done · ${Format.size(t.size)}"
                        Transfer.State.FAILED -> t.error ?: "Failed"
                        Transfer.State.CANCELED -> "Cancelled"
                    },
                    color = if (t.state == Transfer.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        },
        trailingContent = {
            when {
                t.active -> IconAction(Icons.Close, "Cancel", { transfers.cancel(t.id) })
                t.state == Transfer.State.FAILED || t.state == Transfer.State.CANCELED -> IconAction(Icons.Replay, "Try again", { transfers.retry(t.id) })
                else -> Icon(Icons.CheckCircleFill, "Done", tint = MaterialTheme.colorScheme.primary)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(t.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) }
}
