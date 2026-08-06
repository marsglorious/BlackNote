package com.marsglorious.blacknote

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI stability and performance tests under rapid interaction on a virtual Android device.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RapidUsageUiTest : BlackNoteUiTestBase() {

    @Test
    fun rapidNewNoteAndBack_cyclesStayResponsive() {
        val start = System.currentTimeMillis()
        repeat(12) { i ->
            tapNewNote()
            setEditorTitle("Fast $i")
            setEditorBody("rapid body $i")
            closeEditorToList()
        }
        val dur = System.currentTimeMillis() - start
        assertTrue("12 UI create/close cycles took ${dur}ms (limit 45s)", dur < 45_000)
        composeTestRule.onNodeWithContentDescription("New note").assertIsDisplayed()
    }

    @Test
    fun rapidMenuNavigation_noCrash() {
        repeat(15) {
            openMenu()
            composeTestRule.onNodeWithText("Settings").performClick()
            composeTestRule.onNodeWithText("Notes folder").assertIsDisplayed()
            tapBack()
            openMenu()
            composeTestRule.onNodeWithText("Trash").performClick()
            tapBack()
        }
        composeTestRule.onNodeWithContentDescription("New note").assertIsDisplayed()
    }

    @Test
    fun rapidSearchTyping_staysResponsive() {
        repeat(5) { i ->
            seedOnDisk("Search$i.md", "searchable $i")
        }
        waitForText("Search0")
        val start = System.currentTimeMillis()
        repeat(20) { n ->
            composeTestRule.onNodeWithTag("search_field").performTextReplacement("Search${n % 5}")
            composeTestRule.waitForIdle()
        }
        val dur = System.currentTimeMillis() - start
        assertTrue("20 search updates took ${dur}ms (limit 15s)", dur < 15_000)
        composeTestRule.onNodeWithTag("search_field").performTextReplacement("")
    }

    @Test
    fun rapidOpenCloseSameNote_stable() {
        tapNewNote()
        setEditorTitle("Stable Tap")
        setEditorBody("content")
        closeEditorToList()
        repeat(8) {
            openNoteByTitle("Stable Tap")
            closeEditorToList()
        }
        waitForText("Stable Tap")
    }

    @Test
    fun rapidCollageToggle_stable() {
        seedOnDisk("Toggle.md", "toggle")
        waitForText("Toggle")
        repeat(10) {
            composeTestRule.onNodeWithContentDescription("Collage view").performClick()
            composeTestRule.onNodeWithContentDescription("List view").performClick()
        }
        composeTestRule.onNodeWithContentDescription("Collage view").assertIsDisplayed()
    }
}