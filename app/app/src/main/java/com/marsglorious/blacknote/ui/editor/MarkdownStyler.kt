package com.marsglorious.blacknote.ui.editor

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.marsglorious.blacknote.ui.theme.MdColors

/**
 * Offset-preserving Markdown styler. Renders styling on top of the raw markdown
 * source — markers stay visible (dimmed), inner content gets the appropriate
 * weight/style/decoration. Used both as a Compose VisualTransformation in the
 * editor and as a stand-alone AnnotatedString builder for the render view.
 */
fun styleMarkdown(src: String): AnnotatedString = AnnotatedString.Builder().apply {
    append(src)
    applyFrontmatter(src)
    applyHeadings(src)
    applyLists(src)
    applyInlinePairs(src, "**", SpanStyle(fontWeight = FontWeight.Bold))
    applyInlinePairs(src, "__", SpanStyle(fontWeight = FontWeight.Bold))
    applyInlinePairs(src, "*", SpanStyle(fontStyle = FontStyle.Italic))
    applyInlinePairs(src, "_", SpanStyle(fontStyle = FontStyle.Italic))
    applyInlinePairs(src, "~~", SpanStyle(textDecoration = TextDecoration.LineThrough))
    applyInlinePairs(src, "`", SpanStyle(fontFamily = FontFamily.Monospace, background = MdColors.SurfaceHi2, color = MdColors.OnSurface))
    applyHtmlTag(src, "u", SpanStyle(textDecoration = TextDecoration.Underline))
    applyHashtags(src)
    applyWikiLinks(src)
    applyWebLinks(src)
    applyBareUrls(src)
}.toAnnotatedString()

class MarkdownVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(styleMarkdown(text.text), OffsetMapping.Identity)
}

/**
 * Real renderer: strips the markdown markers and applies styles to the inner text.
 * Used by RENDER mode in the editor. Not offset-preserving — there is no cursor here.
 *
 * Handles per-line: # headings, - / * bullets, N. ordered items, > blockquotes.
 * Handles inline: **bold**, __bold__, *italic*, _italic_, ~~strike~~, `code`, <u>...</u>.
 */
fun renderMarkdown(src: String, onWikiLink: ((String) -> Unit)? = null): AnnotatedString =
    AnnotatedString.Builder().apply {
        val lines = src.split('\n')
        var firstOut = true
        var inCodeFence = false
        for (raw in lines) {
            if (raw.trimStart().startsWith("```")) {
                inCodeFence = !inCodeFence
                // Don't emit fence markers — content flows seamlessly
                continue
            }
            if (!firstOut) append('\n')
            firstOut = false
            if (inCodeFence) {
                val start = length
                append(raw)
                addStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = MdColors.SurfaceHi2,
                        fontSize = 13.sp),
                    start, length,
                )
            } else {
                renderLine(raw, onWikiLink)
            }
        }
    }.toAnnotatedString()

private fun AnnotatedString.Builder.renderLine(line: String, onWikiLink: ((String) -> Unit)? = null) {
    // Heading: # … ###### …
    var level = 0
    while (level < 6 && level < line.length && line[level] == '#') level++
    if (level > 0 && level < line.length && line[level] == ' ') {
        val rest = line.substring(level + 1)
        val start = length
        renderInline(rest, onWikiLink)
        val end = length
        val size = when (level) {
            1 -> 26.sp; 2 -> 22.sp; 3 -> 19.sp; 4 -> 17.sp; 5 -> 16.sp; else -> 15.sp
        }
        addStyle(
            SpanStyle(
                fontWeight = FontWeight.Bold, fontSize = size, color = MdColors.OnSurface
            ),
            start, end,
        )
        return
    }
    // Blockquote
    if (line.startsWith("> ")) {
        val start = length
        append("│ ")
        addStyle(SpanStyle(color = MdColors.Accent), start, start + 1)
        val inStart = length
        renderInline(line.substring(2), onWikiLink)
        addStyle(SpanStyle(color = MdColors.OnSurfaceDim), inStart, length)
        return
    }
    // Bullet list
    if (line.length >= 2 && (line[0] == '-' || line[0] == '*') && line[1] == ' ') {
        val markerStart = length
        append("•  ")
        addStyle(
            SpanStyle(color = MdColors.Accent, fontWeight = FontWeight.Bold),
            markerStart, markerStart + 1,
        )
        renderInline(line.substring(2), onWikiLink)
        return
    }
    // Ordered list
    var k = 0
    while (k < line.length && line[k].isDigit()) k++
    if (k > 0 && k + 1 < line.length && line[k] == '.' && line[k + 1] == ' ') {
        val markerStart = length
        append(line.substring(0, k)); append(".  ")
        addStyle(
            androidx.compose.ui.text.SpanStyle(color = MdColors.Accent, fontWeight = FontWeight.Medium),
            markerStart, length,
        )
        renderInline(line.substring(k + 2), onWikiLink)
        return
    }
    // Horizontal rule
    if (line == "---" || line == "***") {
        val start = length
        append("———")
        addStyle(SpanStyle(color = MdColors.OnSurfaceFaint), start, length)
        return
    }
    renderInline(line, onWikiLink)
}

