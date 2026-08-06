package com.marsglorious.blacknote

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.marsglorious.blacknote.data.TRASH_FOLDER_NAME
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Compose UI tests for delete-to-trash flow on a virtual Android device.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class TrashUiTest : BlackNoteUiTestBase() {

    @Test
    fun trashScreen_emptyState() {
        openTrashFromMenu()
        composeTestRule.onNodeWithText("Trash is empty").assertIsDisplayed()
        tapBack()
    }

    @Test
    fun deleteNote_appearsInTrash() {
        seedOnDisk("TrashMe.md", "delete this note")
        waitForText("TrashMe")

        composeTestRule.onNodeWithTag("note_card_TrashMe")
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Delete").performClick()
        composeTestRule.waitForIdle()
        Thread.sleep(800)

        openTrashFromMenu()
        composeTestRule.onNodeWithText("TrashMe").assertIsDisplayed()
        composeTestRule.onNodeWithText("Restore").assertIsDisplayed()

        val inTrash = File(storeRule.rootDir, "$TRASH_FOLDER_NAME/TrashMe.md").exists() ||
            File(storeRule.rootDir, ".Trash/TrashMe.md").exists()
        assertTrue("note must be on disk in Trash", inTrash)
    }

    @Test
    fun deletedNote_removedFromMainList() {
        seedOnDisk("Gone.md", "gone")
        waitForText("Gone")
        composeTestRule.onNodeWithTag("note_card_Gone")
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Delete").performClick()
        Thread.sleep(800)
        composeTestRule.waitForIdle()

        assertFalse(
            runCatching {
                composeTestRule.onNodeWithTag("note_card_Gone").fetchSemanticsNode()
                true
            }.getOrDefault(false)
        )
    }

    @Test
    fun restoreNote_returnsToMainList() {
        seedOnDisk("RestoreMe.md", "restore")
        waitForText("RestoreMe")
        composeTestRule.onNodeWithTag("note_card_RestoreMe")
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Delete").performClick()
        Thread.sleep(800)

        openTrashFromMenu()
        composeTestRule.onNodeWithText("Restore").performClick()
        Thread.sleep(600)
        tapBack()
        waitForText("RestoreMe")
    }
}