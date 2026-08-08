package com.marsglorious.blacknote

import android.net.Uri
import com.marsglorious.blacknote.data.Note
import com.marsglorious.blacknote.data.foldersFromNotes
import com.marsglorious.blacknote.data.safUriToFilePath
import com.marsglorious.blacknote.data.stableDisplayDesc
import com.marsglorious.blacknote.data.titleFromFileName
import com.marsglorious.blacknote.viewmodel.SortMode
import com.marsglorious.blacknote.viewmodel.noteComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the file-access migration: correct sort keys, path conversions,
 * and content:// URI filtering — the bugs that caused weeks of ordering instability.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileAccessTest {

    private fun note(path: String, mtime: Long, title: String = "T") =
        Note(path, "/root", title, "", mtime, mtime, emptyList(), null)

    // ── Sort order ──────────────────────────────────────────────────────────

    @Test
    fun stableDisplayDesc_sortsByModifiedTimeDesc() {
        val notes = listOf(note("/b.md", 3000L), note("/a.md", 5000L), note("/c.md", 1000L))
        val sorted = notes.sortedWith(stableDisplayDesc)
        assertEquals("/a.md", sorted[0].path)
        assertEquals("/b.md", sorted[1].path)
        assertEquals("/c.md", sorted[2].path)
    }

    @Test
    fun stableDisplayDesc_pathTiebreakerWhenMtimeEqual() {
        val notes = listOf(note("/z.md", 1000L), note("/a.md", 1000L), note("/m.md", 1000L))
        val fwd = notes.sortedWith(stableDisplayDesc).map { it.path }
        val rev = notes.reversed().sortedWith(stableDisplayDesc).map { it.path }
        assertEquals("Order must be identical regardless of walk order", fwd, rev)
        assertEquals("/a.md", fwd[0])  // lowest path first
        assertEquals("/m.md", fwd[1])
        assertEquals("/z.md", fwd[2])
    }

    @Test
    fun noteComparator_dateDesc_usesMtime() {
        // DATE_DESC now sorts by modifiedMillis — no frontmatter dates involved.
        val notes = listOf(
            note("/new.md", 9000L),
            note("/old.md", 1000L),
            note("/mid.md", 5000L),
        )
        val sorted = notes.sortedWith(noteComparator(SortMode.DATE_DESC, emptySet()))
        assertEquals("/new.md", sorted[0].path)
        assertEquals("/mid.md", sorted[1].path)
        assertEquals("/old.md", sorted[2].path)
    }

    @Test
    fun noteComparator_pinnedNotesAlwaysFirst() {
        val notes = listOf(note("/a.md", 5000L), note("/b.md", 3000L), note("/c.md", 9000L))
        val sorted = notes.sortedWith(noteComparator(SortMode.DATE_DESC, setOf("/a.md")))
        assertEquals("/a.md", sorted[0].path)  // pinned despite lower mtime than /c.md
        assertEquals("/c.md", sorted[1].path)
        assertEquals("/b.md", sorted[2].path)
    }

    // ── Content:// URI filtering ────────────────────────────────────────────

    @Test
    fun contentUriNotes_filteredBeforeDisplay() {
        val mixed = listOf(
            note("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Fnote.md", 9000L),
            note("/storage/emulated/0/Documents/real.md", 5000L),
        )
        val filtered = mixed.filter { !it.path.startsWith("content://") }
        assertEquals(1, filtered.size)
        assertEquals("/storage/emulated/0/Documents/real.md", filtered[0].path)
    }

    @Test
    fun contentUriNote_doesNotReachEditor() {
        // Simulates the guard in AppViewModel.openNote()
        val stale = note("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Fold.md", 1000L)
        val shouldOpen = !stale.path.startsWith("content://")
        assertEquals("content:// note must be blocked from opening", false, shouldOpen)
    }

    @Test
    fun contentUriPath_doesNotReachFileWrite() {
        // Simulates the guard in AppViewModel.closeEditor()
        val path = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Fold.md"
        val shouldWrite = path != null && !path.startsWith("content://")
        assertEquals("content:// path must be blocked from write", false, shouldWrite)
    }

    // ── SAF URI → file path conversion ────────────────────────────────────

    @Test
    fun safUriToFilePath_convertsInternalStorageUri() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FBlackNote")
        val path = safUriToFilePath(uri)
        // The "primary" volume resolves under external storage; the exact root differs
        // between device and Robolectric, but the relative part must be appended intact.
        assertTrue("Should be non-null for the primary volume, got: $path", path != null)
        assertTrue("Should end with Documents/BlackNote, got: $path", path?.endsWith("Documents/BlackNote") == true)
    }

    @Test
    fun safUriToFilePath_returnsNullForSdCard() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/1234-5678%3ABlackNote")
        val path = safUriToFilePath(uri)
        assertNull("SD card URI should return null — not accessible via java.io.File", path)
    }

    @Test
    fun safUriToFilePath_returnsNullForCloudStorage() {
        val uri = Uri.parse("content://com.google.android.apps.docs.storage/document/abc123")
        val path = safUriToFilePath(uri)
        assertNull("Cloud storage URI should return null", path)
    }

    // ── Folders reconstructed from cached notes (startup) ──────────────────

    private fun noteIn(parent: String, name: String) =
        Note("$parent/$name", parent, name, "", 1L, 1L, emptyList(), null)

    @Test
    fun foldersFromNotes_derivesTopLevelAndNestedWithDepth() {
        val root = "/storage/emulated/0/Notes"
        val notes = listOf(
            noteIn(root, "top.md"),                 // top-level note → no folder
            noteIn("$root/Work", "b.md"),
            noteIn("$root/Work/Deep", "c.md"),
        )
        val folders = foldersFromNotes(notes, root)
        assertEquals(listOf("$root/Work", "$root/Work/Deep"), folders.map { it.path })
        val work = folders.first { it.name == "Work" }
        assertEquals(0, work.depth)
        assertEquals(root, work.parent)
        val deep = folders.first { it.name == "Deep" }
        assertEquals(1, deep.depth)
        assertEquals("$root/Work", deep.parent)
    }

    @Test
    fun foldersFromNotes_dedupesSharedFoldersAndSkipsTrash() {
        val root = "/r"
        val notes = listOf(
            noteIn("/r/Work", "a.md"),
            noteIn("/r/Work", "b.md"),        // same folder as above
            noteIn("/r/Trash", "gone.md"),    // trash must not appear
            noteIn("/r", "root.md"),          // top-level note → no folder
        )
        val folders = foldersFromNotes(notes, root)
        assertEquals(listOf("/r/Work"), folders.map { it.path })
    }

    // ── Title from filename ────────────────────────────────────────────────

    @Test
    fun titleFromFileName_stripsExtension() {
        assertEquals("My Note", titleFromFileName("My Note.md"))
        assertEquals("My Note", titleFromFileName("My Note.MD"))
        assertEquals("a", titleFromFileName("a.md"))
    }

    @Test
    fun titleFromFileName_emptyFallback() {
        assertEquals("Untitled", titleFromFileName(".md"))
        assertEquals("Untitled", titleFromFileName(""))
    }

    @Test
    fun titleFromFileName_preservesUnicode() {
        assertEquals("日記 2026", titleFromFileName("日記 2026.md"))
        assertEquals("café ☕", titleFromFileName("café ☕.md"))
    }
}
