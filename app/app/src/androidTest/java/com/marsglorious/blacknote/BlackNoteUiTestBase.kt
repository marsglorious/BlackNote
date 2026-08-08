package com.marsglorious.blacknote

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.marsglorious.blacknote.data.NoteRepository
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

/**
 * Base for full Compose UI tests on a managed Pixel 7 emulator (or any connected device).
 * [TestStoreRule] injects [TestFileStore] before [MainActivity] launches so tests skip the folder picker.
 */
abstract class BlackNoteUiTestBase {
    protected val storeRule = TestStoreRule()
    protected val composeTestRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: TestRule = RuleChain.outerRule(storeRule).around(composeTestRule)

    @Before
    fun waitForAppReady() {
        waitForBootstrap()
    }

    @After
    fun uiTearDown() {
        storeRule.cleanup()
    }

    protected fun waitForBootstrap(timeoutMs: Long = 10000) {
        composeTestRule.waitUntil(timeoutMs) {
            runCatching {
                composeTestRule.onNodeWithContentDescription("New note").fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
    }

    protected fun waitForText(text: String, timeoutMs: Long = 10000) {
        composeTestRule.waitUntil(timeoutMs) {
            runCatching {
                composeTestRule.onNode(hasText(text, substring = true)).fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
    }

    protected fun tapNewNote() {
        composeTestRule.onNodeWithContentDescription("New note").performClick()
        composeTestRule.waitUntil(8000) {
            runCatching {
                composeTestRule.onNodeWithTag("editor_title").fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
    }

    protected fun tapBack() {
        composeTestRule.onNodeWithContentDescription("Back").performClick()
    }

    protected fun openMenu() {
        composeTestRule.onNodeWithContentDescription("Menu").performClick()
    }

    protected fun setEditorTitle(title: String) {
        composeTestRule.onNodeWithTag("editor_title").performTextReplacement(title)
    }

    protected fun setEditorBody(body: String) {
        composeTestRule.onNodeWithTag("editor_body").performTextReplacement(body)
    }

    protected fun appendEditorBody(text: String) {
        composeTestRule.onNodeWithTag("editor_body").performTextInput(text)
    }

    protected fun openNoteByTitle(title: String) {
        waitForText(title)
        composeTestRule.onNodeWithTag("note_card_$title").performClick()
        composeTestRule.waitUntil(8000) {
            runCatching {
                composeTestRule.onNodeWithTag("editor_title").fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
    }

    protected fun closeEditorToList() {
        tapBack()
        composeTestRule.waitUntil(8000) {
            runCatching {
                composeTestRule.onNodeWithContentDescription("New note").fetchSemanticsNode()
                true
            }.getOrDefault(false)
        }
        Thread.sleep(700)
        composeTestRule.waitForIdle()
    }

    protected fun openTrashFromMenu() {
        openMenu()
        composeTestRule.onNodeWithText("Trash").performClick()
        composeTestRule.waitUntil(8000) {
            runCatching {
                composeTestRule.onNodeWithText("Trash is empty", substring = true).fetchSemanticsNode()
                true
            }.getOrDefault(false) ||
                runCatching {
                    composeTestRule.onNodeWithText("Restore", substring = true).fetchSemanticsNode()
                    true
                }.getOrDefault(false)
        }
    }

    protected fun openSettingsFromMenu() {
        openMenu()
        composeTestRule.onNodeWithText("Settings").performClick()
        waitForText("Notes folder")
    }

    protected fun seedOnDisk(fileName: String, body: String) {
        storeRule.seed(fileName, body)
        openMenu()
        composeTestRule.onNodeWithText("Refresh").performClick()
        composeTestRule.waitForIdle()
        Thread.sleep(400)
    }

    class TestStoreRule : TestRule {
        lateinit var rootDir: File
            private set

        override fun apply(base: Statement, description: Description): Statement {
            return object : Statement() {
                override fun evaluate() {
                    val ctx = ApplicationProvider.getApplicationContext<Context>()
                    rootDir = File(ctx.cacheDir, "bn-ui-${System.nanoTime()}").also { it.mkdirs() }
                    val app = ctx.applicationContext as App
                    val fs = TestFileStore(ctx, rootDir)
                    app.setRepositoryForTest(NoteRepository(ctx, app.searchIndex, fs))
                    try {
                        base.evaluate()
                    } finally {
                        cleanup()
                    }
                }
            }
        }

        fun seed(fileName: String, body: String) {
            File(rootDir, fileName).writeText(body)
        }

        fun cleanup() {
            if (::rootDir.isInitialized) rootDir.deleteRecursively()
        }
    }
}