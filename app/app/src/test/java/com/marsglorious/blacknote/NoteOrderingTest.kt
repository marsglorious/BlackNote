package com.marsglorious.blacknote

import com.marsglorious.blacknote.data.Note
import com.marsglorious.blacknote.viewmodel.SortMode
import com.marsglorious.blacknote.viewmodel.noteComparator
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for non-deterministic note ordering across app restarts.
 *
 * Root cause: noteComparator compared on a single field. Kotlin's sortedWith is a
 * stable sort, so equal elements preserve their *input* order. The input comes from
 * DocumentFile.listFiles(), whose enumeration order is non-deterministic between boots
 * (FAT32 directory entry order, inode order, provider-internal page order…). Any two
 * notes with the same timestamp therefore shuffle on every start.
 *
 * The fast walk makes this worse: every note is stamped createdMillis = mtime. FAT32/
 * exFAT mtime has 2-second resolution, so notes created within the same 2 seconds are
 * indistinguishable by the primary sort key.
 *
 * Fix: add path as a stable, unique tiebreaker on every comparator so the output order
 * is fully determined by the notes themselves, not by the walk's enumeration order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoteOrderingTest {

    private fun note(path: String, ts: Long, title: String = "Title") =
        Note(path, "root", title, "", ts, ts, emptyList(), null)

    // Convenience: sort the given list, then sort it reversed, and return both path-lists.
    private fun sortBothWays(notes: List<Note>, cmp: Comparator<Note>): Pair<List<String>, List<String>> {
        val fwd = notes.sortedWith(cmp).map { it.path }
        val rev = notes.reversed().sortedWith(cmp).map { it.path }
        return fwd to rev
    }

    @Test
    fun dateDesc_isStable_whenTimestampsAreEqual() {
        val ts = 5_000L
        val notes = listOf(
            note("p/zzz.md", ts),
            note("p/aaa.md", ts),
            note("p/mmm.md", ts),
            note("p/bbb.md", ts),
        )
        val (fwd, rev) = sortBothWays(notes, noteComparator(SortMode.DATE_DESC, emptySet()))
        assertEquals(
            "DATE_DESC: equal timestamps must yield the same order regardless of walk order",
            fwd, rev,
        )
    }

    @Test
    fun dateAsc_isStable_whenTimestampsAreEqual() {
        val ts = 5_000L
        val notes = listOf(
            note("p/zzz.md", ts),
            note("p/aaa.md", ts),
            note("p/mmm.md", ts),
        )
        val (fwd, rev) = sortBothWays(notes, noteComparator(SortMode.DATE_ASC, emptySet()))
        assertEquals(
            "DATE_ASC: equal timestamps must yield the same order regardless of walk order",
            fwd, rev,
        )
    }

    @Test
    fun modifiedDesc_isStable_whenTimestampsAreEqual() {
        val ts = 5_000L
        val notes = listOf(
            note("p/zzz.md", ts),
            note("p/aaa.md", ts),
            note("p/mmm.md", ts),
        )
        val (fwd, rev) = sortBothWays(notes, noteComparator(SortMode.MODIFIED_DESC, emptySet()))
        assertEquals(
            "MODIFIED_DESC: equal timestamps must yield the same order regardless of walk order",
            fwd, rev,
        )
    }

    @Test
    fun titleAsc_isStable_whenTitlesAreEqual() {
        val ts = 5_000L
        val notes = listOf(
            note("p/zzz.md", ts, "Same Title"),
            note("p/aaa.md", ts, "Same Title"),
            note("p/mmm.md", ts, "Same Title"),
        )
        val (fwd, rev) = sortBothWays(notes, noteComparator(SortMode.TITLE_ASC, emptySet()))
        assertEquals(
            "TITLE_ASC: duplicate titles must yield the same order regardless of walk order",
            fwd, rev,
        )
    }

    @Test
    fun sort_isFullyDeterministic_acrossMultipleWalkOrders() {
        // Simulate three app starts where DocumentFile.listFiles() returns the same notes
        // in three different enumeration orders. All three must produce an identical visible list.
        val ts = 5_000L
        val paths = listOf("p/zzz.md", "p/aaa.md", "p/mmm.md", "p/bbb.md", "p/ccc.md")
        val make = { order: List<String> -> order.map { note(it, ts) } }
        val cmp = noteComparator(SortMode.DATE_DESC, emptySet())

        val boot1 = make(paths).sortedWith(cmp).map { it.path }
        val boot2 = make(paths.reversed()).sortedWith(cmp).map { it.path }
        val boot3 = make(paths.shuffled(java.util.Random(99))).sortedWith(cmp).map { it.path }

        assertEquals("boot1 vs boot2 differ", boot1, boot2)
        assertEquals("boot1 vs boot3 differ", boot1, boot3)
    }

    @Test
    fun pinnedNotesAlwaysFirst_andThenStable() {
        val ts = 5_000L
        val notes = listOf(
            note("p/zzz.md", ts),
            note("p/aaa.md", ts),
            note("p/mmm.md", ts),
        )
        val pinned = setOf("p/mmm.md")
        val (fwd, rev) = sortBothWays(notes, noteComparator(SortMode.DATE_DESC, pinned))

        assertEquals("pinned note must always be first", "p/mmm.md", fwd.first())
        assertEquals(
            "remaining notes must be in consistent order regardless of walk order",
            fwd, rev,
        )
    }
}
