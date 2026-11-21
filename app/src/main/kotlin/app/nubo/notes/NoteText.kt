package app.nubo.notes

import androidx.compose.runtime.Immutable

/** The five note types of Filen (research N-04). An unknown type is null: Nubo shows it read-only (N-94). */
enum class NoteType(val wire: String, val label: String) {
    TEXT("text", "Text"),
    MD("md", "Markdown"),
    CODE("code", "Code"),
    RICH("rich", "Rich text"),
    CHECKLIST("checklist", "Checklist");

    companion object {
        fun of(wire: String): NoteType? = entries.firstOrNull { it.wire == wire }

        /** The types that Nubo can write. Rich text is Quill HTML: Nubo only shows it (DESIGN §12). */
        val writable = listOf(TEXT, CHECKLIST, MD, CODE)
    }
}

/** One row of a checklist note. */
@Immutable
data class Check(val text: String, val done: Boolean)

/**
 * The content formats of the official apps (research N-50..N-54). Pure functions on text: the JVM tests run them.
 * Note HTML comes from other users too: Nubo never renders it as HTML, it only takes the text out.
 */
object NoteText {
    /** Preview length of the official apps, in UTF-16 units (N-54). */
    const val PREVIEW_MAX = 128

    /** A checklist from its HTML (N-52): lists without `data-checked` are not done; inner tags go; entities decode. */
    fun parseChecklist(html: String): List<Check> {
        val out = ArrayList<Check>()
        val lists = ArrayDeque<Boolean>()
        var item: StringBuilder? = null
        var done = false
        var pos = 0
        // An item ends at its </li>, at the next <li>, and at a nested list (old data); a nested list keeps its parent
        // only when the parent has text.
        fun flush(keepEmpty: Boolean) {
            val b = item ?: return
            item = null
            val text = decodeEntities(stripTags(b.toString())).trim()
            if (keepEmpty || text.isNotEmpty()) out += Check(text, done)
        }
        for (m in LIST_TAG.findAll(html)) {
            item?.append(html, pos, m.range.first)
            pos = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            if (m.groupValues[2].equals("li", ignoreCase = true)) {
                flush(keepEmpty = true)
                if (!closing) {
                    item = StringBuilder()
                    done = lists.lastOrNull() ?: false
                }
            } else if (closing) {
                flush(keepEmpty = true)
                lists.removeLastOrNull()
            } else {
                flush(keepEmpty = false)
                lists.addLast(CHECKED.find(m.groupValues[3])?.groupValues?.get(1).equals("true", ignoreCase = true))
            }
        }
        item?.append(html, pos, html.length)
        flush(keepEmpty = true)
        return out
    }

    /** The exact HTML of the official writer (N-52): one `<ul data-checked>` for each run of rows with the same state. */
    fun writeChecklist(items: List<Check>): String = buildString {
        var open: Boolean? = null
        for (c in items) {
            if (open != c.done) {
                if (open != null) append("</ul>")
                append("<ul data-checked=\"").append(c.done).append("\">")
                open = c.done
            }
            val text = c.text.trim()
            append("<li>").append(if (text.isEmpty()) "<br>" else escape(text)).append("</li>")
        }
        if (open != null) append("</ul>")
    }

    /** The plain-text preview that the note lists show (N-54). */
    fun preview(type: NoteType?, content: String): String = cut(
        when (type) {
            NoteType.RICH -> {
                val end = content.indexOf("<p><br></p>")
                decodeEntities(stripTags(if (end >= 0) content.substring(0, end) else content.substringBefore('\n')))
            }
            NoteType.CHECKLIST -> parseChecklist(content).firstOrNull { it.text.isNotEmpty() }?.text.orEmpty()
            else -> stripTags(content.substringBefore('\n'))
        },
        PREVIEW_MAX,
    )

    /** Rich-text HTML as readable text: one line for each block, "• " for list items. */
    fun plain(html: String): String {
        val lines = html.replace(EMPTY_P, "\n").replace(BLOCK_END, "\n").replace(BR, "\n").replace(LI_START, "• ")
        return decodeEntities(stripTags(lines)).trimEnd()
    }

    /**
     * [content] of type [from] as content of type [to]. The official apps do not convert (N-53), so a text note that
     * becomes a checklist would show one empty row there: Nubo makes one row for each line, and one line for each row.
     */
    fun convert(content: String, from: NoteType?, to: NoteType): String = when {
        from == to -> content
        to == NoteType.CHECKLIST -> writeChecklist(content.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { Check(it, false) }.ifEmpty { listOf(Check("", false)) })
        from == NoteType.CHECKLIST -> parseChecklist(content).joinToString("\n") { it.text }
        from == NoteType.RICH -> plain(content)
        else -> content
    }

    /** [text] cut to [max] UTF-16 units, never inside a surrogate pair (N-54). */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = if (text[max - 1].isHighSurrogate()) max - 1 else max
        return text.substring(0, end)
    }

    fun stripTags(text: String): String = if ('<' in text) TAG.replace(text, "") else text

    /** Named entities of the HTML writers, and numeric ones. An unknown entity stays as it is. */
    fun decodeEntities(text: String): String = if ('&' !in text) text else ENTITY.replace(text) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint)
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[e]
        } ?: m.value
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun codePoint(c: Int): String? = if (c != 0 && Character.isValidCodePoint(c)) String(Character.toChars(c)) else null

    private val LIST_TAG = Regex("""<(/?)(ul|ol|li)\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val CHECKED = Regex("""data-checked\s*=\s*["']?(true|false)""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
    private val EMPTY_P = Regex("""<p>\s*<br\s*/?>\s*</p>""", RegexOption.IGNORE_CASE)
    private val BLOCK_END = Regex("</(p|h[1-6]|blockquote|pre|div|li)>", RegexOption.IGNORE_CASE)
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val LI_START = Regex("""<li\b[^>]*>""", RegexOption.IGNORE_CASE)
}
