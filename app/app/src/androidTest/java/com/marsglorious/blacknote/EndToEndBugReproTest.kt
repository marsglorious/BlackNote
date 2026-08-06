package com.marsglorious.blacknote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.marsglorious.blacknote.data.TRASH_FOLDER_NAME
import com.marsglorious.blacknote.viewmodel.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented repro for the four user-reported bugs. Each test drives the real production
 * [com.marsglorious.blacknote.viewmodel.AppViewModel] against a real on-device directory
 * via [TestSafStore].
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class EndToEndBugReproTest : InstrumentedVmTestBase() {

    @Test
    fun createEditCloseReopen_keepsEdits_andTitleMatchesFileName() = runBlocking {
        vm.newNote(); awaitEditor()

        vm.onTitleChange(tfv("My Plan"))
        vm.onBodyChange(tfv("first line\nsecond line"))
        vm.saveNow()

        vm.closeEditor(); awaitList(); awaitRefreshSettled()

        val noteAfter = vm.uiState.value.tree.notes.singleOrNull()
            ?: error("expected exactly one note after create; got ${vm.uiState.value.tree.notes}")

        val fileNames = rootDir.listFiles()?.filter { it.isFile && it.name.endsWith(".md") }?.map { it.name }
            ?: emptyList()
        val onDiskName = fileNames.singleOrNull()
            ?: error("expected exactly one .md on disk; got $fileNames")
        assertEquals(
            "list title must equal file name minus .md (title='${noteAfter.title}', file='$onDiskName')",
            onDiskName.removeSuffix(".md"), noteAfter.title
        )

        vm.openNote(noteAfter); awaitEditor()
        assertEquals("My Plan", vm.uiState.value.editingTitle.text)
        val body = vm.uiState.value.editingBody.text
        assertTrue("body lost 'first line' after reopen, was '$body'", body.contains("first line"))
        assertTrue("body lost 'second line' after reopen, was '$body'", body.contains("second line"))
    }

    @Test
    fun secondEditCycle_alsoPersists() = runBlocking {
        vm.newNote(); awaitEditor()
        vm.onTitleChange(tfv("Alpha")); vm.onBodyChange(tfv("body v1")); vm.saveNow()
        vm.closeEditor(); awaitList(); awaitRefreshSettled()

        val first = vm.uiState.value.tree.notes.single()
        vm.openNote(first); awaitEditor()
        vm.onBodyChange(tfv("body v2")); vm.saveNow()
        vm.closeEditor(); awaitList(); awaitRefreshSettled()

        val after = vm.uiState.value.tree.notes.single()
        vm.openNote(after); awaitEditor()
        assertEquals("Alpha", vm.uiState.value.editingTitle.text)
        assertTrue(
            "second-cycle edit lost: body was '${vm.uiState.value.editingBody.text}'",
            vm.uiState.value.editingBody.text.contains("body v2")
        )
    }

    @Test
    fun titleRename_updatesBothTitleAndFileName() = runBlocking {
        vm.newNote(); awaitEditor()
        vm.onTitleChange(tfv("First")); vm.onBodyChange(tfv("content")); vm.saveNow()
        vm.closeEditor(); awaitList(); awaitRefreshSettled()
        val n1 = vm.uiState.value.tree.notes.single()
        assertEquals("First", n1.title)
        assertTrue("expected First.md on disk, got ${rootDir.listFiles()?.map { it.name }}",
            File(rootDir, "First.md").exists())

        vm.openNote(n1); awaitEditor()
        vm.onTitleChange(tfv("Renamed")); vm.saveNow()
        vm.closeEditor(); awaitList(); awaitRefreshSettled()

        val n2 = vm.uiState.value.tree.notes.single()
        assertEquals("Renamed", n2.title)
        assertTrue("expected Renamed.md after rename, got ${rootDir.listFiles()?.map { it.name }}",
            File(rootDir, "Renamed.md").exists())
    }

    @Test
    fun openNote_whenFileIsMissing_neverFlashesEditor_andSubsequentTapsWork() = runBlocking {
        File(rootDir, "broken.md").writeText("# Broken\n\nbody")
        File(rootDir, "works.md").writeText("# Works\n\nactual body")
        vm.refreshTree(); awaitRefreshSettled()

        val broken = vm.uiState.value.tree.notes.first { it.title == "Broken" }
        val works = vm.uiState.value.tree.notes.first { it.title == "Works" }

        File(rootDir, "broken.md").delete()

        vm.openNote(broken)
        assertEquals("openNote flipped to EDITOR synchronously — that's the flash bug",
            Screen.LIST, vm.uiState.value.screen)
        awaitRefreshSettled()
        assertEquals("after a failed open, screen must still be LIST",
            Screen.LIST, vm.uiState.value.screen)
        assertNull("after a failed open, editingPath must be null",
            vm.uiState.value.editingPath)

        vm.openNote(works); awaitEditor()
        assertEquals(works.path, vm.uiState.value.editingPath)
    }

    @Test
    fun openNote_repeatedTapsOnSameRow_alwaysOpenIt() = runBlocking {
        File(rootDir, "Note.md").writeText("# Note\n\nbody")
        vm.refreshTree(); awaitRefreshSettled()
        val n = vm.uiState.value.tree.notes.single()

        vm.openNote(n); awaitEditor()
        vm.closeEditor(); awaitList(); awaitRefreshSettled()

        val again = vm.uiState.value.tree.notes.single()
        vm.openNote(again); awaitEditor()
        assertEquals(again.path, vm.uiState.value.editingPath)
    }

    @Test
    fun deletedNote_appearsOnTrashScreen() = runBlocking {
        File(rootDir, "Doomed.md").writeText("# Doomed\n\ngoodbye")
        vm.refreshTree(); awaitRefreshSettled()
        val doomed = vm.uiState.value.tree.notes.single()

        vm.deleteToTrash(doomed.path, doomed.parent)
        awaitRefreshSettled()
        awaitCondition("note moved into Trash on disk") {
            File(rootDir, "$TRASH_FOLDER_NAME/Doomed.md").exists() ||
                File(rootDir, ".Trash/Doomed.md").exists()
        }

        assertTrue("Doomed.md must be in Trash on disk",
            File(rootDir, "$TRASH_FOLDER_NAME/Doomed.md").exists() ||
                File(rootDir, ".Trash/Doomed.md").exists())

        assertFalse("Doomed should be gone from main listing",
            vm.uiState.value.tree.notes.any { it.title.equals("Doomed", true) })

        vm.openTrash()
        awaitCondition("trash screen lists deleted note") {
            vm.uiState.value.screen == Screen.TRASH &&
                vm.uiState.value.trashNotes.any { it.title.equals("Doomed", true) }
        }
    }

    @Test
    fun deleteThenImmediatelyOpenTrash_showsTheNote() = runBlocking {
        File(rootDir, "Quick.md").writeText("# Quick\n\nbody")
        vm.refreshTree(); awaitRefreshSettled()
        val n = vm.uiState.value.tree.notes.single()

        vm.deleteToTrash(n.path, n.parent)
        vm.openTrash()
        awaitCondition("trash surfaces note even when opened immediately") {
            vm.uiState.value.screen == Screen.TRASH && vm.uiState.value.trashNotes.isNotEmpty()
        }
    }
}