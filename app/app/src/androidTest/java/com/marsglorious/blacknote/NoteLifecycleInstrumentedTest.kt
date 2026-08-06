package com.marsglorious.blacknote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for note open/close, creation, and persistence on a virtual device.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NoteLifecycleInstrumentedTest : InstrumentedVmTestBase() {

    @Test
    fun newNote_opensEditor() = runBlocking {
        vm.newNote()
        awaitEditor()
        assertTrue(vm.uiState.value.editingPath != null)
    }

    @Test
    fun closeEditor_returnsToList() = runBlocking {
        vm.newNote()
        awaitEditor()
        vm.closeEditor()
        awaitList()
    }

    @Test
    fun createNote_closeAndReopen_persistsContent() = runBlocking {
        vm.newNote()
        awaitEditor()
        vm.onTitleChange(tfv("Meeting Notes"))
        vm.onBodyChange(tfv("Agenda item one"))
        vm.saveNow()
        vm.closeEditor()
        awaitList()
        awaitRefreshSettled()

        awaitListContains("Meeting Notes")
        val note = vm.uiState.value.tree.notes.first { it.title == "Meeting Notes" }
        vm.openNote(note)
        awaitEditor()
        assertEquals("Meeting Notes", vm.uiState.value.editingTitle.text)
        assertTrue(vm.uiState.value.editingBody.text.contains("Agenda item one"))
    }

    @Test
    fun createdNote_appearsOnDiskAndInList() = runBlocking {
        vm.newNote()
        awaitEditor()
        vm.onTitleChange(tfv("On Disk"))
        vm.onBodyChange(tfv("saved body"))
        vm.saveNow()
        vm.closeEditor()
        awaitList()
        awaitRefreshSettled()

        assertTrue(File(rootDir, "On Disk.md").exists())
        awaitListContains("On Disk")
    }

    @Test
    fun openExistingNote_closeWithoutEdits_stillPresent() = runBlocking {
        seedNote("Stable.md", "stable content")
        refreshAndSettle()
        val note = vm.uiState.value.tree.notes.single { it.title == "Stable" }
        vm.openNote(note)
        awaitEditor()
        vm.closeEditor()
        awaitList()
        awaitRefreshSettled()
        awaitListContains("Stable")
    }

    @Test
    fun multipleNotes_allSurviveCloseReopenCycle() = runBlocking {
        listOf("Alpha", "Beta", "Gamma").forEach { title ->
            vm.newNote()
            awaitEditor()
            vm.onTitleChange(tfv(title))
            vm.onBodyChange(tfv("body for $title"))
            vm.saveNow()
            vm.closeEditor()
            awaitList()
            awaitRefreshSettled()
        }
        assertEquals(3, vm.uiState.value.tree.notes.size)
        vm.uiState.value.tree.notes.forEach { note ->
            vm.openNote(note)
            awaitEditor()
            assertFalse(vm.uiState.value.editingBody.text.isBlank())
            vm.closeEditor()
            awaitList()
        }
    }

    @Test
    fun renameOnClose_updatesListTitle() = runBlocking {
        vm.newNote()
        awaitEditor()
        vm.onTitleChange(tfv("Original"))
        vm.onBodyChange(tfv("text"))
        vm.saveNow()
        vm.closeEditor()
        awaitList()
        awaitRefreshSettled()

        val n = vm.uiState.value.tree.notes.single { it.title == "Original" }
        vm.openNote(n)
        awaitEditor()
        vm.onTitleChange(tfv("Renamed"))
        vm.saveNow()
        vm.closeEditor()
        awaitList()
        awaitRefreshSettled()

        assertFalse(vm.uiState.value.tree.notes.any { it.title == "Original" })
        awaitListContains("Renamed")
        assertTrue(File(rootDir, "Renamed.md").exists())
    }
}