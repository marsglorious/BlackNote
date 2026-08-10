package com.marsglorious.blacknote.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * Persistent note index + startup cache, backed by Android's bundled SQLite. Kotlin port of
 * the former Rust `index.rs` (which used rusqlite + FTS5). Search is scored in Kotlin over
 * the stored bodies rather than via FTS, so there are no SQLite-version / FTS availability
 * concerns across Android releases — for a personal note corpus this is instant.
 *
 * All access is serialised (`@Synchronized`); SQLiteDatabase is itself thread-safe, but this
 * mirrors the single-connection mutex the Rust version used and keeps [retain] atomic.
 */
class SearchIndex(dbPath: String) {

    private val db: SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(dbPath, null)

    init {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notes (
                path     TEXT PRIMARY KEY,
                parent   TEXT,
                title    TEXT,
                body     TEXT,
                label    TEXT,
                tags     TEXT,
                modified INTEGER,
                created  INTEGER
            )
            """.trimIndent()
        )
    }

    @Synchronized
    fun upsert(
        path: String,
        parent: String,
        title: String,
        body: String,
        label: String?,
        tags: List<String>,
        modifiedMillis: Long,
        createdMillis: Long,
    ) {
        val values = ContentValues().apply {
            put("path", path)
            put("parent", parent)
            put("title", title)
            put("body", body)
            put("label", label)
            put("tags", tags.joinToString(" "))
            put("modified", modifiedMillis)
            put("created", createdMillis)
        }
        db.replace("notes", null, values) // INSERT OR REPLACE keyed on path
    }

    @Synchronized
    fun delete(path: String) {
        db.delete("notes", "path = ?", arrayOf(path))
    }

    /** Cache read for instant startup: newest first (front-matter `created` wins, else mtime). */
    @Synchronized
    fun allSorted(limit: UInt): List<NoteMeta> {
        db.rawQuery(
            "SELECT path, parent, title, substr(body, 1, 180), modified, created, tags, label " +
                "FROM notes ORDER BY COALESCE(NULLIF(created, 0), modified) DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            return buildList { while (c.moveToNext()) add(readMeta(c)) }
        }
    }

    /** Full-text-ish search: score title/label/body term hits in Kotlin, best matches first. */
    @Synchronized
    fun query(q: String, limit: UInt): List<NoteMeta> {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) return allSorted(limit)
        val terms = trimmed.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

        val scored = ArrayList<Pair<Long, NoteMeta>>()
        db.rawQuery(
            "SELECT path, parent, title, body, modified, created, tags, label FROM notes",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val body = c.getString(3) ?: ""
                val title = c.getString(2) ?: ""
                val label = c.getString(7)
                val titleL = title.lowercase()
                val bodyL = body.lowercase()
                val labelL = (label ?: "").lowercase()
                var score = 0L
                for (t in terms) {
                    if (titleL.contains(t)) score += 100
                    if (labelL.contains(t)) score += 60
                    if (bodyL.contains(t)) score += 20
                }
                if (score > 0L) {
                    scored += score to NoteMeta(
                        path = c.getString(0) ?: "",
                        parent = c.getString(1) ?: "",
                        title = title,
                        preview = body.take(180),
                        modifiedMillis = c.getLong(4),
                        createdMillis = c.getLong(5),
                        tags = splitTags(c.getString(6)),
                        label = label,
                    )
                }
            }
        }
        return scored
            .sortedWith(
                compareByDescending<Pair<Long, NoteMeta>> { it.first }
                    .thenByDescending { it.second.modifiedMillis }
            )
            .take(limit.toInt())
            .map { it.second }
    }

    /** Drop every note whose path isn't in [alivePaths] — keeps the index in sync with disk. */
    @Synchronized
    fun retain(alivePaths: List<String>) {
        db.execSQL("CREATE TEMP TABLE IF NOT EXISTS keep(path TEXT PRIMARY KEY)")
        db.execSQL("DELETE FROM keep")
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("INSERT OR IGNORE INTO keep(path) VALUES (?)")
            for (p in alivePaths) {
                stmt.bindString(1, p)
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.delete("notes", "path NOT IN (SELECT path FROM keep)", null)
    }

    // Column order: path, parent, title, preview, modified, created, tags, label
    private fun readMeta(c: Cursor) = NoteMeta(
        path = c.getString(0) ?: "",
        parent = c.getString(1) ?: "",
        title = c.getString(2) ?: "",
        preview = c.getString(3) ?: "",
        modifiedMillis = c.getLong(4),
        createdMillis = c.getLong(5),
        tags = splitTags(c.getString(6)),
        label = c.getString(7),
    )

    private fun splitTags(joined: String?): List<String> =
        joined?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList()
}
