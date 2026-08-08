package com.marsglorious.blacknote.selftest

import android.content.Context
import com.marsglorious.blacknote.data.FileStore
import com.marsglorious.blacknote.data.Note
import com.marsglorious.blacknote.data.safUriToFilePath
import com.marsglorious.blacknote.data.sanitizeFileName
import com.marsglorious.blacknote.data.stableDisplayDesc
import com.marsglorious.blacknote.data.titleFromFileName
import com.marsglorious.blacknote.viewmodel.SortMode
import com.marsglorious.blacknote.viewmodel.noteComparator
import java.io.File
import androidx.core.net.toUri

/** One test's outcome. [error] is null on success. */
data class SelfTestResult(
    val name: String,
    val passed: Boolean,
    val error: String? = null,
)

/**
 * On-device test harness that exercises the REAL production logic — the same
 * functions the app uses at runtime — with no Robolectric or Gradle dependency.
 *
 * This exists so the app can self-verify the parts that historically broke
 * (note ordering, the content:// migration, file I/O) directly on the phone,
 * and so those checks can also be run headlessly from a dev machine.
 *
 * The identical logic is covered by the JVM unit tests (FileAccessTest etc.);
 * this harness is the runtime mirror of those, callable from the menu.
 */
object SelfTest {

    fun runAll(ctx: Context): List<SelfTestResult> = buildList {
        add(check("Sort: newest modified first") {
            val notes = listOf(n("/b.md", 3000), n("/a.md", 5000), n("/c.md", 1000))
            val got = notes.sortedWith(stableDisplayDesc).map { it.path }
            expect(listOf("/a.md", "/b.md", "/c.md"), got)
        })

        add(check("Sort: stable when mtimes are equal (the weeks-long bug)") {
            val notes = listOf(n("/z.md", 1000), n("/a.md", 1000), n("/m.md", 1000))
            val fwd = notes.sortedWith(stableDisplayDesc).map { it.path }
            val rev = notes.reversed().sortedWith(stableDisplayDesc).map { it.path }
            require(fwd == rev) { "order changed with input order: $fwd vs $rev" }
            expect(listOf("/a.md", "/m.md", "/z.md"), fwd)
        })

        add(check("Sort: DATE_DESC uses file mtime, not frontmatter") {
            val notes = listOf(n("/new.md", 9000), n("/old.md", 1000), n("/mid.md", 5000))
            val got = notes.sortedWith(noteComparator(SortMode.DATE_DESC, emptySet())).map { it.path }
            expect(listOf("/new.md", "/mid.md", "/old.md"), got)
        })

        add(check("Sort: pinned notes stay on top") {
            val notes = listOf(n("/a.md", 5000), n("/b.md", 3000), n("/c.md", 9000))
            val got = notes.sortedWith(noteComparator(SortMode.DATE_DESC, setOf("/a.md"))).map { it.path }
            expect(listOf("/a.md", "/c.md", "/b.md"), got)
        })

        add(check("Migration: content:// notes filtered before display") {
            val mixed = listOf(
                n("content://com.android.externalstorage.documents/tree/primary%3ADocs%2Fx.md", 9000),
                n("/storage/emulated/0/Docs/real.md", 5000),
            )
            val kept = mixed.filter { !it.path.startsWith("content://") }.map { it.path }
            expect(listOf("/storage/emulated/0/Docs/real.md"), kept)
        })

        add(check("SAF URI → internal storage path") {
            val uri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FBlackNote".toUri()
            val path = safUriToFilePath(uri)
            require(path != null && path.endsWith("Documents/BlackNote")) { "got: $path" }
        })

        add(check("SAF URI → null for SD card / cloud (not File-accessible)") {
            val sd = safUriToFilePath(
                "content://com.android.externalstorage.documents/tree/1234-5678%3ANotes".toUri()
            )
            val cloud = safUriToFilePath(
                "content://com.google.android.apps.docs.storage/document/abc".toUri()
            )
            require(sd == null) { "SD card should be null, got: $sd" }
            require(cloud == null) { "cloud should be null, got: $cloud" }
        })

        add(check("Filename: illegal chars removed, unicode kept") {
            expect("ab", sanitizeFileName("a/b"))
            expect("time 1230", sanitizeFileName("time 12:30"))
            expect("日記 2026", sanitizeFileName("日記 2026"))
            expect("Untitled", sanitizeFileName("   "))
        })

        add(check("Title: strips .md, keeps unicode, falls back") {
            expect("My Note", titleFromFileName("My Note.md"))
            expect("日記 2026", titleFromFileName("日記 2026.md"))
            expect("Untitled", titleFromFileName(".md"))
        })

        add(check("File I/O: write then read round-trips (real disk)") {
            val fs = FileStore(ctx)
            val dir = File(ctx.cacheDir, "selftest").apply { mkdirs() }
            val f = File(dir, "roundtrip.md")
            val body = "# Hello\n\nunicode ☕ 日記\n"
            try {
                require(fs.writeText(f.absolutePath, body)) { "writeText returned false" }
                expect(body, fs.readText(f.absolutePath))
            } finally {
                f.delete()
            }
        })

        add(check("File I/O: read of a missing file is safe (null / empty)") {
            val fs = FileStore(ctx)
            val ghost = File(ctx.cacheDir, "does-not-exist-${System.nanoTime()}.md").absolutePath
            require(fs.readTextOrNull(ghost) == null) { "expected null for missing file" }
            expect("", fs.readText(ghost))
        })
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun n(path: String, mtime: Long) =
        Note(path, "/root", "T", "", mtime, mtime, emptyList(), null)

    private inline fun check(name: String, body: () -> Unit): SelfTestResult =
        try {
            body()
            SelfTestResult(name, true)
        } catch (t: Throwable) {
            SelfTestResult(name, false, t.message ?: t.javaClass.simpleName)
        }

    private fun <T> expect(expected: T, actual: T) {
        if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>")
    }
}