private fun AnnotatedString.Builder.renderInline(line: String, onWikiLink: ((String) -> Unit)? = null) {
    var i = 0
    while (i < line.length) {
        when {
            line.startsWith("[[", i) ->
                consumeWiki(line, i, onWikiLink)?.let { i = it } ?: run { append(line[i]); i++ }
            line[i] == '[' ->
                consumeWebLink(line, i)?.let { i = it } ?: run { append(line[i]); i++ }
            line.startsWith("https://", i) || line.startsWith("http://", i) ->
                consumeBareUrl(line, i)?.let { i = it } ?: run { append(line[i]); i++ }
            line[i] == '#' && (i == 0 || !line[i - 1].let { it.isLetterOrDigit() || it == '_' }) ->
                consumeHashtag(line, i)?.let { i = it } ?: run { append(line[i]); i++ }
            line.startsWith("**", i) -> consumePair(line, i, "**",
                SpanStyle(fontWeight = FontWeight.Bold)
            )?.let { i = it } ?: run { append(line[i]); i++ }
            line.startsWith("__", i) -> consumePair(line, i, "__",
                SpanStyle(fontWeight = FontWeight.Bold)
            )?.let { i = it } ?: run { append(line[i]); i++ }
            line.startsWith("~~", i) -> consumePair(line, i, "~~",
                SpanStyle(textDecoration = TextDecoration.LineThrough)
            )?.let { i = it } ?: run { append(line[i]); i++ }
            line[i] == '*' && (i + 1 >= line.length || line[i + 1] != '*') ->
                consumePair(line, i, "*",
                    SpanStyle(fontStyle = FontStyle.Italic)
                )?.let { i = it } ?: run { append(line[i]); i++ }
            line[i] == '_' && (i + 1 >= line.length || line[i + 1] != '_') ->
                consumePair(line, i, "_",
                    SpanStyle(fontStyle = FontStyle.Italic)
                )?.let { i = it } ?: run { append(line[i]); i++ }
            line[i] == '`' ->
                consumePair(line, i, "`",
                    SpanStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        background = MdColors.SurfaceHi2, color = MdColors.OnSurface
                    )
                )?.let { i = it } ?: run { append(line[i]); i++ }
            line.startsWith("<u>", i, ignoreCase = true) ->
                consumeTag(line, i, "u",
                    SpanStyle(textDecoration = TextDecoration.Underline)
                )?.let { i = it } ?: run { append(line[i]); i++ }
            else -> { append(line[i]); i++ }
        }
    }
}

private fun AnnotatedString.Builder.consumeWiki(line: String, i: Int, onWikiLink: ((String) -> Unit)? = null): Int? {
    val close = line.indexOf("]]", i + 2)
    if (close < 0) return null
    val target = line.substring(i + 2, close)
    if (target.isEmpty()) return null
    val start = length
    if (onWikiLink != null) {
        pushLink(androidx.compose.ui.text.LinkAnnotation.Clickable("wiki") { onWikiLink(target) })
        append(target)
        pop()
    } else append(target)
    addStyle(
        SpanStyle(
            color = MdColors.Accent, textDecoration = TextDecoration.Underline,
        ),
        start, length,
    )
    return close + 2
}

