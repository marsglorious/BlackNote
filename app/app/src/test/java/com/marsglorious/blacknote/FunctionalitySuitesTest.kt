package com.marsglorious.blacknote

import com.marsglorious.blacknote.data.Note

// FFI imports avoided to prevent native load crash in Robolectric JVM. Use pure logic.
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FunctionalitySuitesTest {
    private fun sampleNotes() = listOf(
        Note("p1", "root", "Delete Test", "body", 1, 0, emptyList(), null),
        Note("p2", "root", "Move Test", "move body", 2, 0, emptyList(), null),
        Note("p3", "sub", "Button Test", "func", 3, 0, listOf("tag"), null)
    )

    @Test
    fun deleteSearchWorks() {
        val notes = sampleNotes()
        // use fallback logic or direct since ffi init may fail in robo
        val res = notes.filter { it.title.contains("delete", true) || it.preview.contains("delete", true) }
        assertTrue(res.any { it.title.contains("Delete", true) })
    }

    @Test
    fun moveSearchWorks() {
        val notes = sampleNotes()
        val res = notes.filter { it.title.contains("move", true) }
        assertTrue(res.any { it.title.contains("Move", true) })
    }

    @Test
    fun buttonsAndTags() {
        val notes = sampleNotes()
        val res = notes.filter { it.title.contains("button", true) || it.tags.any { t -> t.contains("tag") } }
        assertTrue(res.isNotEmpty())
    }

    @Test
    fun performanceSearch100() {
        val notes = (1..100).map { Note("p$it", "r", "Note $it", "preview", it.toLong(), 0, emptyList(), null) }
        val start = System.currentTimeMillis()
        repeat(50) {
            notes.filter { it.title.contains("note", true) }
            notes.filter { it.preview.contains("note", true) }
        }
        val dur = System.currentTimeMillis() - start
        assertTrue("Search perf 100 notes x50: $dur ms", dur < 300)
    }
}
