package app.filenza.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.filenza.app
import app.filenza.drive.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A text or code file up to [MAX_BYTES], as selectable text (monospace for code). Bytes that are not UTF-8
 * show as the replacement character. Blocks of lines go into a lazy list, so long files scroll smoothly.
 */
@Composable
internal fun TextPage(item: Item, mono: Boolean) {
    val app = LocalContext.current.app
    val state = rememberLoaded(item.uuid + item.key) {
        val bytes = app.content.bytes(item, MAX_BYTES)
        withContext(Dispatchers.Default) { bytes.toString(Charsets.UTF_8).lines().chunked(LINES).map { it.joinToString("\n") } }
    }
    val blocks = state.value
    val error = state.error
    when {
        blocks != null -> {
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 72.dp, bottom = bottom + 24.dp)) {
                    items(blocks.size) { i ->
                        Text(
                            blocks[i],
                            style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                    }
                }
            }
        }
        error != null -> PageMessage(item, error, state::retry)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
    }
}

private const val MAX_BYTES = 2L * 1000 * 1000
private const val LINES = 60
