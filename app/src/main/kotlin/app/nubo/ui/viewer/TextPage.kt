package app.nubo.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.nubo.app
import app.nubo.drive.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A text or code file up to [MAX_BYTES] (monospace for code). Bytes that are not UTF-8 show as the replacement
 * character. Blocks of lines go into a lazy list, so long files scroll smoothly. [top] is the height of the
 * top bar. The text cannot be selected: a copy would put the file text on the clipboard as plain text, where
 * other apps can read it (review 69).
 */
@Composable
internal fun TextPage(item: Item, mono: Boolean, current: Boolean, cache: PageCache, top: Dp) {
    val app = LocalContext.current.app
    val state = rememberLoaded(item.uuid + item.key, cache, current, ::charsOf) {
        val bytes = app.content.bytes(item, MAX_BYTES)
        withContext(Dispatchers.Default) { textBlocks(bytes.toString(Charsets.UTF_8)) }
    }
    val blocks = state.value
    val error = state.error
    when {
        blocks != null -> {
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = bottom + 24.dp)) {
                items(blocks.size, key = { it }) { i ->
                    Text(
                        blocks[i],
                        style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
            }
        }
        error != null -> PageMessage(item, error, state::retry)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
    }
}

/** UTF-16: 2 bytes for each character. */
private fun charsOf(blocks: List<String>): Long = blocks.sumOf { it.length.toLong() * 2 }

/**
 * [text] in blocks of at most [lines] lines and at most [chars] characters. A line that is longer than [chars]
 * (a minified JSON file) goes into more than one block, so that no single Text is too large to lay out
 * (review 53). A cut never splits a surrogate pair.
 */
internal fun textBlocks(text: String, lines: Int = LINES, chars: Int = CHARS): List<String> {
    val s = text.replace("\r\n", "\n").replace('\r', '\n')
    val out = ArrayList<String>()
    var start = 0
    while (start < s.length) {
        val limit = minOf(s.length, start + chars)
        var end = start
        var count = 0
        while (end < limit && !(s[end] == '\n' && ++count == lines)) end++
        if (end == limit && end < s.length && end - start > 1 && Character.isHighSurrogate(s[end - 1])) end--
        out += s.substring(start, end)
        // The line break between two blocks is the gap between their Texts.
        start = if (end < s.length && s[end] == '\n') end + 1 else end
    }
    return out.ifEmpty { listOf("") }
}

private const val MAX_BYTES = 2L * 1000 * 1000
private const val LINES = 60
private const val CHARS = 4_000
