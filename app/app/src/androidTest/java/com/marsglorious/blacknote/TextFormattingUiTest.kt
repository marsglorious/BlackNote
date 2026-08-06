package com.marsglorious.blacknote

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Compose UI tests for the editor formatting toolbar on a virtual Android device.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class TextFormattingUiTest : BlackNoteUiTestBase() {

    @Test
    fun formatToolbar_allButtonsVisible() {
        tapNewNote()
        listOf(
            "Undo", "Redo",
            "Format bold", "Format italic", "Format underline", "Format strikethrough",
            "Format ordered list", "Format bullet list",
        ).forEach { label ->
            composeTestRule.onNodeWithContentDescription(label).assertIsDisplayed()
        }
    }

    @Test
    fun formatBold_viaToolbar_persistsOnDisk() {
        tapNewNote()
        setEditorTitle("Bold Note")
        setEditorBody("hello")
        composeTestRule.onNodeWithTag("editor_body").performTextReplacement("hello")
        composeTestRule.onNodeWithContentDescription("Format bold").performClick()
        closeEditorToList()
        openNoteByTitle("Bold Note")
        val file = File(storeRule.rootDir, "Bold Note.md")
        assertTrue("bold markers should persist: ${file.readText()}", file.readText().contains("**"))
    }

    @Test
    fun formatItalic_viaToolbar_persistsOnDisk() {
        tapNewNote()
        setEditorTitle("Italic Note")
        setEditorBody("word")
        composeTestRule.onNodeWithContentDescription("Format italic").performClick()
        closeEditorToList()
        val file = File(storeRule.rootDir, "Italic Note.md")
        assertTrue(file.readText().contains("_"))
    }

    @Test
    fun formatUnderline_viaToolbar_persistsOnDisk() {
        tapNewNote()
        setEditorTitle("Underline Note")
        setEditorBody("line")
        composeTestRule.onNodeWithContentDescription("Format underline").performClick()
        closeEditorToList()
        val file = File(storeRule.rootDir, "Underline Note.md")
        assertTrue(file.readText().contains("<u>"))
    }

    @Test
    fun formatStrikethrough_viaToolbar_persistsOnDisk() {
        tapNewNote()
        setEditorTitle("Strike Note")
        setEditorBody("gone")
        composeTestRule.onNodeWithContentDescription("Format strikethrough").performClick()
        closeEditorToList()
        val file = File(storeRule.rootDir, "Strike Note.md")
        assertTrue(file.readText().contains("~~"))
    }

    @Test
    fun formatBulletList_viaToolbar_persistsOnDisk() {
        tapNewNote()
        setEditorTitle("Bullet Note")
        setEditorBody("item")
        composeTestRule.onNodeWithContentDescription("Format bullet list").performClick()
        closeEditorToList()
        val file = File(storeRule.rootDir, "Bullet Note.md")
        assertTrue(file.readText().contains("- "))
    }

    @Test
    fun formatOrderedList_viaToolbar_persistsOnDisk() {
        tapNewNote()
        setEditorTitle("Ordered Note")
        setEditorBody("item")
        composeTestRule.onNodeWithContentDescription("Format ordered list").performClick()
        closeEditorToList()
        val file = File(storeRule.rootDir, "Ordered Note.md")
        assertTrue(file.readText().contains("1. "))
    }

    @Test
    fun previewToggle_switchesMode() {
        tapNewNote()
        setEditorBody("**bold**")
        composeTestRule.onNodeWithContentDescription("Preview").performClick()
        composeTestRule.onNodeWithContentDescription("Edit").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Edit").performClick()
        composeTestRule.onNodeWithContentDescription("Preview").assertIsDisplayed()
    }
}