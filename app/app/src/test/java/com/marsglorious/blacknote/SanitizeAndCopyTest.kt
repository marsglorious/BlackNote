package com.marsglorious.blacknote

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import com.marsglorious.blacknote.data.SafStore
import com.marsglorious.blacknote.data.sanitizeFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SanitizeAndCopyTest {

    // --- sanitizeFileName: the old whitelist [A-Za-z0-9 _-] destroyed non-English titles ---

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
        // Leading dots make SAF providers hide the file; trailing dots break FAT.
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
        val long = "x".repeat(500)
        assertTrue(sanitizeFileName(long).length <= 120)
    }

    // --- copyDocumentCompat: provider copyDocument lies (returns URI, writes nothing) ---

    /** In-memory SafStore: overrides the primitives so the compat logic runs against a map. */
    private class FakeSaf(
        ctx: Context,
        var copyDocumentResult: Uri?,
        val files: MutableMap<String, String>,
    ) : SafStore(ctx) {
        var deleted = mutableListOf<String>()
        override fun copyDocument(sourceUri: Uri, targetParentUri: Uri): Uri? = copyDocumentResult
        override fun readTextOrNull(uri: Uri): String? = files[uri.toString()]
        override fun writeText(uri: Uri, text: String): Boolean {
            files[uri.toString()] = text; return true
        }
        override fun deleteDocument(uri: Uri): Boolean {
            deleted.add(uri.toString())
            return files.remove(uri.toString()) != null
        }
        override fun singleDoc(uri: Uri): DocumentFile? = null
    }

    private fun targetDir(): DocumentFile {
        val dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "copytarget-${System.nanoTime()}")
        dir.mkdirs()
        return DocumentFile.fromFile(dir)
    }

    @Test
    fun copyFallsBackWhenProviderReturnsPhantomUri() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val files = mutableMapOf("file:///src/note.md" to "hello copy")
        // Provider claims success with a URI that has no readable file behind it.
        val saf = FakeSaf(ctx, copyDocumentResult = Uri.parse("content://phantom/doc"), files = files)

        val target = targetDir()
        val result = saf.copyDocumentCompat(Uri.parse("file:///src/note.md"), target)

        assertNotNull("manual fallback must produce a real copy", result)
        assertEquals("hello copy", files[result.toString()])
        // Source untouched — copy is not move.
        assertEquals("hello copy", files["file:///src/note.md"])
    }

    @Test
    fun copyAcceptsProviderResultWhenDestinationIsReadable() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val providerDest = Uri.parse("content://real/copied")
        val files = mutableMapOf(
            "file:///src/note.md" to "body",
            providerDest.toString() to "body",
        )
        val saf = FakeSaf(ctx, copyDocumentResult = providerDest, files = files)

        val result = saf.copyDocumentCompat(Uri.parse("file:///src/note.md"), targetDir())
        assertEquals(providerDest, result)
    }

    @Test
    fun copyReturnsNullWhenSourceUnreadable() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val saf = FakeSaf(ctx, copyDocumentResult = null, files = mutableMapOf())
        val result = saf.copyDocumentCompat(Uri.parse("file:///src/gone.md"), targetDir())
        assertNull(result)
    }
}
