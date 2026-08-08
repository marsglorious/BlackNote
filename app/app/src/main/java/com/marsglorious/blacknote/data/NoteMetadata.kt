package com.marsglorious.blacknote.data

/**
 * Derive a note's metadata (title, tags, label, dates, preview) from its raw text.
 *
 * Kotlin port of the former Rust `meta.rs`. Recognises YAML-ish front-matter fenced by
 * `---`, Obsidian-style `#hashtags` in the body, and falls back to the first heading /
 * first line / file name for the title. Behaviour is intentionally identical to the Rust
 * version so existing notes parse the same way.
 */
fun extractMeta(
    path: String,
    parent: String,
    fileName: String,
    text: String,
    modifiedMillis: Long,
): NoteMeta {
    var title = ""
    var label: String? = null
    val tags = mutableListOf<String>()
    var bodyStart = 0
    var createdMillis = 0L
    var modifiedOverride: Long? = null

    val trimmed = text.trimStart()
    val leading = text.length - trimmed.length
    if (trimmed.startsWith("---")) {
        val end = trimmed.substring(3).indexOf("---")
        if (end >= 0) {
            val yaml = trimmed.substring(3, 3 + end)
            for (rawLine in yaml.lines()) {
                val line = rawLine.trim()
                val titleV = stripKey(line, "title")
                val tagsV = stripKey(line, "tags")
                val labelV = stripKey(line, "label")
                val createdV = stripKey(line, "created")
                val modifiedV = stripKey(line, "modified")
                val sourceV = stripKey(line, "source")
                when {
                    titleV != null -> title = titleV.trim('"')
                    tagsV != null -> parseTagArray(tagsV, tags)
                    labelV != null -> label = labelV.trim('"')
                    createdV != null -> parseIsoDate(createdV)?.let { createdMillis = it }
                    modifiedV != null -> parseIsoDate(modifiedV)?.let { modifiedOverride = it }
                    // Surface 'source' as the chip label when no explicit label was set.
                    sourceV != null -> if (label == null) {
                        val v = sourceV.trim('"')
                        if (v.isNotEmpty()) label = v
                    }
                }
            }
            bodyStart = leading + 3 + end + 3
        }
    }

    val bodyFull = if (bodyStart <= text.length) text.substring(bodyStart) else text
    val body = bodyFull.trimStart()

    if (title.isEmpty()) {
        for (line in body.lines()) {
            val ls = line.trimStart()
            if (ls.startsWith("#")) {
                val t = ls.trimStart('#').trim()
                if (t.isNotEmpty()) { title = t; break }
            } else if (line.trim().isNotEmpty()) {
                title = line.trim().take(80)
                break
            }
        }
    }
    if (title.isEmpty()) {
        title = fileName.removeSuffix(".md")
        if (title.isEmpty()) title = "Untitled"
    }

    extractHashtags(body, tags)
    val cleanTags = tags.toSortedSet().toList()

    val preview = body.lines()
        .filter { it.trim().isNotEmpty() && !it.trimStart().startsWith("#") }
        .take(3)
        .joinToString(" ")
        .take(180)

    return NoteMeta(
        path = path,
        parent = parent,
        title = title,
        preview = preview,
        modifiedMillis = modifiedOverride ?: modifiedMillis,
        createdMillis = createdMillis,
        tags = cleanTags,
        label = label,
    )
}

private fun stripKey(line: String, key: String): String? =
    if (line.startsWith("$key:")) line.substring(key.length + 1).trim() else null

private fun parseTagArray(v: String, out: MutableList<String>) {
    val inner = v.trim().trim('[').trim(']')
    for (raw in inner.split(',')) {
        val t = raw.trim().trim('"').trim('\'')
        if (t.isNotEmpty()) out.add(t)
    }
}

/** Parse a plain `YYYY-MM-DD` date to epoch millis (UTC midnight). Returns null if malformed. */
private fun parseIsoDate(s: String): Long? {
    val v = s.trim().trim('"').trim('\'')
    val parts = v.split('-')
    if (parts.size != 3) return null
    val year = parts[0].toLongOrNull() ?: return null
    val month = parts[1].toLongOrNull() ?: return null
    val day = parts[2].toLongOrNull() ?: return null
    if (month !in 1..12 || day !in 1..31 || year < 1970 || year > 2999) return null
    var days = 0L
    for (y in 1970 until year) days += if (isLeap(y)) 366 else 365
    val dim = longArrayOf(31, if (isLeap(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    for (m in 0 until (month - 1).toInt()) days += dim[m]
    days += day - 1
    return days * 86_400_000L
}

private fun isLeap(y: Long): Boolean = (y % 4 == 0L && y % 100 != 0L) || (y % 400 == 0L)

private fun extractHashtags(body: String, out: MutableList<String>) {
    for (line in body.lines()) {
        val stripped = line.trimStart()
        // Heading line: 1–6 '#' followed by a space — skip so headings aren't read as tags.
        var hIdx = 0
        while (hIdx < 6 && hIdx < stripped.length && stripped[hIdx] == '#') hIdx++
        if (hIdx in 1 until stripped.length && stripped[hIdx] == ' ') continue

        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '#' && (i == 0 || !isWordChar(line[i - 1]))) {
                val start = i + 1
                var endIdx = start
                while (endIdx < line.length && isWordChar(line[endIdx])) endIdx++
                if (endIdx > start) {
                    val tag = line.substring(start, endIdx)
                    if (!tag.all { it in '0'..'9' }) out.add(tag)
                }
                i = endIdx
            } else {
                i++
            }
        }
    }
}

private fun isWordChar(c: Char): Boolean =
    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '/'
