package com.marsglorious.blacknote

import com.marsglorious.blacknote.data.NoteMeta
import com.marsglorious.blacknote.data.SearchIndex
import com.marsglorious.blacknote.data.extractMeta
import com.marsglorious.blacknote.data.searchNotes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for the Kotlin core that replaced the Rust/UniFFI crate: metadata extraction,
 * in-memory search, and the SQLite-backed index.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreLogicTest {

    // ── extractMeta (was meta.rs) ──────────────────────────────────────────

    @Test
    fun extractMeta_readsFrontmatterTitleTagsLabelAndCreated() {
        val text = """
            ---
            title: My Trip
            tags: [travel, "japan"]
            label: Journal
            created: 2026-03-15
            ---
            First body line.
            Second line.
        """.trimIndent()
        val m = extractMeta("/n/a.md", "/n", "a.md", text, 999L)
        assertEquals("My Trip", m.title)
        assertEquals(listOf("japan", "travel"), m.tags) // sorted + deduped
        assertEquals("Journal", m.label)
        // 2026-03-15 UTC midnight in epoch millis.
        assertEquals(1_773_532_800_000L, m.createdMillis)
        assertTrue(m.preview.startsWith("First body line."))
    }

    @Test
    fun extractMeta_modifiedOverrideWinsOverFileMtime() {
        val text = "---\nmodified: 2020-01-01\n---\nbody"
        val m = extractMeta("/n/a.md", "/n", "a.md", text, 999L)
        assertEquals(1_577_836_800_000L, m.modifiedMillis) // not 999
    }

    @Test
    fun extractMeta_titleFallsBackToHeadingThenFirstLineThenFileName() {
        assertEquals("Heading", extractMeta("/p/x.md", "/p", "x.md", "# Heading\nbody", 1).title)
        assertEquals("Just text", extractMeta("/p/x.md", "/p", "x.md", "Just text\nmore", 1).title)
        assertEquals("myfile", extractMeta("/p/myfile.md", "/p", "myfile.md", "", 1).title)
        assertEquals("Untitled", extractMeta("/p/.md", "/p", ".md", "", 1).title)
    }

    @Test
    fun extractMeta_pullsHashtagsFromBodyButNotHeadings() {
        val m = extractMeta("/p/x.md", "/p", "x.md", "# Heading not a tag\ntext #alpha and #beta_1", 1)
        assertTrue("has alpha", m.tags.contains("alpha"))
        assertTrue("has beta_1", m.tags.contains("beta_1"))
        assertTrue("heading word not a tag", !m.tags.contains("Heading"))
    }

    // ── searchNotes (was search.rs) ────────────────────────────────────────

    private fun meta(title: String, preview: String = "", label: String? = null, mtime: Long = 0) =
        NoteMeta("/p/$title.md", "/p", title, preview, mtime, mtime, emptyList(), label)

    @Test
    fun searchNotes_ranksTitleOverLabelOverPreview() {
        val notes = listOf(
            meta("apple pie", preview = "dessert"),
            meta("dessert list", preview = "includes apple"),
            meta("unrelated", preview = "nothing here"),
        )
        val res = searchNotes(notes, "apple", 10u).map { it.title }
        assertEquals(listOf("apple pie", "dessert list"), res) // title hit ranks first
    }

    @Test
    fun searchNotes_emptyQueryReturnsNewestFirstWithinLimit() {
        val notes = listOf(meta("a", mtime = 100), meta("b", mtime = 300), meta("c", mtime = 200))
        val res = searchNotes(notes, "  ", 2u).map { it.title }
        assertEquals(listOf("b", "c"), res)
    }

    // ── SearchIndex (was index.rs, now SQLite) ─────────────────────────────

    private val dbFile: File by lazy {
        File.createTempFile("bn-index-${System.nanoTime()}", ".db").also { it.delete() }
    }

    @After fun cleanup() { dbFile.delete() }

    @Test
    fun searchIndex_upsertAllSortedQueryDeleteRetain() {
        val idx = SearchIndex(dbFile.absolutePath)
        idx.upsert("/n/old.md", "/n", "Old Note", "boring body", null, listOf("x"), 100L, 0L)
        idx.upsert("/n/new.md", "/n", "New Note", "zanzibar deep in body", "Src", listOf("y"), 300L, 0L)

        // allSorted: newest (by modified, created=0) first, preview from body prefix.
        val all = idx.allSorted(10u)
        assertEquals(listOf("/n/new.md", "/n/old.md"), all.map { it.path })

        // query hits body-only text and returns it.
        val hits = idx.query("zanzibar", 10u)
        assertEquals(1, hits.size)
        assertEquals("/n/new.md", hits[0].path)

        // upsert replaces on same path (no duplicate).
        idx.upsert("/n/old.md", "/n", "Old Note v2", "updated", null, emptyList(), 400L, 0L)
        assertEquals(2, idx.allSorted(10u).size)
        assertEquals("/n/old.md", idx.allSorted(10u)[0].path) // now newest

        idx.delete("/n/old.md")
        assertEquals(listOf("/n/new.md"), idx.allSorted(10u).map { it.path })

        // retain drops anything not listed.
        idx.upsert("/n/keep.md", "/n", "Keep", "k", null, emptyList(), 500L, 0L)
        idx.retain(listOf("/n/keep.md"))
        assertEquals(listOf("/n/keep.md"), idx.allSorted(10u).map { it.path })

        idx.retain(emptyList())
        assertTrue(idx.allSorted(10u).isEmpty())
    }
}
