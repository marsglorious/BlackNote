package com.marsglorious.blacknote.ui.list

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.marsglorious.blacknote.data.Note
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeleteButtonTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleNote = Note(
        path = "content://test/note1.md",
        parent = "content://test/root",
        title = "Test Note",
        preview = "preview",
        modifiedMillis = 0,
        createdMillis = 0,
        tags = emptyList(),
        label = null
    )

    @Test
    fun deleteMenuItem_callsOnDelete() {
        var deleteCalled = false

        composeTestRule.setContent {
            NoteCard(
                note = sampleNote,
                indent = 0,
                onClick = {},
                onLongClick = {},
                showMenu = true,
                onDismissMenu = {},
                onMove = {},
                onCopy = {},
                onDelete = { deleteCalled = true }
            )
        }

        // The delete item text
        composeTestRule.onNodeWithText("Delete").performClick()

        assertTrue("Delete button should trigger onDelete", deleteCalled)
    }

    @Test
    fun deleteMenuItem_isPresentInMenu() {
        composeTestRule.setContent {
            NoteCard(
                note = sampleNote,
                indent = 0,
                onClick = {},
                onLongClick = {},
                showMenu = true,
                onDismissMenu = {},
                onMove = {},
                onCopy = {},
                onDelete = {}
            )
        }

        composeTestRule.onNodeWithText("Delete").assertExists()
    }
}
