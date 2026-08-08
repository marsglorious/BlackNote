package com.marsglorious.blacknote

import com.marsglorious.blacknote.data.sanitizeFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SanitizeAndCopyTest {

    @Test
    fun unicodeTitlesSurvive() {
        assertEquals("日記 2026", sanitizeFileName("日記 2026"))
        assertEquals("café notes", sanitizeFileName("café notes"))
        assertEquals("Überblick", sanitizeFileName("Überblick"))
        assertEquals("🎉 party plan", sanitizeFileName("🎉 party plan"))
    }

    @Test
    fun illegalFilesystemCharsRemoved() {
        assertEquals("ab", sanitizeFileName("a/b"))
        assertEquals("ab", sanitizeFileName("a\\b"))
        assertEquals("time 1230", sanitizeFileName("time 12:30"))
        assertEquals("what", sanitizeFileName("what?"))
        assertEquals("quoted", sanitizeFileName("\"quoted\""))
        assertEquals("pipe", sanitizeFileName("pipe|"))
    }

    @Test
    fun hiddenAndFatUnfriendlyNamesCleaned() {
        assertEquals("hidden", sanitizeFileName(".hidden"))
        assertEquals("trailing", sanitizeFileName("trailing. "))
    }

    @Test
    fun blankFallsBack() {
        assertEquals("Untitled", sanitizeFileName("   "))
        assertEquals("Untitled", sanitizeFileName("///"))
        assertEquals("Folder", sanitizeFileName("?", fallback = "Folder"))
    }

    @Test
    fun longNamesAreCapped() {
        assertTrue(sanitizeFileName("x".repeat(500)).length <= 120)
    }
}
