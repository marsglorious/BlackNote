package com.marsglorious.blacknote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.marsglorious.blacknote.data.TRASH_FOLDER_NAME
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for delete-to-trash, trash listing, restore, and permanent delete.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class TrashInstrumentedTest : InstrumentedVmTestBase() {

    @Test
    fun deleteToTrash_movesFileToTrashFolder() = runBlocking {
        seedNote("Doomed.md", "goodbye world")
        refreshAndSettle()
        val doomed = vm.uiState.value.tree.notes.single { it.title == "Doomed" }

        vm.deleteToTrash(doomed.path, doomed.parent)
        awaitRefreshSettled()
        awaitCondition("file in Trash on disk") {
            File(rootDir, "$TRASH_FOLDER_NAME/Doomed.md").exists() ||
                File(rootDir, ".Trash/Doomed.md").exists()
        }

        assertFalse(vm.uiState.value.tree.notes.any { it.title == "Doomed" })
    }

    @Test
    fun deletedNote_appearsOnTrashScreen() = runBlocking {
        seedNote("Gone.md", "trash me")
        refreshAndSettle()
        val note = vm.uiState.value.tree.notes.single { it.title == "Gone" }

        vm.deleteToTrash(note.path, note.parent)
        vm.openTrash()
        awaitTrashContains("Gone")
    }

    @Test
    fun deleteThenOpenTrash_immediatelyShowsNote() = runBlocking {
        seedNote("Quick.md", "fast delete")
        refreshAndSettle()
        val note = vm.uiState.value.tree.notes.single()

        vm.deleteToTrash(note.path, note.parent)
        vm.openTrash()
        awaitCondition("trash populated immediately") {
            vm.uiState.value.trashNotes.isNotEmpty()
        }
    }

    @Test
    fun restoreFromTrash_returnsNoteToMainList() = runBlocking {
        seedNote("Return.md", "restore me")
        refreshAndSettle()
        val note = vm.uiState.value.tree.notes.single()
        vm.deleteToTrash(note.path, note.parent)
        vm.openTrash()
        awaitTrashContains("Return")

        val trashed = vm.uiState.value.trashNotes.single()
        vm.restoreFromTrash(trashed.path)
        awaitRefreshSettled()
        vm.backToList()
        awaitList()
        awaitListContains("Return")
    }

    @Test
    fun deletePermanently_removesFromTrash() = runBlocking {
        seedNote("Forever.md", "gone forever")
        refreshAndSettle()
        val note = vm.uiState.value.tree.notes.single()
        vm.deleteToTrash(note.path, note.parent)
        vm.openTrash()
        awaitTrashContains("Forever")

        val trashed = vm.uiState.value.trashNotes.single()
        // Permanent delete now goes through an explicit confirmation step.
        vm.requestDeletePermanently(trashed.path)
        vm.confirmDeletePermanently()
        awaitCondition("trash empty after permanent delete") {
            vm.uiState.value.trashNotes.isEmpty()
        }
        assertFalse(trashFile("Forever")?.exists() == true)
    }
}