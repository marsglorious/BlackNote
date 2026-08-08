package com.marsglorious.blacknote.data

/**
 * Note metadata — the shape the search index and metadata extractor deal in. Formerly a
 * UniFFI dictionary generated from the Rust core; now a plain Kotlin data class.
 */
data class NoteMeta(
    val path: String,
    val parent: String,
    val title: String,
    val preview: String,
    val modifiedMillis: Long,
    val createdMillis: Long,
    val tags: List<String>,
    val label: String?,
)

/** Inline/block formatting actions the editor toolbar can apply. */
enum class FormatKind {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKE,
    CODE,
    BULLET_LIST,
    ORDERED_LIST,
}
