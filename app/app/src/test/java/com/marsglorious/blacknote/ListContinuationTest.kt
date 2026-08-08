package com.marsglorious.blacknote

import android.content.Context
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import com.marsglorious.blacknote.data.NoteRepository
import com.marsglorious.blacknote.data.TreeSnapshot
import com.marsglorious.blacknote.viewmodel.AppViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for automatic list continuation on Enter.
 *
 * Standard editor behaviour (Notion, Bear, Obsidian, Apple Notes):
 *  - Enter on "- item"  → new line starts with "- "
 *  - Enter on "1. item" → new line starts with "2. "
 *  - Enter on "- "      → remove the empty bullet and exit list mode
 *  - Enter on "1. "     → remove the empty item and exit list mode
 *  - Enter on normal prose → no change
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ListContinuationTest {

    private lateinit var vm: AppViewModel
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val repo = object : NoteRepository(ctx, null) {
            override suspend fun refreshTree() = TreeSnapshot.EMPTY
            override suspend fun read(path: String) = ""
        }
        vm = AppViewModel(ctx as App, repo)
        vm.openNoteRaw("p", "root", "")
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    /** Simulates the user pressing Enter while editing the body. */
    private fun typeEnter(before: String, cursorAt: Int): TextFieldValue {
        val afterText = before.substring(0, cursorAt) + "\n" + before.substring(cursorAt)
        val newCursor = cursorAt + 1
        vm.onBodyChange(TextFieldValue(afterText, TextRange(newCursor)))
        return vm.uiState.value.editingBody
    }

    /** Simulates typing from scratch: set the body, then append \n at the end. */
    private fun pressEnterAfter(text: String): TextFieldValue {
        vm.onBodyChange(TextFieldValue(text, TextRange(text.length)))
        return typeEnter(text, text.length)
    }

    // ── Bullet list ────────────────────────────────────────────────────────────

    @Test
    fun bulletList_enterAfterItem_continuesWithNewBullet() {
        val result = pressEnterAfter("- first item")
        assertEquals("- first item\n- ", result.text)
        assertEquals(15, result.selection.start)  // cursor after "- "
    }

    @Test
    fun bulletList_enterOnEmptyItem_exitsList() {
        val result = pressEnterAfter("- first\n- ")
        // The empty "- " is stripped and the newline is removed
        assertEquals("- first\n", result.text)
        assertEquals(8, result.selection.start)
    }

    @Test
    fun bulletList_enterMidDocument_insertsNextBullet() {
        // "- a\n- b" with cursor at end of first item
        val text = "- a\n- b"
        vm.onBodyChange(TextFieldValue(text, TextRange(3)))
        val result = typeEnter(text, 3)
        assertEquals("- a\n- \n- b", result.text)
        assertEquals(6, result.selection.start)  // cursor sits right after the inserted "- "
    }

    // ── Ordered list ───────────────────────────────────────────────────────────

    @Test
    fun orderedList_enterAfterFirstItem_addsSecondNumber() {
        val result = pressEnterAfter("1. first item")
        assertEquals("1. first item\n2. ", result.text)
        assertEquals(17, result.selection.start)
    }

    @Test
    fun orderedList_enterAfterNinthItem_addsTenthNumber() {
        val result = pressEnterAfter("9. ninth item")
        assertEquals("9. ninth item\n10. ", result.text)
        assertEquals(18, result.selection.start)
    }

    @Test
    fun orderedList_enterOnEmptyItem_exitsList() {
        val result = pressEnterAfter("1. first\n2. ")
        assertEquals("1. first\n", result.text)
        assertEquals(9, result.selection.start)
    }

    // ── Non-list lines ─────────────────────────────────────────────────────────

    @Test
    fun plainText_enterDoesNotModify() {
        val result = pressEnterAfter("just some prose")
        assertEquals("just some prose\n", result.text)
        assertEquals(16, result.selection.start)
    }

    @Test
    fun headingLine_enterDoesNotContinueAsList() {
        val result = pressEnterAfter("# My heading")
        assertEquals("# My heading\n", result.text)
    }

    // ── Selection (multi-char) ─────────────────────────────────────────────────

    @Test
    fun enterWithActiveSelection_doesNotApplyListLogic() {
        // If text is selected and replaced with \n, don't try to continue a list
        val before = "- item"
        vm.onBodyChange(TextFieldValue(before, TextRange(0, before.length)))
        // Replace whole selection with \n (net change is -5, not +1)
        vm.onBodyChange(TextFieldValue("\n", TextRange(1)))
        assertEquals("\n", vm.uiState.value.editingBody.text)
    }
}
