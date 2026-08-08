package com.marsglorious.blacknote

import android.content.Context
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.marsglorious.blacknote.data.FileStore
import com.marsglorious.blacknote.data.FolderInfo
import com.marsglorious.blacknote.data.Note
import com.marsglorious.blacknote.data.NoteRepository
import com.marsglorious.blacknote.data.TreeSnapshot
import com.marsglorious.blacknote.viewmodel.AppViewModel
import com.marsglorious.blacknote.viewmodel.Screen
import com.marsglorious.blacknote.viewmodel.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppViewModelFeatureTest {
    private val testDispatcher = StandardTestDispatcher()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val app: App get() = ctx as App

    @Before
    fun setup() { Dispatchers.setMain(testDispatcher) }

    @After
    fun tear() { Dispatchers.resetMain() }

    private fun note(path: String, title: String, created: Long = 0, modified: Long = 0, preview: String = "") =
        Note(path, "root", title, preview, modified, created, emptyList(), null)

    // --- Folder switching must not leak the previous folder's notes ---

    @Test
    fun switchingFolders_dropsOldFolderNotes() = runTest {
        // Folder selection is now a plain file path (SAF is only used for the picker UI).
        // The switch must wipe the previous folder's notes so mergeSnapshot can't keep
        // them alive as optimistic entries and interleave both folders in the list.
        // In-memory FileStore so onFolderPicked's folder read/write doesn't hit real
        // DataStore, which never settles under the virtual test clock.
        val fakeFs = object : FileStore(ctx) {
            private var folder: String? = null
            override suspend fun getFolderPath(): String? = folder
            override suspend fun saveFolderPath(path: String) { folder = path }
            override suspend fun getPref(name: String): String? = null
            override suspend fun setPref(name: String, value: String) {}
        }
        var switched = false
        val repo = object : NoteRepository(ctx, null, fakeFs) {
            override suspend fun refreshTree(): TreeSnapshot =
                if (!switched) {
                    TreeSnapshot(listOf(note("a1", "Alpha One"), note("a2", "Alpha Two")), emptyList())
                } else {
                    TreeSnapshot(listOf(note("b1", "Beta One")), emptyList())
                }
        }
        val vm = AppViewModel(app, repo)
        vm.onFolderPicked("/storage/emulated/0/A"); advanceUntilIdle()
        assertEquals(2, vm.uiState.value.tree.notes.size)

        switched = true
        vm.onFolderPicked("/storage/emulated/0/B"); advanceUntilIdle()

        val titles = vm.uiState.value.tree.notes.map { it.title }
        assertEquals(
            "old folder's notes must not survive a folder switch (mergeSnapshot kept them as optimistic entries)",
            listOf("Beta One"), titles,
        )
        assertTrue(vm.uiState.value.visibleNotes.none { it.title.startsWith("Alpha") })
    }

    // --- Startup must not flash the "Pick a folder" screen ---

    @Test
    fun folderStatusIsUnknownUntilBootstrapResolves() = runTest {
        // In-memory FileStore so getFolderPath/getPref settle under the virtual clock.
        val fakeFs = object : FileStore(ctx) {
            override suspend fun getFolderPath(): String? = null
            override suspend fun getPref(name: String): String? = null
            override suspend fun setPref(name: String, value: String) {}
        }
        val repo = object : NoteRepository(ctx, null, fakeFs) {
            override suspend fun refreshTree() = TreeSnapshot.EMPTY
        }
        val vm = AppViewModel(app, repo)
        // Before bootstrap resolves, folder status is unknown so the list screen renders
        // nothing rather than the "Pick a folder" placeholder — no flash.
        assertTrue("folder status must be unknown pre-bootstrap", !vm.uiState.value.folderKnown)

        vm.bootstrap(ctx); advanceUntilIdle()
        assertTrue("folder status must be resolved after bootstrap", vm.uiState.value.folderKnown)
        assertTrue("no folder configured in this fake", !vm.uiState.value.hasFolder)
    }

    @Test
    fun placeholderIsSuppressedUntilFirstLoadCompletes() = runTest {
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun refreshTree() = TreeSnapshot.EMPTY
        }
        val vm = AppViewModel(app, repo)
        // Before any load, the "No notes yet" placeholder must be suppressed.
        assertTrue("initial load must not be marked complete yet", !vm.uiState.value.initialLoadComplete)
        vm.refreshTree(); advanceUntilIdle()
        assertTrue("initial load complete after first refresh", vm.uiState.value.initialLoadComplete)
    }

    // --- Opening a note without editing must not rewrite it (mtime / date bug) ---

    @Test
    fun openingNoteAndClosingWithoutEdits_doesNotWrite() = runTest {
        var writeCalls = 0
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun write(path: String, parent: String, text: String): Boolean {
                writeCalls++; return true
            }
            override suspend fun read(path: String) = "hello body"
            override suspend fun renameToMatchTitle(currentUri: String, parent: String, desiredTitle: String) = currentUri
            override suspend fun refreshTree() = TreeSnapshot.EMPTY
        }
        val vm = AppViewModel(app, repo)
        vm.openNoteRaw("/root/Note.md", "/root", "hello body", "Note.md")
        vm.closeEditor(); advanceUntilIdle()
        assertEquals("closing an unchanged note must not write the file", 0, writeCalls)
    }

    @Test
    fun editingNoteBody_writesOnClose() = runTest {
        var writeCalls = 0
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun write(path: String, parent: String, text: String): Boolean {
                writeCalls++; return true
            }
            override suspend fun read(path: String) = "edited body"
            override suspend fun renameToMatchTitle(currentUri: String, parent: String, desiredTitle: String) = currentUri
            override suspend fun refreshTree() = TreeSnapshot.EMPTY
        }
        val vm = AppViewModel(app, repo)
        vm.openNoteRaw("/root/Note.md", "/root", "original body", "Note.md")
        vm.onBodyChange(TextFieldValue("original body + edit"))
        vm.closeEditor(); advanceUntilIdle()
        assertTrue("a real edit must persist on close", writeCalls >= 1)
    }

    // --- A new note scrolls the list to the top only when it lands at the top ---

    @Test
    fun newestTopLevelNote_isAtTop_underDateDesc() {
        val vm = AppViewModel(app, object : NoteRepository(ctx, null) {})
        val notes = listOf(
            note("/root/new.md", "New", modified = 900),
            note("/root/old.md", "Old", modified = 100),
        )
        assertTrue(vm.isNoteAtTopOfList("/root/new.md", notes, emptyList(), SortMode.DATE_DESC, emptySet()))
        assertTrue(!vm.isNoteAtTopOfList("/root/old.md", notes, emptyList(), SortMode.DATE_DESC, emptySet()))
    }

    @Test
    fun newestNote_isNotAtTop_underDateAsc() {
        val vm = AppViewModel(app, object : NoteRepository(ctx, null) {})
        val notes = listOf(
            note("/root/new.md", "New", modified = 900),
            note("/root/old.md", "Old", modified = 100),
        )
        // Oldest-first: the newest note sorts to the bottom, so it is not at the top.
        assertTrue(!vm.isNoteAtTopOfList("/root/new.md", notes, emptyList(), SortMode.DATE_ASC, emptySet()))
    }

    @Test
    fun nestedNote_isNeverAtTop_evenIfNewest() {
        val vm = AppViewModel(app, object : NoteRepository(ctx, null) {})
        val folders = listOf(FolderInfo("/root/Sub", "/root", "Sub", 0))
        val topLevel = Note("/root/top.md", "/root", "Top", "", 100, 100, emptyList(), null)
        val child = Note("/root/Sub/child.md", "/root/Sub", "Child", "", 999, 999, emptyList(), null)
        val notes = listOf(topLevel, child)
        // Child has the newest mtime but sits inside a folder, so it isn't at the top.
        assertTrue(!vm.isNoteAtTopOfList("/root/Sub/child.md", notes, folders, SortMode.DATE_DESC, emptySet()))
        assertTrue(vm.isNoteAtTopOfList("/root/top.md", notes, folders, SortMode.DATE_DESC, emptySet()))
    }

    @Test
    fun creatingTopLevelNote_thenClosing_requestsScrollToTop() = runTest {
        val fakeFs = object : FileStore(ctx) {
            override suspend fun getFolderPath() = "/root"
            override suspend fun getPref(name: String): String? = null
            override suspend fun setPref(name: String, value: String) {}
        }
        val repo = object : NoteRepository(ctx, null, fakeFs) {
            override suspend fun create(parentFolder: String?): Pair<String, String> = "/root/Untitled.md" to "/root"
            override suspend fun read(path: String) = "body"
            override suspend fun write(path: String, parent: String, text: String) = true
            override suspend fun renameToMatchTitle(currentUri: String, parent: String, desiredTitle: String) = currentUri
            override suspend fun refreshTree() = TreeSnapshot.EMPTY
        }
        val vm = AppViewModel(app, repo)
        vm.newNote(); advanceUntilIdle()
        vm.closeEditor(); advanceUntilIdle()
        assertTrue("a new top-level note should request scroll to top", vm.uiState.value.scrollListToTop)
        vm.consumeScrollToTop()
        assertTrue("flag must clear once consumed", !vm.uiState.value.scrollListToTop)
    }

    @Test
    fun editingExistingNote_doesNotScrollToTop() = runTest {
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun read(path: String) = "body"
            override suspend fun write(path: String, parent: String, text: String) = true
            override suspend fun renameToMatchTitle(currentUri: String, parent: String, desiredTitle: String) = currentUri
            override suspend fun refreshTree() = TreeSnapshot.EMPTY
        }
        val vm = AppViewModel(app, repo)
        vm.openNoteRaw("/root/existing.md", "/root", "body", "existing.md")
        vm.closeEditor(); advanceUntilIdle()
        assertTrue("editing an existing note must not move the list", !vm.uiState.value.scrollListToTop)
    }

    // --- Default "Untitled" notes open with an empty title field ---

    @Test
    fun openingDefaultUntitledNote_opensWithEmptyTitleField() = runTest {
        val vm = AppViewModel(app, object : NoteRepository(ctx, null) {})
        vm.openNoteRaw("/notes/Untitled.md", "/notes", "", "Untitled.md")
        assertEquals("", vm.uiState.value.editingTitle.text)
    }

    @Test
    fun openingNumberedUntitledNote_opensWithEmptyTitleField() = runTest {
        val vm = AppViewModel(app, object : NoteRepository(ctx, null) {})
        vm.openNoteRaw("/notes/Untitled 2.md", "/notes", "", "Untitled 2.md")
        assertEquals("", vm.uiState.value.editingTitle.text)
    }

    @Test
    fun openingNamedNote_showsItsTitle() = runTest {
        val vm = AppViewModel(app, object : NoteRepository(ctx, null) {})
        vm.openNoteRaw("/notes/Groceries.md", "/notes", "", "Groceries.md")
        assertEquals("Groceries", vm.uiState.value.editingTitle.text)
        // A file literally named "Untitled Poems.md" is a real title, not the placeholder.
        vm.openNoteRaw("/notes/Untitled Poems.md", "/notes", "", "Untitled Poems.md")
        assertEquals("Untitled Poems", vm.uiState.value.editingTitle.text)
    }

    @Test
    fun closingUntitledNoteWithoutTyping_doesNotRenameFile() = runTest {
        var renameCalled = false
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun write(path: String, parent: String, text: String) = true
            override suspend fun read(path: String) = "body"
            override suspend fun renameToMatchTitle(currentUri: String, parent: String, desiredTitle: String): String {
                renameCalled = true; return currentUri
            }
        }
        val vm = AppViewModel(app, repo)
        vm.openNoteRaw("/notes/Untitled 2.md", "/notes", "", "Untitled 2.md")
        vm.closeEditor(); advanceUntilIdle()
        assertTrue("a blank title must not rename the file", !renameCalled)
    }

    // --- Search must reach note bodies through the FTS index ---

    @Test
    fun search_findsBodyOnlyMatchesViaIndex() = runTest {
        val repo = object : NoteRepository(ctx, null) {
            override val hasNativeIndex: Boolean get() = true
            override suspend fun refreshTree() = TreeSnapshot(
                listOf(
                    note("n1", "Shopping", preview = "eggs milk"),
                    note("n2", "Journal", preview = "morning walk"),
                ),
                emptyList(),
            )
            override suspend fun search(query: String, fallback: List<Note>, limit: Int): List<Note> =
                // Simulates FTS: "zanzibar" appears deep in n2's body, invisible in the preview.
                if (query == "zanzibar") listOf(note("n2", "Journal")) else emptyList()
        }
        val vm = AppViewModel(app, repo)
        vm.refreshTree(); advanceUntilIdle()

        vm.setQuery("zanzibar"); advanceUntilIdle()

        assertTrue(
            "a body-only match must surface through the FTS index — the index existed but search never used it",
            vm.uiState.value.visibleNotes.any { it.title == "Journal" },
        )
        assertFalse(vm.uiState.value.visibleNotes.any { it.title == "Shopping" })
    }

    @Test
    fun search_substringMatchesStillWorkWithoutIndex() = runTest {
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun refreshTree() = TreeSnapshot(
                listOf(note("n1", "Groceries", preview = "buy milk"), note("n2", "Other")),
                emptyList(),
            )
        }
        val vm = AppViewModel(app, repo)
        vm.refreshTree(); advanceUntilIdle()
        vm.setQuery("milk"); advanceUntilIdle()
        assertEquals(listOf("Groceries"), vm.uiState.value.visibleNotes.map { it.title })
    }

    // --- Delete forever needs confirmation; empty trash exists ---

    @Test
    fun deleteForever_requiresConfirmation() = runTest {
        var deleted: String? = null
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun refreshTrash() = listOf(note("t1", "Trashed"))
            override suspend fun deletePermanently(noteUri: String): Boolean {
                deleted = noteUri; return true
            }
        }
        val vm = AppViewModel(app, repo)
        vm.openTrash(); advanceUntilIdle()

        vm.requestDeletePermanently("t1")
        assertEquals("t1", vm.uiState.value.confirmDeleteForever)
        assertNull("nothing may be deleted before the user confirms", deleted)

        vm.cancelDeletePermanently()
        advanceUntilIdle()
        assertNull("cancel must not delete", deleted)

        vm.requestDeletePermanently("t1")
        vm.confirmDeletePermanently(); advanceUntilIdle()
        assertEquals("t1", deleted)
        assertNull(vm.uiState.value.confirmDeleteForever)
    }

    @Test
    fun emptyTrash_confirmFlowDeletesEverything() = runTest {
        var emptied = false
        val repo = object : NoteRepository(ctx, null) {
            private val trash = mutableListOf(note("t1", "One"), note("t2", "Two"))
            override suspend fun refreshTrash() = trash.toList()
            override suspend fun emptyTrash(): Int {
                emptied = true
                val n = trash.size; trash.clear(); return n
            }
        }
        val vm = AppViewModel(app, repo)
        vm.openTrash(); advanceUntilIdle()
        assertEquals(2, vm.uiState.value.trashNotes.size)

        vm.requestEmptyTrash()
        assertTrue(vm.uiState.value.confirmEmptyTrash)
        assertFalse(emptied)

        vm.confirmEmptyTrash(); advanceUntilIdle()
        assertTrue(emptied)
        assertTrue(vm.uiState.value.trashNotes.isEmpty())
    }

    // --- Pinning and sorting ---

    @Test
    fun pinnedNotesSortFirst() = runTest {
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun refreshTree() = TreeSnapshot(
                listOf(
                    note("new", "Newest", modified = 300),
                    note("mid", "Middle", modified = 200),
                    note("old", "Oldest", modified = 100),
                ),
                emptyList(),
            )
        }
        val vm = AppViewModel(app, repo)
        vm.refreshTree(); advanceUntilIdle()
        assertEquals(listOf("Newest", "Middle", "Oldest"), vm.uiState.value.visibleNotes.map { it.title })

        vm.togglePin("old"); advanceUntilIdle()
        assertEquals(
            "pinned note must jump to the top",
            listOf("Oldest", "Newest", "Middle"), vm.uiState.value.visibleNotes.map { it.title },
        )

        vm.togglePin("old"); advanceUntilIdle()
        assertEquals(listOf("Newest", "Middle", "Oldest"), vm.uiState.value.visibleNotes.map { it.title })
    }

    @Test
    fun sortModeReordersVisibleNotes() = runTest {
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun refreshTree() = TreeSnapshot(
                listOf(
                    note("b", "Banana", created = 300, modified = 1),
                    note("a", "Apple", created = 200, modified = 2),
                    note("c", "Cherry", created = 100, modified = 3),
                ),
                emptyList(),
            )
        }
        val vm = AppViewModel(app, repo)
        vm.refreshTree(); advanceUntilIdle()

        vm.setSortMode(SortMode.TITLE_ASC); advanceUntilIdle()
        assertEquals(listOf("Apple", "Banana", "Cherry"), vm.uiState.value.visibleNotes.map { it.title })

        vm.setSortMode(SortMode.DATE_ASC); advanceUntilIdle()
        // DATE_ASC now sorts by file mtime ascending (was frontmatter created date).
        assertEquals(listOf("Banana", "Apple", "Cherry"), vm.uiState.value.visibleNotes.map { it.title })

        vm.setSortMode(SortMode.MODIFIED_DESC); advanceUntilIdle()
        assertEquals(listOf("Cherry", "Apple", "Banana"), vm.uiState.value.visibleNotes.map { it.title })
    }

    // --- closeEditor keeps the original creation date on the optimistic card ---

    @Test
    fun closeEditor_preservesCreatedDate() = runTest {
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun refreshTree() = TreeSnapshot(
                listOf(note("p1", "Old Note", created = 1234L)),
                emptyList(),
            )
            override suspend fun read(path: String) = "content"
            override suspend fun write(path: String, parent: String, text: String) = true
            override suspend fun renameToMatchTitle(currentUri: String, parent: String, desiredTitle: String) = currentUri
        }
        val vm = AppViewModel(app, repo)
        vm.refreshTree(); advanceUntilIdle()

        vm.openNote(vm.uiState.value.tree.notes.first()); advanceUntilIdle()
        assertEquals(Screen.EDITOR, vm.uiState.value.screen)
        vm.onBodyChange(TextFieldValue("content edited"))
        vm.closeEditor(); advanceUntilIdle()

        val after = vm.uiState.value.tree.notes.first { it.path == "p1" }
        assertEquals(
            "optimistic insert must keep the note's original creation date, not stamp 'now'",
            1234L, after.createdMillis,
        )
    }

    // --- New note in a folder (long-press → New note here) ---

    @Test
    fun newNote_inFolderCreatesUnderThatParent() = runTest {
        var requestedParent: String? = "unset"
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun refreshTree() = TreeSnapshot.EMPTY
            override suspend fun create(parentFolder: String?): Pair<String, String>? {
                requestedParent = parentFolder
                return "newpath" to (parentFolder ?: "root")
            }
        }
        val vm = AppViewModel(app, repo)
        vm.newNote(parentFolder = "folderX"); advanceUntilIdle()
        assertEquals("folderX", requestedParent)
        assertEquals(Screen.EDITOR, vm.uiState.value.screen)
        assertEquals("newpath", vm.uiState.value.editingPath)
    }

    // --- Wiki link navigation ---

    @Test
    fun wikiLink_opensMatchingNoteByTitle() = runTest {
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun refreshTree() = TreeSnapshot(
                listOf(note("g1", "Groceries"), note("j1", "Journal")),
                emptyList(),
            )
            override suspend fun read(path: String) = "body of $path"
        }
        val vm = AppViewModel(app, repo)
        vm.refreshTree(); advanceUntilIdle()

        vm.openWikiLink("groceries"); advanceUntilIdle()
        assertEquals(Screen.EDITOR, vm.uiState.value.screen)
        assertEquals("g1", vm.uiState.value.editingPath)
    }

    @Test
    fun wikiLink_noMatchIsNoOp() = runTest {
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun refreshTree() = TreeSnapshot(listOf(note("g1", "Groceries")), emptyList())
        }
        val vm = AppViewModel(app, repo)
        vm.refreshTree(); advanceUntilIdle()
        vm.openWikiLink("nothing like this"); advanceUntilIdle()
        assertEquals(Screen.LIST, vm.uiState.value.screen)
    }

    // --- Rapid typing while searching must never desync the query field ---

    @Test
    fun setQuery_updatesFieldSynchronously() = runTest {
        val vm = AppViewModel(app, NoteRepository(ctx, null))
        for (q in listOf("s", "so", "soc", "soci")) {
            vm.setQuery(q)
            assertEquals("query must update synchronously (IME desync otherwise)", q, vm.uiState.value.query)
        }
    }
}
