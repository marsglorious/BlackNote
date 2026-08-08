package com.marsglorious.blacknote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.marsglorious.blacknote.data.FormatKind
import com.marsglorious.blacknote.viewmodel.EditorMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for all markdown formatting toolbar actions on a virtual device.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class TextFormattingInstrumentedTest : InstrumentedVmTestBase() {

    @Test
    fun formatBold_wrapsSelection() = runBlocking {
        openEditorWithBody("hello world", selStart = 0, selEnd = 5)
        vm.format(FormatKind.BOLD)
        assertEquals("**hello** world", vm.uiState.value.editingBody.text)
    }

    @Test
    fun formatItalic_wrapsSelection() = runBlocking {
        openEditorWithBody("hello world", selStart = 6, selEnd = 11)
        vm.format(FormatKind.ITALIC)
        assertEquals("hello _world_", vm.uiState.value.editingBody.text)
    }

    @Test
    fun formatUnderline_wrapsSelection() = runBlocking {
        openEditorWithBody("line", selStart = 0, selEnd = 4)
        vm.format(FormatKind.UNDERLINE)
        assertEquals("<u>line</u>", vm.uiState.value.editingBody.text)
    }

    @Test
    fun formatStrikethrough_wrapsSelection() = runBlocking {
        openEditorWithBody("strike", selStart = 0, selEnd = 6)
        vm.format(FormatKind.STRIKE)
        assertEquals("~~strike~~", vm.uiState.value.editingBody.text)
    }

    @Test
    fun formatBulletList_prefixesLines() = runBlocking {
        openEditorWithBody("one\ntwo", selStart = 0, selEnd = 7)
        vm.format(FormatKind.BULLET_LIST)
        assertTrue(vm.uiState.value.editingBody.text.contains("- one"))
        assertTrue(vm.uiState.value.editingBody.text.contains("- two"))
    }

    @Test
    fun formatOrderedList_prefixesLines() = runBlocking {
        openEditorWithBody("alpha\nbeta", selStart = 0, selEnd = 10)
        vm.format(FormatKind.ORDERED_LIST)
        val body = vm.uiState.value.editingBody.text
        assertTrue(body.contains("1. alpha"))
        assertTrue(body.contains("2. beta"))
    }

    @Test
    fun formatBold_noSelection_insertsEmptyMarkers() = runBlocking {
        openEditorWithBody("text", selStart = 4, selEnd = 4)
        vm.format(FormatKind.BOLD)
        assertEquals("text****", vm.uiState.value.editingBody.text)
    }

    @Test
    fun undo_redo_roundTrip() = runBlocking {
        openEditorWithBody("original", selStart = 0, selEnd = 8)
        vm.format(FormatKind.BOLD)
        assertTrue(vm.uiState.value.editingBody.text.contains("**"))
        vm.undo()
        assertEquals("original", vm.uiState.value.editingBody.text)
        vm.redo()
        assertTrue(vm.uiState.value.editingBody.text.contains("**original**"))
    }

    @Test
    fun formattedBody_persistsAfterCloseReopen() = runBlocking {
        openEditorWithBody("persist", selStart = 0, selEnd = 7)
        vm.format(FormatKind.BOLD)
        vm.onTitleChange(tfv("Format Test"))
        vm.saveNow()
        vm.closeEditor()
        awaitList()
        awaitRefreshSettled()

        val note = vm.uiState.value.tree.notes.single { it.title == "Format Test" }
        vm.openNote(note)
        awaitEditor()
        assertTrue(vm.uiState.value.editingBody.text.contains("**persist**"))
    }

    @Test
    fun toggleEditorMode_switchesBetweenEditAndRender() = runBlocking {
        vm.newNote()
        awaitEditor()
        vm.onBodyChange(tfv("# Heading\n\n**bold**"))
        assertEquals(EditorMode.EDIT, vm.uiState.value.editorMode)
        vm.toggleEditorMode()
        assertEquals(EditorMode.RENDER, vm.uiState.value.editorMode)
        vm.toggleEditorMode()
        assertEquals(EditorMode.EDIT, vm.uiState.value.editorMode)
    }

    @Test
    fun allFormats_appliedInSequence() = runBlocking {
        openEditorWithBody("word", selStart = 0, selEnd = 4)
        vm.format(FormatKind.BOLD)
        val afterBold = vm.uiState.value.editingBody.text
        vm.onBodyChange(tfv(afterBold, selectionStart = 0, selectionEnd = afterBold.length))
        vm.format(FormatKind.ITALIC)
        val body = vm.uiState.value.editingBody.text
        assertTrue("expected bold+italic markers, got '$body'", body.contains("**") && body.contains("_"))
    }

    private suspend fun openEditorWithBody(text: String, selStart: Int, selEnd: Int) {
        vm.newNote()
        awaitEditor()
        vm.onBodyChange(tfv(text, selStart, selEnd))
    }
}