private fun AnnotatedString.Builder.consumeWebLink(line: String, i: Int): Int? {
    val cb = line.indexOf(']', i + 1)
    if (cb < 0 || cb + 1 >= line.length || line[cb + 1] != '(') return null
    val cp = line.indexOf(')', cb + 2)
    if (cp < 0) return null
    val text = line.substring(i + 1, cb)
    val url = line.substring(cb + 2, cp)
    if (text.isEmpty() || url.isEmpty()) return null
    val start = length
    pushLink(androidx.compose.ui.text.LinkAnnotation.Url(url))
    append(text)
    pop()
    addStyle(
        SpanStyle(
            color = MdColors.Accent, textDecoration = TextDecoration.Underline,
        ),
        start, length,
    )
    return cp + 1
}

private fun AnnotatedString.Builder.consumeHashtag(line: String, i: Int): Int? {
    var end = i + 1
    while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_' || line[end] == '-' || line[end] == '/')) end++
    if (end == i + 1) return null
    val tag = line.substring(i, end)
    if (tag.substring(1).all { it.isDigit() }) return null
    val tagName = tag.substring(1).lowercase()
    val color = MdColors.hashtagColor(tagName)
    val start = length
    append(tag)
    addStyle(
        SpanStyle(color = color, background = color.copy(alpha = 0.30f), fontWeight = FontWeight.SemiBold),
        start, length,
    )
    return end
}

private fun AnnotatedString.Builder.consumePair(
    line: String, i: Int, marker: String, style: SpanStyle,
): Int? {
    val openEnd = i + marker.length
    if (openEnd >= line.length) return null
    val closeAt = line.indexOf(marker, openEnd)
    if (closeAt < 0) return null
    val inner = line.substring(openEnd, closeAt)
    if (inner.isEmpty()) return null
    val start = length
    append(inner)
    addStyle(style, start, length)
    return closeAt + marker.length
}

private fun AnnotatedString.Builder.consumeTag(
    line: String, i: Int, tag: String, style: SpanStyle,
): Int? {
    val open = "<$tag>"; val close = "</$tag>"
    val openEnd = i + open.length
    if (openEnd >= line.length) return null
    val closeAt = line.indexOf(close, openEnd, ignoreCase = true)
    if (closeAt < 0) return null
    val inner = line.substring(openEnd, closeAt)
    val start = length
    append(inner)
    addStyle(style, start, length)
    return closeAt + close.length
}

/**
 * Locate a YAML-style frontmatter block at the very start of the document:
 *   ---\nkey: value\nkey: value\n---\n
 * Returns the end-exclusive offset of the closing "---\n", or -1 if absent.
 * Only "---\n" is recognised — leading whitespace or BOM is intentionally not.
 */
internal fun frontmatterEnd(src: String): Int {
    if (!src.startsWith("---\n")) return -1
    val close = src.indexOf("\n---", startIndex = 4)
    if (close < 0) return -1
    val afterMarker = close + 4
    // Allow trailing \n after closing marker, or EOF.
    return when {
        afterMarker == src.length -> afterMarker
        src[afterMarker] == '\n' -> afterMarker + 1
        else -> -1
    }
}

private fun AnnotatedString.Builder.applyFrontmatter(src: String) {
    val end = frontmatterEnd(src)
    if (end < 0) return
    // Whole block: dimmed monospace so it reads as metadata, not prose.
    addStyle(
        SpanStyle(
            color = MdColors.OnSurfaceFaint,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        ),
        0, end,
    )
    // The two "---" fences in a fainter still tone.
    addStyle(SpanStyle(color = MdColors.OnSurfaceFaint.copy(alpha = 0.6f)), 0, 3)
    val secondFence = src.lastIndexOf("---", end - 1)
    if (secondFence in 0 until end) {
        addStyle(SpanStyle(color = MdColors.OnSurfaceFaint.copy(alpha = 0.6f)), secondFence, secondFence + 3)
    }
    // Per-line key/value colouring between the fences.
    var lineStart = 4 // past "---\n"
    while (lineStart < end) {
        val lineEnd = src.indexOf('\n', lineStart).let { if (it < 0 || it > end) end else it }
        val line = src.substring(lineStart, lineEnd)
        if (!line.startsWith("---")) {
            val colon = line.indexOf(':')
            if (colon > 0) {
                addStyle(
                    SpanStyle(color = MdColors.LabelChipFg, fontWeight = FontWeight.SemiBold),
                    lineStart, lineStart + colon,
                )
                addStyle(
                    SpanStyle(color = MdColors.OnSurfaceDim),
                    lineStart + colon + 1, lineEnd,
                )
            }
        }
        lineStart = lineEnd + 1
    }
}

