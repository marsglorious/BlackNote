package com.marsglorious.blacknote.data

/**
 * In-memory note search used when the persistent index is unavailable (Kotlin port of the
 * former Rust `search.rs`). Scores each note by term hits — title weighted highest, then
 * label, then preview — and returns the best [limit] matches, newest first on ties.
 */
fun searchNotes(notes: List<NoteMeta>, query: String, limit: UInt): List<NoteMeta> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) {
        return notes.sortedByDescending { it.modifiedMillis }.take(limit.toInt())
    }
    val terms = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return notes
        .mapNotNull { n ->
            val titleL = n.title.lowercase()
            val previewL = n.preview.lowercase()
            val labelL = (n.label ?: "").lowercase()
            var score = 0L
            for (term in terms) {
                if (titleL.contains(term)) score += 100
                if (labelL.contains(term)) score += 60
                if (previewL.contains(term)) score += 20
            }
            if (score == 0L) null else score to n
        }
        .sortedWith(
            compareByDescending<Pair<Long, NoteMeta>> { it.first }
                .thenByDescending { it.second.modifiedMillis }
        )
        .take(limit.toInt())
        .map { it.second }
}
