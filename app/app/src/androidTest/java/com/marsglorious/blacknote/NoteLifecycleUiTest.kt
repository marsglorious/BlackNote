package com.marsglorious.blacknote

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Compose UI tests for opening/closing notes and persistence on a virtual Android device.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class NoteLifecycleUiTest : BlackNoteUiTestBase() {

    @Test
    fun emptyState_showsPlaceholder() {
        composeTestRule.onNodeWithText("No notes yet").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("New note").assertIsDisplayed()
    }

    @Test
    fun tapNewNote_opensEditor() {
        tapNewNote()
        composeTestRule.onNodeWithTag("editor_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("editor_body").assertIsDisplayed()
    }

    @Test
    fun closeEditor_returnsToList() {
        tapNewNote()
        closeEditorToList()
        composeTestRule.onNodeWithContentDescription("New note").assertIsDisplayed()
    }

    @Test
    fun createNote_closeAndSeeInList() {
        tapNewNote()
        setEditorTitle("UI Created")
        setEditorBody("visible after close")
        closeEditorToList()
        waitForText("UI Created")
        composeTestRule.onNodeWithTag("note_card_UI Created").assertIsDisplayed()
        assertTrue(File(storeRule.rootDir, "UI Created.md").exists())
    }

    @Test
    fun openNote_editClose_reopenShowsEdits() {
        tapNewNote()
        setEditorTitle("Reopen Test")
        setEditorBody("first version")
        closeEditorToList()
        openNoteByTitle("Reopen Test")
        setEditorBody("second version")
        closeEditorToList()
        openNoteByTitle("Reopen Test")
        composeTestRule.onNodeWithTag("editor_body").assertIsDisplayed()
    }

    @Test
    fun openAndClose_multipleNotesInSuccession() {
        listOf("One", "Two", "Three").forEach { title ->
            tapNewNote()
            setEditorTitle(title)
            setEditorBody("body $title")
            closeEditorToList()
            waitForText(title)
        }
        composeTestRule.onNodeWithTag("note_card_One").assertIsDisplayed()
        composeTestRule.onNodeWithTag("note_card_Two").assertIsDisplayed()
        composeTestRule.onNodeWithTag("note_card_Three").assertIsDisplayed()
    }
}