private fun AnnotatedString.Builder.applyHeadings(src: String) {
    var i = 0
    while (i < src.length) {
        val nl = src.indexOf('\n', i).let { if (it == -1) src.length else it }
        if (i == 0 || src[i - 1] == '\n') {
            var level = 0
            var j = i
            while (j < nl && src[j] == '#' && level < 6) { level++; j++ }
            if (level > 0 && j < nl && src[j] == ' ') {
                addStyle(SpanStyle(color = MdColors.OnSurfaceDim), i, j + 1)
                val (weight, size) = when (level) {
                    1 -> FontWeight.Bold to 24.sp
                    2 -> FontWeight.SemiBold to 21.sp
                    3 -> FontWeight.SemiBold to 19.sp
                    else -> FontWeight.SemiBold to 17.sp
                }
                addStyle(SpanStyle(fontWeight = weight, fontSize = size, color = MdColors.OnSurface), j + 1, nl)
            }
        }
        i = nl + 1
    }
}

private fun AnnotatedString.Builder.applyLists(src: String) {
    var i = 0
    while (i < src.length) {
        val nl = src.indexOf('\n', i).let { if (it == -1) src.length else it }
        if (i == 0 || src[i - 1] == '\n') {
            if (i + 1 < nl && (src[i] == '-' || src[i] == '*') && src[i + 1] == ' ') {
                addStyle(SpanStyle(color = MdColors.Accent, fontWeight = FontWeight.Bold), i, i + 1)
            }
            var k = i
            while (k < nl && src[k].isDigit()) k++
            if (k > i && k + 1 < nl && src[k] == '.' && src[k + 1] == ' ') {
                addStyle(SpanStyle(color = MdColors.Accent, fontWeight = FontWeight.Medium), i, k + 1)
            }
        }
        i = nl + 1
    }
}

private fun AnnotatedString.Builder.applyInlinePairs(src: String, marker: String, inner: SpanStyle) {
    val markerLen = marker.length
    var i = 0
    while (true) {
        val a = src.indexOf(marker, i)
        if (a == -1) return
        if (marker == "*" && (a + 1 < src.length && src[a + 1] == '*')) { i = a + 2; continue }
        if (marker == "_" && (a + 1 < src.length && src[a + 1] == '_')) { i = a + 2; continue }
        // Search for the closing marker only within the same line — inline formatting
        // must not span across newlines or it eats the rest of the document.
        val lineEnd = src.indexOf('\n', a).let { if (it == -1) src.length else it }
        val b = src.indexOf(marker, a + markerLen).takeIf { it != -1 && it < lineEnd }
        if (b == null) { i = lineEnd + 1; continue }
        if (b - a - markerLen <= 0) { i = b + markerLen; continue }
        addStyle(SpanStyle(color = MdColors.OnSurfaceFaint), a, a + markerLen)
        addStyle(inner, a + markerLen, b)
        addStyle(SpanStyle(color = MdColors.OnSurfaceFaint), b, b + markerLen)
        i = b + markerLen
    }
}

private fun isHashtagChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-' || c == '/'

