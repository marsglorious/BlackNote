package com.marsglorious.blacknote

import androidx.compose.ui.text.input.TextFieldValue
import com.marsglorious.blacknote.viewmodel.EditorHistory
import com.marsglorious.blacknote.viewmodel.EditorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorHistoryTest {
    private fun body(s: String) = EditorSnapshot(body = TextFieldValue(s))

    @Test
    fun recordsAndUndos() {
        val history = EditorHistory()
        history.record(body("a"), now = 0)
        history.record(body("abc"), now = 10000) // delta >1 to avoid coalesce
        assertTrue(history.canUndo)
        val prev = history.undo()
        assertEquals("a", prev?.body?.text)
    }

    @Test
    fun coalescesTyping() {
        val history = EditorHistory()
        history.record(body("h"))
        history.record(body("he"))
        history.record(body("hel"))
        // should be one entry after coalesce
        history.undo()
        assertFalse(history.canUndo)
    }

    @Test
    fun undoRevertsLastEditedFieldFirst() {
        // Regression: the old two-stack design always drained BODY history before
        // TITLE history — undo after a title edit incorrectly reverted the body.
        val history = EditorHistory()
        history.reset(EditorSnapshot(TextFieldValue("Title"), TextFieldValue("Body")))
        history.record(EditorSnapshot(TextFieldValue("Title"), TextFieldValue("Body more")), now = 0)
        history.record(EditorSnapshot(TextFieldValue("Title edited"), TextFieldValue("Body more")), now = 10000)

        val afterFirstUndo = history.undo()
        assertEquals("first undo must revert the title (edited last)",
            "Title", afterFirstUndo?.title?.text)
        assertEquals("body must survive the first undo",
            "Body more", afterFirstUndo?.body?.text)

        val afterSecondUndo = history.undo()
        assertEquals("Body", afterSecondUndo?.body?.text)
    }

    @Test
    fun redoRestoresBothFields() {
        val history = EditorHistory()
        history.reset(EditorSnapshot(TextFieldValue("T"), TextFieldValue("B")))
        history.record(EditorSnapshot(TextFieldValue("T2"), TextFieldValue("B")), now = 0)
        history.undo()
        val redone = history.redo()
        assertEquals("T2", redone?.title?.text)
        assertEquals("B", redone?.body?.text)
    }

    @Test
    fun performanceManyRecords() {
        val history = EditorHistory()
        val start = System.currentTimeMillis()
        repeat(1000) { i ->
            history.record(body("text$i"))
        }
        val dur = System.currentTimeMillis() - start
        assertTrue("Performance: 1000 records took ${dur}ms", dur < 100)
    }
}
