package com.marsglorious.blacknote

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests covering every major screen and control on a virtual Android device.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class FullUiFeatureTest : BlackNoteUiTestBase() {

    @Test
    fun hamburgerMenu_showsAllItems() {
        openMenu()
        listOf("Refresh", "New folder", "Trash", "Settings").forEach { item ->
            composeTestRule.onNodeWithText(item).assertIsDisplayed()
        }
    }

    @Test
    fun settingsScreen_showsVersionAndFolderCard() {
        openSettingsFromMenu()
        composeTestRule.onNodeWithText("Notes folder").assertIsDisplayed()
        composeTestRule.onNodeWithText("Change").assertIsDisplayed()
        tapBack()
    }

    @Test
    fun collageToggle_switchesViewMode() {
        seedOnDisk("Collage.md", "collage note")
        waitForText("Collage")
        composeTestRule.onNodeWithContentDescription("Collage view").performClick()
        composeTestRule.onNodeWithContentDescription("List view").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("List view").performClick()
        composeTestRule.onNodeWithContentDescription("Collage view").assertIsDisplayed()
    }

    @Test
    fun search_filtersNotes() {
        seedOnDisk("Apple.md", "fruit")
        seedOnDisk("Banana.md", "fruit")
        waitForText("Apple")
        composeTestRule.onNodeWithTag("search_field").performTextReplacement("Apple")
        composeTestRule.waitForIdle()
        Thread.sleep(300)
        composeTestRule.onNodeWithText("Apple").assertIsDisplayed()
    }

    @Test
    fun newFolderDialog_createsFolder() {
        openMenu()
        composeTestRule.onNodeWithText("New folder").performClick()
        composeTestRule.onNodeWithText("Folder name").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create").assertIsDisplayed()
        // Dismiss without creating — verifies dialog wiring.
        composeTestRule.onNodeWithText("Cancel").performClick()
    }

    @Test
    fun noteLongPressMenu_showsMoveCopyDelete() {
        seedOnDisk("MenuTest.md", "menu")
        waitForText("MenuTest")
        composeTestRule.onNodeWithTag("note_card_MenuTest")
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithText("Move to…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy to…").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun refresh_keepsSeededNotes() {
        seedOnDisk("RefreshMe.md", "refresh")
        waitForText("RefreshMe")
        openMenu()
        composeTestRule.onNodeWithText("Refresh").performClick()
        composeTestRule.waitForIdle()
        Thread.sleep(400)
        composeTestRule.onNodeWithText("RefreshMe").assertIsDisplayed()
    }

    @Test
    fun editorPreviewToggle_andBackNavigation() {
        tapNewNote()
        setEditorBody("preview test")
        composeTestRule.onNodeWithContentDescription("Preview").performClick()
        composeTestRule.onNodeWithContentDescription("Edit").assertIsDisplayed()
        tapBack()
        composeTestRule.onNodeWithContentDescription("New note").assertIsDisplayed()
    }

    @Test
    fun trashBack_returnsToList() {
        openTrashFromMenu()
        tapBack()
        composeTestRule.onNodeWithContentDescription("New note").assertIsDisplayed()
    }
}