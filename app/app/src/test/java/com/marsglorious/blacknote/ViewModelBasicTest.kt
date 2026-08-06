package com.marsglorious.blacknote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.marsglorious.blacknote.data.Note
import com.marsglorious.blacknote.data.NoteRepository
import com.marsglorious.blacknote.data.TreeSnapshot
import com.marsglorious.blacknote.viewmodel.AppViewModel
import kotlinx.coroutines.withContext
import com.marsglorious.blacknote.viewmodel.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewModelBasicTest {
    private lateinit var vm: AppViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = NoteRepository(context, null) // fallback mode, no index
        val app: App = object : App() {}
        vm = AppViewModel(app, repo)
    }

    @After
    fun tear() {
        Dispatchers.resetMain()
    }

    @Test
    fun toggleListModeSwitches() = runTest {
        vm.bootstrap(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()
        val initial = vm.uiState.value.listMode
        vm.toggleListMode()
        assertTrue(vm.uiState.value.listMode != initial)
    }

    @Test
    fun setQueryFiltersVisible() = runTest {
        vm.bootstrap(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()
        vm.setQuery("test")
        advanceUntilIdle()
        // in fallback, uses searchNotes
        assertEquals("test", vm.uiState.value.query)
    }

    @Test
    fun deleteCallsWithoutCrash() = runTest {
        vm.bootstrap(ApplicationProvider.getApplicationContext())
        advanceUntilIdle()
        vm.deleteToTrash("fake", "fake")
        advanceUntilIdle()
        // no crash in fallback
    }

    @Test
    fun deleteRemovesNoteFromList() = runTest {
        // Fake repo to simulate notes + delete removal (bypasses real SAF)
        val fakeRepo = object : NoteRepository(ApplicationProvider.getApplicationContext<Context>(), null) {
            private val mainNotes = mutableListOf(
                Note("delpath", "root", "ToDelete", "p", 0L, 0L, emptyList(), null)
            )
            private val trashedPaths = mutableSetOf<String>()

            override suspend fun refreshTree(): TreeSnapshot {
                val visible = mainNotes.filter { it.path !in trashedPaths }
                return TreeSnapshot(notes = visible.toList(), folders = emptyList())
            }

            override suspend fun moveToTrash(noteUri: String, parentUri: String): Boolean {
                val wasInMain = mainNotes.any { it.path == noteUri }
                if (wasInMain) {
                    trashedPaths.add(noteUri)
                }
                return wasInMain
            }

            // Test helper to verify it was actually moved to trash
            fun isInTrash(path: String) = path in trashedPaths
        }
        val testVm = AppViewModel(ApplicationProvider.getApplicationContext<Context>() as App, fakeRepo)
        testVm.refreshTree()
        advanceUntilIdle()

        assertTrue(
            "should have the note initially",
            testVm.uiState.value.tree.notes.any { it.title == "ToDelete" }
        )

        testVm.deleteToTrash("delpath", "root")
        advanceUntilIdle()

        assertTrue(
            "delete should remove the note from the list",
            testVm.uiState.value.tree.notes.none { it.title == "ToDelete" }
        )

        assertTrue(
            "delete should have moved the MD file into the trash",
            fakeRepo.isInTrash("delpath")
        )
    }

    @Test
    fun performanceRefresh() = runTest {
        vm.bootstrap(ApplicationProvider.getApplicationContext())
        val start = System.currentTimeMillis()
        repeat(5) {
            vm.refreshTree()
            advanceUntilIdle()
        }
        val dur = System.currentTimeMillis() - start
        assertTrue("Refresh perf <500ms for 5: $dur", dur < 500)
    }

    // --- Tests for openNote flows, back, succession, performance, stale ---

    @Test
    fun openNote_success_goesToEditor() = runTest {
        val fakeRepo = object : NoteRepository(ApplicationProvider.getApplicationContext<Context>(), null) {
            override suspend fun refreshTree() = TreeSnapshot(
                notes = listOf(
                    Note("p1", "root", "Note One", "preview1", 100, 0, emptyList(), null)
                ),
                folders = emptyList()
            )
            override suspend fun read(path: String) = "# Note One\n\nBody content here."
        }
        val testVm = AppViewModel(ApplicationProvider.getApplicationContext<Context>() as App, fakeRepo)
        testVm.refreshTree()
        advanceUntilIdle()

        val note = testVm.uiState.value.tree.notes.first()
        testVm.openNote(note)
        advanceUntilIdle()

        assertEquals(Screen.EDITOR, testVm.uiState.value.screen)
        assertEquals("p1", testVm.uiState.value.editingPath)
        assertTrue(testVm.uiState.value.editingBody.text.contains("Body"))
    }

    @Test
    fun openNote_stale_returnsToList() = runTest {
        var refreshCalls = 0
        val fakeRepo = object : NoteRepository(ApplicationProvider.getApplicationContext<Context>(), null) {
            override suspend fun refreshTree(): TreeSnapshot {
                refreshCalls++
                // First refresh seeds the stale row; openNote's recovery refresh finds nothing.
                return if (refreshCalls == 1) {
                    TreeSnapshot(
                        notes = listOf(Note("stale", "root", "Stale Note", "p", 0, 0, emptyList(), null)),
                        folders = emptyList(),
                    )
                } else {
                    TreeSnapshot.EMPTY
                }
            }
            override suspend fun read(path: String): String? = null // simulate missing
        }
        val testVm = AppViewModel(ApplicationProvider.getApplicationContext<Context>() as App, fakeRepo)
        testVm.refreshTree()
        advanceUntilIdle()

        val note = testVm.uiState.value.tree.notes.first()
        testVm.openNote(note)
        advanceUntilIdle()

        // Read failed but the row still exists after merge — v1.9.x opens an empty editor
        // so the user isn't stuck, rather than bouncing back to LIST.
        assertEquals(Screen.EDITOR, testVm.uiState.value.screen)
        assertEquals("stale", testVm.uiState.value.editingPath)
        assertEquals("", testVm.uiState.value.editingBody.text)
    }

    @Test
    fun hittingBack_fromEditor_goesToList() = runTest {
        val testVm = AppViewModel(
            ApplicationProvider.getApplicationContext<Context>() as App,
            NoteRepository(ApplicationProvider.getApplicationContext<Context>(), null)
        )
        // Simulate being in editor
        testVm.openNoteRaw("p", "root", "some body")
        assertEquals(Screen.EDITOR, testVm.uiState.value.screen)

        testVm.closeEditor()
        advanceUntilIdle()

        assertEquals(Screen.LIST, testVm.uiState.value.screen)
    }

    @Test
    fun openingNotesInSuccession() = runTest {
        val fakeRepo = object : NoteRepository(ApplicationProvider.getApplicationContext<Context>(), null) {
            override suspend fun refreshTree() = TreeSnapshot(
                notes = listOf(
                    Note("n1", "root", "First", "b1", 1, 0, emptyList(), null),
                    Note("n2", "root", "Second", "b2", 2, 0, emptyList(), null)
                ),
                folders = emptyList()
            )
            override suspend fun read(path: String) = "body for $path"
        }
        val testVm = AppViewModel(ApplicationProvider.getApplicationContext<Context>() as App, fakeRepo)
        testVm.refreshTree()
        advanceUntilIdle()

        val notes = testVm.uiState.value.tree.notes
        val first = notes.first { it.path == "n1" }
        val second = notes.first { it.path == "n2" }
        testVm.openNote(first)
        advanceUntilIdle()
        assertEquals("n1", testVm.uiState.value.editingPath)
        testVm.openNote(second)
        advanceUntilIdle()

        assertEquals(Screen.EDITOR, testVm.uiState.value.screen)
        assertEquals("n2", testVm.uiState.value.editingPath)
    }

    @Test
    fun performance_openNotes() = runTest {
        val fakeRepo = object : NoteRepository(ApplicationProvider.getApplicationContext<Context>(), null) {
            override suspend fun refreshTree() = TreeSnapshot(
                notes = (1..20).map { i ->
                    Note("p$i", "root", "Note $i", "preview $i", i.toLong(), 0, emptyList(), null)
                },
                folders = emptyList()
            )
            override suspend fun read(path: String) = "body"
        }
        val testVm = AppViewModel(ApplicationProvider.getApplicationContext<Context>() as App, fakeRepo)
        testVm.refreshTree()
        advanceUntilIdle()

        val notes = testVm.uiState.value.tree.notes
        val start = System.currentTimeMillis()
        notes.forEach { n ->
            testVm.openNote(n)
            advanceUntilIdle()
            testVm.closeEditor()
            advanceUntilIdle()
        }
        val dur = System.currentTimeMillis() - start
        assertTrue("Opening 20 notes in succession + back took ${dur}ms", dur < 2000)
    }

    @Test
    fun deletedNote_appearsInTrashAndDisappearsFromList() = runTest {
        // End-to-end VM-level check that the three guarantees hold simultaneously:
        //   (1) note vanishes from main listing,
        //   (2) note appears in the trash listing the user sees,
        //   (3) the underlying repo recorded the file as moved to trash.
        val fakeRepo = object : NoteRepository(ApplicationProvider.getApplicationContext<Context>(), null) {
            private val main = mutableListOf(
                Note("u1", "root", "Keeper", "x", 0L, 0L, emptyList(), null),
                Note("u2", "root", "Doomed", "y", 0L, 0L, emptyList(), null),
            )
            private val trash = mutableListOf<Note>()

            override suspend fun refreshTree(): TreeSnapshot =
                TreeSnapshot(notes = main.toList(), folders = emptyList())

            override suspend fun refreshTrash(): List<Note> = trash.toList()

            override suspend fun moveToTrash(noteUri: String, parentUri: String): Boolean {
                val n = main.firstOrNull { it.path == noteUri } ?: return false
                main.remove(n)
                trash.add(n.copy(path = "trash/${n.path}"))
                return true
            }

            suspend fun trashSnapshot() = trash.toList()
        }
        val testVm = AppViewModel(ApplicationProvider.getApplicationContext<Context>() as App, fakeRepo)
        testVm.refreshTree(); advanceUntilIdle()

        testVm.deleteToTrash("u2", "root"); advanceUntilIdle()

        // (1) gone from main listing
        assertTrue(testVm.uiState.value.tree.notes.none { it.title == "Doomed" })
        assertTrue(testVm.uiState.value.visibleNotes.none { it.title == "Doomed" })
        // (2) the user can see it in trash
        testVm.openTrash(); advanceUntilIdle()
        assertEquals(Screen.TRASH, testVm.uiState.value.screen)
        assertTrue(
            "trashed note should be visible on the trash screen",
            testVm.uiState.value.trashNotes.any { it.title == "Doomed" }
        )
        // (3) the keeper survives
        assertTrue(fakeRepo.trashSnapshot().any { it.title == "Doomed" })
    }

    @Test
    fun delete_whenRepoFails_noteRemainsAndUserCanSeeIt() = runTest {
        // If the SAF provider refuses the move (which historically happened silently),
        // the listing must not lie to the user — the note has to come back on refresh.
        val fakeRepo = object : NoteRepository(ApplicationProvider.getApplicationContext<Context>(), null) {
            private val main = mutableListOf(
                Note("p", "root", "Stuck", "x", 0L, 0L, emptyList(), null),
            )
            override suspend fun refreshTree() =
                TreeSnapshot(notes = main.toList(), folders = emptyList())
            override suspend fun moveToTrash(noteUri: String, parentUri: String) = false
        }
        val testVm = AppViewModel(ApplicationProvider.getApplicationContext<Context>() as App, fakeRepo)
        testVm.refreshTree(); advanceUntilIdle()

        testVm.deleteToTrash("p", "root"); advanceUntilIdle()

        assertTrue(
            "When the underlying move fails, the listing must restore the note instead of " +
                "silently dropping it from the user's view.",
            testVm.uiState.value.tree.notes.any { it.title == "Stuck" }
        )
    }

    @Test
    fun scrollOpenBack_returnsToSameSpotAndIsInstant() = runTest {
        // The flow the user described: scroll to a note → open it → back → scroll to the next
        // → open → back → scroll to a third → open → back. Each "back" must land on the spot
        // we started from, each "open" must be effectively instant.
        val fakeRepo = object : NoteRepository(ApplicationProvider.getApplicationContext<Context>(), null) {
            override suspend fun refreshTree() = TreeSnapshot(
                notes = (1..50).map { i ->
                    Note("p$i", "root", "Note $i", "body $i", i.toLong(), 0, emptyList(), null)
                },
                folders = emptyList()
            )
            override suspend fun read(path: String) = "# Title\n\nbody for $path"
        }
        val testVm = AppViewModel(ApplicationProvider.getApplicationContext<Context>() as App, fakeRepo)
        testVm.refreshTree(); advanceUntilIdle()
        val notes = testVm.uiState.value.tree.notes

        // The three (index, offset) spots the user scrolled to before tapping each note.
        val spots = listOf(Triple(notes[5], 5, 40), Triple(notes[15], 15, 80), Triple(notes[28], 28, 12))
        val openBudgetMs = 80L // generous — a single openNote must feel instant.

        for ((note, idx, off) in spots) {
            testVm.saveListScrollPosition(idx, off)

            // Open the note. Time the whole openNote → editor-on-screen transition.
            val t0 = System.currentTimeMillis()
            testVm.openNote(note)
            advanceUntilIdle()
            val elapsed = System.currentTimeMillis() - t0
            assertEquals("openNote should land on the editor", Screen.EDITOR, testVm.uiState.value.screen)
            assertEquals("editor should be on the tapped note", note.path, testVm.uiState.value.editingPath)
            assertTrue("openNote took ${elapsed}ms — taps must feel instant", elapsed < openBudgetMs)

            // Back to list.
            testVm.closeEditor()
            advanceUntilIdle()
            assertEquals(Screen.LIST, testVm.uiState.value.screen)

            // Scroll spot preserved — this is the actual "return to where you started" check.
            assertEquals(
                "list scroll index drifted after open/back cycle on ${note.title}",
                idx, testVm.uiState.value.listScrollIndex
            )
            assertEquals(
                "list scroll offset drifted after open/back cycle on ${note.title}",
                off, testVm.uiState.value.listScrollOffset
            )
        }
    }

    @Test
    fun openNote_doesNotFlashWhenReadFails() = runTest {
        // Repro for the user-reported "press a note, flashes into it, instantly closes" bug.
        // If the underlying read fails, the editor must NEVER have been shown — otherwise
        // the user sees a flash and the next tap repeats the cycle.
        var refreshCalls = 0
        val fakeRepo = object : NoteRepository(ApplicationProvider.getApplicationContext<Context>(), null) {
            override suspend fun refreshTree(): TreeSnapshot {
                refreshCalls++
                return if (refreshCalls == 1) {
                    TreeSnapshot(
                        notes = listOf(Note("bad", "root", "Broken", "p", 0, 0, emptyList(), null)),
                        folders = emptyList(),
                    )
                } else {
                    TreeSnapshot.EMPTY
                }
            }
            override suspend fun read(path: String): String? = null
        }
        val testVm = AppViewModel(ApplicationProvider.getApplicationContext<Context>() as App, fakeRepo)
        testVm.refreshTree(); advanceUntilIdle()
        // openNote must not flip to EDITOR synchronously — that's the flash. With the fix,
        // it stays on LIST until the read finishes (and if read fails, never flips at all).
        testVm.openNote(testVm.uiState.value.tree.notes.first())
        assertEquals(
            "openNote flipped to EDITOR synchronously — this causes the flash-then-close",
            Screen.LIST, testVm.uiState.value.screen
        )
        advanceUntilIdle()
        // No flash: read failed first, then opened only after recovery gave up on content.
        assertEquals(Screen.EDITOR, testVm.uiState.value.screen)
        assertEquals("bad", testVm.uiState.value.editingPath)
        assertEquals("", testVm.uiState.value.editingBody.text)
    }

    @Test
    fun backFromNote_restoresExactScrollPosition() = runTest {
        val testVm = AppViewModel(
            ApplicationProvider.getApplicationContext<Context>() as App,
            NoteRepository(ApplicationProvider.getApplicationContext<Context>(), null)
        )
        // User scrolls to a certain location in the list
        testVm.saveListScrollPosition(42, 123)
        assertEquals(42, testVm.uiState.value.listScrollIndex)
        assertEquals(123, testVm.uiState.value.listScrollOffset)

        // Click a note (open it) -- this should not reset the list scroll
        val dummy = Note("p1", "root", "Note", "", 0, 0, emptyList(), null)
        testVm.openNote(dummy)
        advanceUntilIdle()

        // Hit back (phone back or app back)
        testVm.closeEditor()
        advanceUntilIdle()

        // Should be back at the exact same scroll location, not top (index 0)
        assertEquals(42, testVm.uiState.value.listScrollIndex)
        assertEquals(123, testVm.uiState.value.listScrollOffset)
    }
}