private fun AnnotatedString.Builder.applyHashtags(src: String) {
    var i = 0
    while (i < src.length) {
        if (src[i] != '#') { i++; continue }
        val prev = if (i == 0) ' ' else src[i - 1]
        // skip heading lines: '#' at line start followed by space or more '#'s then space
        if (prev == '\n' || i == 0) {
            var j = i
            while (j < src.length && j - i < 6 && src[j] == '#') j++
            if (j < src.length && src[j] == ' ') { i = j; continue }
        }
        // tag must follow a non-word boundary
        if (isHashtagChar(prev)) { i++; continue }
        val start = i + 1
        var end = start
        while (end < src.length && isHashtagChar(src[end])) end++
        if (end > start && !src.substring(start, end).all { it.isDigit() }) {
            val tagName = src.substring(start, end).lowercase()
            val color = MdColors.hashtagColor(tagName)
            addStyle(
                SpanStyle(color = color, background = color.copy(alpha = 0.30f), fontWeight = FontWeight.SemiBold),
                i, end,
            )
        }
        i = end.coerceAtLeast(i + 1)
    }
}

private fun AnnotatedString.Builder.applyWikiLinks(src: String) {
    var i = 0
    while (true) {
        val a = src.indexOf("[[", i)
        if (a == -1) return
        val b = src.indexOf("]]", a + 2)
        if (b == -1) return
        addStyle(SpanStyle(color = MdColors.OnSurfaceFaint), a, a + 2)
        addStyle(
            SpanStyle(color = MdColors.Accent, textDecoration = TextDecoration.Underline),
            a + 2, b,
        )
        addStyle(SpanStyle(color = MdColors.OnSurfaceFaint), b, b + 2)
        i = b + 2
    }
}

private fun AnnotatedString.Builder.applyWebLinks(src: String) {
    // Matches [text](url) — naive, doesn't handle nested brackets but that's fine here.
    var i = 0
    while (i < src.length) {
        val ob = src.indexOf('[', i)
        if (ob == -1) return
        // exclude wiki-link doubled brackets
        if (ob + 1 < src.length && src[ob + 1] == '[') { i = ob + 2; continue }
        val cb = src.indexOf(']', ob + 1)
        if (cb == -1) return
        if (cb + 1 >= src.length || src[cb + 1] != '(') { i = cb + 1; continue }
        val cp = src.indexOf(')', cb + 2)
        if (cp == -1) return
        addStyle(SpanStyle(color = MdColors.OnSurfaceFaint), ob, ob + 1)
        addStyle(SpanStyle(color = MdColors.Accent, textDecoration = TextDecoration.Underline), ob + 1, cb)
        addStyle(SpanStyle(color = MdColors.OnSurfaceFaint), cb, cp + 1)
        i = cp + 1
    }
}

private fun AnnotatedString.Builder.consumeBareUrl(line: String, i: Int): Int? {
    if (!line.startsWith("https://", i) && !line.startsWith("http://", i)) return null
    var end = i
    while (end < line.length && !line[end].isWhitespace() && line[end] != '<' && line[end] != '>') end++
    while (end > i && line[end - 1] in listOf('.', ',', ';', ':', '!', '?')) end--
    if (end < i + 8) return null
    val url = line.substring(i, end)
    val start = length
    pushLink(androidx.compose.ui.text.LinkAnnotation.Url(url))
    append(url)
    pop()
    addStyle(SpanStyle(color = MdColors.Accent, textDecoration = TextDecoration.Underline), start, length)
    return end
}

private fun AnnotatedString.Builder.applyBareUrls(src: String) {
    val urlRegex = Regex("""https?://\S+""")
    for (m in urlRegex.findAll(src)) {
        var end = m.range.last + 1
        while (end > m.range.first + 8 && src[end - 1] in listOf('.', ',', ';', ':', '!', '?')) end--
        addStyle(SpanStyle(color = MdColors.Accent, textDecoration = TextDecoration.Underline), m.range.first, end)
    }
}

private fun AnnotatedString.Builder.applyHtmlTag(src: String, tag: String, inner: SpanStyle) {
    val open = "<$tag>"; val close = "</$tag>"
    var i = 0
    while (true) {
        val a = src.indexOf(open, i, ignoreCase = true)
        if (a == -1) return
        val b = src.indexOf(close, a + open.length, ignoreCase = true)
        if (b == -1) return
        addStyle(SpanStyle(color = MdColors.OnSurfaceFaint), a, a + open.length)
        addStyle(inner, a + open.length, b)
        addStyle(SpanStyle(color = MdColors.OnSurfaceFaint), b, b + close.length)
        i = b + close.length
    }
}
