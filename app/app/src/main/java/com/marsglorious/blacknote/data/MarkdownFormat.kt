package com.marsglorious.blacknote.data

import com.marsglorious.blacknote.ffi.FormatKind

/**
 * Kotlin-native markdown format toggling, replacing the Rust `apply_format` FFI call.
 *
 * Two reasons this lives here instead of the Rust core:
 *  1. Offsets. Compose selections are UTF-16 code-unit offsets; the Rust side indexed
 *     bytes. Any note containing a non-ASCII character (emoji, accents, CJK) had its
 *     formatting applied at the wrong position.
 *  2. Toggling. The old path always wrapped, so tapping Bold twice produced
 *     `****text****`. This implementation detects existing markers and removes them.
 */
object MarkdownFormat {

    data class Result(val text: String, val selStart: Int, val selEnd: Int)

    fun apply(text: String, selStart: Int, selEnd: Int, kind: FormatKind): Result {
        val start = selStart.coerceIn(0, text.length)
        val end = selEnd.coerceIn(0, text.length).coerceAtLeast(start)
        return when (kind) {
            FormatKind.BOLD -> toggleInline(text, start, end, "**")
            FormatKind.ITALIC -> toggleInline(text, start, end, "_")
            FormatKind.STRIKE -> toggleInline(text, start, end, "~~")
            FormatKind.CODE -> toggleInline(text, start, end, "`")
            FormatKind.UNDERLINE -> toggleTag(text, start, end, "<u>", "</u>")
            FormatKind.BULLET_LIST -> toggleList(text, start, end, ordered = false)
            FormatKind.ORDERED_LIST -> toggleList(text, start, end, ordered = true)
        }
    }

    private fun toggleInline(text: String, start: Int, end: Int, marker: String): Result =
        togglePair(text, start, end, marker, marker)

    private fun toggleTag(text: String, start: Int, end: Int, open: String, close: String): Result =
        togglePair(text, start, end, open, close)

    /**
     * Wrap [start,end) in open/close markers, or remove them when already present.
     * Removal recognises two shapes: markers just inside the selection
     * ("**bold**" fully selected) and markers just outside it ("bold" selected
     * inside an already-wrapped run).
     */
    private fun togglePair(text: String, start: Int, end: Int, open: String, close: String): Result {
        val sel = text.substring(start, end)
        // Markers inside the selection.
        if (sel.length >= open.length + close.length &&
            sel.startsWith(open) && sel.endsWith(close)
        ) {
            val inner = sel.substring(open.length, sel.length - close.length)
            val out = text.substring(0, start) + inner + text.substring(end)
            return Result(out, start, start + inner.length)
        }
        // Markers immediately outside the selection.
        val before = start - open.length
        val after = end + close.length
        if (before >= 0 && after <= text.length &&
            text.startsWith(open, before) && text.startsWith(close, end)
        ) {
            val out = text.substring(0, before) + sel + text.substring(after)
            return Result(out, before, before + sel.length)
        }
        // Not wrapped — wrap. Empty selection puts the cursor between the markers.
        val out = text.substring(0, start) + open + sel + close + text.substring(end)
        return Result(out, start + open.length, start + open.length + sel.length)
    }

    /**
     * Toggle list prefixes on every line the selection touches (cursor-only selections
     * affect the current line). If all touched non-blank lines already carry the prefix
     * the toggle strips it; otherwise it adds it, renumbering ordered items from 1.
     */
    private fun toggleList(text: String, start: Int, end: Int, ordered: Boolean): Result {
        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let {
            if (start == 0) 0 else if (it < 0) 0 else it + 1
        }
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        val block = text.substring(lineStart, lineEnd)
        val lines = block.split('\n')

        fun stripPrefix(line: String): String? {
            if (!ordered) return if (line.startsWith("- ")) line.substring(2) else null
            var k = 0
            while (k < line.length && line[k].isDigit()) k++
            return if (k > 0 && line.startsWith(". ", k)) line.substring(k + 2) else null
        }

        val nonBlank = lines.filter { it.isNotBlank() }
        val allPrefixed = nonBlank.isNotEmpty() && nonBlank.all { stripPrefix(it) != null }
        var counter = 1
        val newLines = lines.map { line ->
            when {
                // Leave blank lines unchanged only when other non-blank lines exist in the
                // selection — that preserves paragraph gaps in multi-line list toggling.
                // A single blank line (cursor-only on an empty line) must still receive the
                // prefix so the user can start a list item on an empty line.
                line.isBlank() && lines.size > 1 -> line
                allPrefixed -> stripPrefix(line) ?: line
                else -> {
                    // Re-prefixing: replace an existing list marker of the other kind.
                    val bare = line.removePrefix("- ").let { l ->
                        var k = 0
                        while (k < l.length && l[k].isDigit()) k++
                        if (k > 0 && l.startsWith(". ", k)) l.substring(k + 2) else l
                    }
                    if (ordered) "${counter++}. $bare" else "- $bare"
                }
            }
        }
        val newBlock = newLines.joinToString("\n")
        val out = text.substring(0, lineStart) + newBlock + text.substring(lineEnd)
        val delta = newBlock.length - block.length
        return Result(out, lineStart, (lineEnd + delta).coerceAtLeast(lineStart))
    }
}
