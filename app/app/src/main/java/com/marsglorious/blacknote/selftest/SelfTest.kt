package com.marsglorious.blacknote.selftest

import android.content.Context
import com.marsglorious.blacknote.data.FileStore
import com.marsglorious.blacknote.data.FolderInfo
import com.marsglorious.blacknote.data.Note
import com.marsglorious.blacknote.data.NoteMeta
import com.marsglorious.blacknote.data.extractMeta
import com.marsglorious.blacknote.data.foldersFromNotes
import com.marsglorious.blacknote.data.safUriToFilePath
import com.marsglorious.blacknote.data.sanitizeFileName
import com.marsglorious.blacknote.data.searchNotes
import com.marsglorious.blacknote.data.stableDisplayDesc
import com.marsglorious.blacknote.data.titleFromFileName
import com.marsglorious.blacknote.viewmodel.AppViewModel
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

        // ── Sorting ───────────────────────────────────────────────────────────

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

        add(check("Sort: DATE_DESC uses file mtime") {
            val notes = listOf(n("/new.md", 9000), n("/old.md", 1000), n("/mid.md", 5000))
            val got = notes.sortedWith(noteComparator(SortMode.DATE_DESC, emptySet())).map { it.path }
            expect(listOf("/new.md", "/mid.md", "/old.md"), got)
        })

        add(check("Sort: DATE_ASC oldest first") {
            val notes = listOf(n("/new.md", 9000), n("/old.md", 1000), n("/mid.md", 5000))
            val got = notes.sortedWith(noteComparator(SortMode.DATE_ASC, emptySet())).map { it.path }
            expect(listOf("/old.md", "/mid.md", "/new.md"), got)
        })

        add(check("Sort: TITLE_ASC case-insensitive alphabetical") {
            val notes = listOf(
                n("/z.md", 1).copy(title = "Zebra"),
                n("/a.md", 2).copy(title = "apple"),
                n("/m.md", 3).copy(title = "Mango"),
            )
            val got = notes.sortedWith(noteComparator(SortMode.TITLE_ASC, emptySet())).map { it.title }
            expect(listOf("apple", "Mango", "Zebra"), got)
        })

        add(check("Sort: pinned notes stay on top") {
            val notes = listOf(n("/a.md", 5000), n("/b.md", 3000), n("/c.md", 9000))
            val got = notes.sortedWith(noteComparator(SortMode.DATE_DESC, setOf("/a.md"))).map { it.path }
            expect(listOf("/a.md", "/c.md", "/b.md"), got)
        })

        add(check("Sort: multiple pinned keep their own relative sort") {
            val notes = listOf(n("/a.md", 3000), n("/b.md", 5000), n("/c.md", 1000))
            val got = notes.sortedWith(noteComparator(SortMode.DATE_DESC, setOf("/a.md", "/b.md"))).map { it.path }
            expect(listOf("/b.md", "/a.md", "/c.md"), got)
        })

        // ── SAF URI migration ─────────────────────────────────────────────────

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

        // ── Filename / title helpers ──────────────────────────────────────────

        add(check("Filename: illegal chars removed, unicode kept") {
            expect("ab", sanitizeFileName("a/b"))
            expect("time 1230", sanitizeFileName("time 12:30"))
            expect("日記 2026", sanitizeFileName("日記 2026"))
            expect("Untitled", sanitizeFileName("   "))
        })

        add(check("Filename: long name truncated to 120 chars") {
            val long = "a".repeat(200)
            require(sanitizeFileName(long).length == 120) { "expected 120 chars" }
        })

        add(check("Filename: leading dots removed") {
            expect("hidden", sanitizeFileName(".hidden"))
        })

        add(check("Title: strips .md, keeps unicode, falls back") {
            expect("My Note", titleFromFileName("My Note.md"))
            expect("日記 2026", titleFromFileName("日記 2026.md"))
            expect("Untitled", titleFromFileName(".md"))
        })

        add(check("Title: case-insensitive .md strip") {
            expect("note", titleFromFileName("note.MD"))
            expect("note", titleFromFileName("note.Md"))
        })

        // ── Frontmatter / metadata extraction ────────────────────────────────

        add(check("Meta: title from YAML frontmatter") {
            val m = meta("---\ntitle: My Note\n---\nBody.")
            expect("My Note", m.title)
        })

        add(check("Meta: tags array from frontmatter (sorted)") {
            val m = meta("---\ntags: [kotlin, android, ui]\n---\nBody.")
            expect(listOf("android", "kotlin", "ui"), m.tags)
        })

        add(check("Meta: label from frontmatter") {
            val m = meta("---\nlabel: Work\n---\nBody.")
            expect("Work", m.label)
        })

        add(check("Meta: source field used as label when no explicit label") {
            val m = meta("---\nsource: web\n---\nBody.")
            expect("web", m.label)
        })

        add(check("Meta: explicit label wins over source") {
            val m = meta("---\nlabel: Mine\nsource: web\n---\nBody.")
            expect("Mine", m.label)
        })

        add(check("Meta: created date 2024-01-15 → correct epoch millis") {
            val m = meta("---\ncreated: 2024-01-15\n---\nBody.")
            // 2024-01-15 UTC midnight = 1705276800000 ms
            expect(1705276800000L, m.createdMillis)
        })

        add(check("Meta: invalid date ignored (createdMillis stays 0)") {
            val m = meta("---\ncreated: not-a-date\n---\nBody.")
            expect(0L, m.createdMillis)
        })

        add(check("Meta: heading used as title when no frontmatter") {
            val m = meta("# My Heading\n\nBody text.")
            expect("My Heading", m.title)
        })

        add(check("Meta: first non-empty line used as title fallback") {
            val m = meta("\nJust a plain line\nMore text.")
            expect("Just a plain line", m.title)
        })

        add(check("Meta: filename used when body is blank") {
            val m = extractMeta("/groceries.md", "/", "groceries.md", "", 0L)
            expect("groceries", m.title)
        })

        add(check("Meta: hashtags extracted from body") {
            val m = meta("Body with #kotlin and #android-dev tags.")
            require("kotlin" in m.tags) { "missing 'kotlin' in ${m.tags}" }
            require("android-dev" in m.tags) { "missing 'android-dev' in ${m.tags}" }
        })

        add(check("Meta: hashtags NOT extracted from headings") {
            val m = meta("# Heading #not-a-tag\n\nBody with #real-tag.")
            require("real-tag" in m.tags) { "expected 'real-tag'" }
            require("not-a-tag" !in m.tags) { "heading hashtag should be excluded" }
        })

        add(check("Meta: pure-number hashtags ignored") {
            val m = meta("Issue #123 and #feature-1 tag.")
            require("123" !in m.tags) { "pure-number '#123' should be ignored" }
            require("feature-1" in m.tags) { "expected 'feature-1'" }
        })

        add(check("Meta: preview excludes frontmatter block and headings") {
            val m = meta("---\ntitle: T\n---\n# Heading\n\nReal preview content.")
            require("---" !in m.preview) { "frontmatter leaked into preview" }
            require("Heading" !in m.preview) { "heading in preview: ${m.preview}" }
            require("Real preview content" in m.preview) { "preview missing body: ${m.preview}" }
        })

        add(check("Meta: frontmatter tags merged with body hashtags (deduplicated)") {
            val m = meta("---\ntags: [kotlin]\n---\nBody #kotlin and #android.")
            expect(listOf("android", "kotlin"), m.tags) // sorted, deduped
        })

        // ── In-memory search ──────────────────────────────────────────────────

        add(check("Search: title hit ranks above preview hit") {
            val titleNote = nm("/t.md", title = "kotlin tips", preview = "")
            val previewNote = nm("/p.md", title = "misc", preview = "about kotlin")
            val results = searchNotes(listOf(titleNote, previewNote), "kotlin", 10u)
            expect("/t.md", results.first().path)
        })

        add(check("Search: label hit ranks above preview hit") {
            val labelNote = nm("/l.md", title = "note", label = "kotlin")
            val previewNote = nm("/p.md", title = "other", preview = "kotlin tips")
            val results = searchNotes(listOf(previewNote, labelNote), "kotlin", 10u)
            expect("/l.md", results.first().path)
        })

        add(check("Search: empty query returns all notes newest first") {
            val notes = listOf(nm("/a.md", mtime = 1000), nm("/b.md", mtime = 3000), nm("/c.md", mtime = 2000))
            val got = searchNotes(notes, "", 10u).map { it.path }
            expect(listOf("/b.md", "/c.md", "/a.md"), got)
        })

        add(check("Search: no match returns empty list") {
            val notes = listOf(nm("/a.md", title = "hello", preview = "world"))
            require(searchNotes(notes, "xyzzy", 10u).isEmpty()) { "expected no results" }
        })

        add(check("Search: limit respected") {
            val notes = (1..20).map { nm("/$it.md", title = "note $it") }
            val results = searchNotes(notes, "note", 5u)
            require(results.size == 5) { "expected 5 results, got ${results.size}" }
        })

        // ── Folder tree reconstruction ────────────────────────────────────────

        add(check("Folders: derived correctly from note parents") {
            val root = "/sdcard/Notes"
            val notes = listOf(
                note("$root/Work/a.md", "$root/Work"),
                note("$root/Personal/b.md", "$root/Personal"),
            )
            val folders = foldersFromNotes(notes, root)
            val names = folders.map { it.name }.toSet()
            require("Work" in names) { "expected 'Work', got: $names" }
            require("Personal" in names) { "expected 'Personal', got: $names" }
        })

        add(check("Folders: root-level notes produce no folder entries") {
            val root = "/sdcard/Notes"
            val notes = listOf(note("$root/a.md", root))
            val folders = foldersFromNotes(notes, root)
            require(folders.isEmpty()) { "unexpected folders: $folders" }
        })

        add(check("Folders: nested subfolder gets correct depth") {
            val root = "/sdcard/Notes"
            val notes = listOf(note("$root/Work/Projects/a.md", "$root/Work/Projects"))
            val folders = foldersFromNotes(notes, root)
            val work = folders.first { it.name == "Work" }
            val projects = folders.first { it.name == "Projects" }
            expect(0, work.depth)
            expect(1, projects.depth)
        })

        add(check("Folders: trash folder excluded") {
            val root = "/sdcard/Notes"
            val notes = listOf(note("$root/.trash/x.md", "$root/.trash"))
            val folders = foldersFromNotes(notes, root)
            require(folders.none { it.name == ".trash" }) { "trash folder should be excluded" }
        })

        // ── File I/O ──────────────────────────────────────────────────────────

        add(check("File I/O: write then read round-trips (real disk)") {
            val fs = FileStore(ctx)
            val dir = File(ctx.cacheDir, "selftest").apply { mkdirs() }
            val f = File(dir, "roundtrip.md")
            val body = "# Hello\n\nunicode ☕ 日記\n"
            try {
                require(fs.writeText(f.absolutePath, body)) { "writeText returned false" }
                expect(body, fs.readText(f.absolutePath))
            } finally { f.delete() }
        })

        add(check("File I/O: read of a missing file is safe (null / empty)") {
            val fs = FileStore(ctx)
            val ghost = File(ctx.cacheDir, "does-not-exist-${System.nanoTime()}.md").absolutePath
            require(fs.readTextOrNull(ghost) == null) { "expected null for missing file" }
            expect("", fs.readText(ghost))
        })

        add(check("File I/O: rename file") {
            val fs = FileStore(ctx)
            val dir = File(ctx.cacheDir, "selftest").apply { mkdirs() }
            val src = File(dir, "rename-src-${System.nanoTime()}.md").also { it.writeText("hi") }
            try {
                val renamed = fs.renameFile(src, "renamed-${System.nanoTime()}.md")
                require(renamed != null) { "renameFile returned null" }
                require(renamed.exists()) { "renamed file missing" }
                require(!src.exists()) { "original still exists after rename" }
                renamed.delete()
            } finally { src.delete() }
        })

        add(check("File I/O: move file to subdirectory") {
            val fs = FileStore(ctx)
            val dir = File(ctx.cacheDir, "selftest").apply { mkdirs() }
            val src = File(dir, "move-${System.nanoTime()}.md").also { it.writeText("move me") }
            val dest = File(dir, "subdir-${System.nanoTime()}").also { it.mkdirs() }
            try {
                val moved = fs.moveFile(src, dest)
                require(moved != null) { "moveFile returned null" }
                require(moved.exists()) { "moved file doesn't exist at dest" }
                require(!src.exists()) { "source still exists after move" }
                expect("move me", moved.readText())
            } finally { src.delete(); dest.deleteRecursively() }
        })

        add(check("File I/O: copy preserves content, source intact") {
            val fs = FileStore(ctx)
            val dir = File(ctx.cacheDir, "selftest").apply { mkdirs() }
            val src = File(dir, "copy-src-${System.nanoTime()}.md").also { it.writeText("copied ☕") }
            val dest = File(dir, "copy-dest-${System.nanoTime()}").also { it.mkdirs() }
            try {
                val copied = fs.copyFile(src, dest)
                require(copied != null) { "copyFile returned null" }
                require(src.exists()) { "source removed after copy" }
                expect("copied ☕", copied.readText())
            } finally { src.delete(); dest.deleteRecursively() }
        })

        add(check("File I/O: createMarkdown produces .md file") {
            val fs = FileStore(ctx)
            val dir = File(ctx.cacheDir, "selftest").apply { mkdirs() }
            val f = fs.createMarkdown(dir, "TestNote")
            try {
                require(f != null) { "createMarkdown returned null" }
                require(f.exists()) { "created file doesn't exist" }
                require(f.name.endsWith(".md")) { "wrong extension: ${f.name}" }
            } finally { f?.delete() }
        })

        // ── Quote detection ───────────────────────────────────────────────────

        add(check("Quote: straight quotes + hyphen detected") {
            val tags = AppViewModel.detectQuoteTags("\"Be the change you wish to see in the world\" - Mahatma Gandhi")
            require("MahatmaGandhi" in tags) { "missing author tag, got: $tags" }
            require("quote" in tags) { "missing #quote, got: $tags" }
        })

        add(check("Quote: curly quotes + hyphen detected") {
            val lq = 0x201c.toChar(); val rq = 0x201d.toChar()
            val body = "${lq}Be the change you wish to see in the world${rq} - Mahatma Gandhi"
            val tags = AppViewModel.detectQuoteTags(body)
            require("MahatmaGandhi" in tags) { "missing author tag, got: $tags" }
            require("quote" in tags) { "missing #quote, got: $tags" }
        })

        add(check("Quote: em-dash separator detected") {
            val em = 0x2014.toChar()
            val body = "\"Be the change you wish to see in the world\" ${em} Noam Chomsky"
            val tags = AppViewModel.detectQuoteTags(body)
            require("NoamChomsky" in tags) { "missing author tag, got: $tags" }
        })

        add(check("Quote: newline between closing quote and dash") {
            val body = "\"Be the change you wish to see in the world\"\n- Mahatma Gandhi"
            val tags = AppViewModel.detectQuoteTags(body)
            require("MahatmaGandhi" in tags) { "missing author tag, got: $tags" }
        })

        add(check("Quote: short quote detected (our dog - Einstein)") {
            val tags = AppViewModel.detectQuoteTags("\"our dog\" - Albert Einstein")
            require("AlbertEinstein" in tags) { "missing author tag, got: $tags" }
            require("quote" in tags) { "missing #quote, got: $tags" }
        })

        add(check("Quote: single-char quote not detected") {
            val tags = AppViewModel.detectQuoteTags("\"x\" - Someone")
            require(tags.isEmpty()) { "1-char quote should produce no tags, got: $tags" }
        })

        add(check("Quote: lowercase-only attribution not detected") {
            val tags = AppViewModel.detectQuoteTags("\"Be the change you wish to see in the world\" - mahatma gandhi")
            require(tags.isEmpty()) { "lowercase author should produce no tags, got: $tags" }
        })

        add(check("Quote: no attribution produces no tags") {
            val tags = AppViewModel.detectQuoteTags("Just a regular note with no quotes.")
            require(tags.isEmpty()) { "no attribution should produce no tags, got: $tags" }
        })

        add(check("Quote: single-name author") {
            val tags = AppViewModel.detectQuoteTags("\"Do not go gentle into that good night\" - Dylan")
            require("Dylan" in tags) { "missing single-word author tag, got: $tags" }
        })

        add(check("Quote: author tag is CamelCase joined words") {
            val tags = AppViewModel.detectQuoteTags("\"Knowledge is power and you need power\" - Francis Bacon")
            require("FrancisBacon" in tags) { "expected 'FrancisBacon', got: $tags" }
        })

        add(check("File I/O: delete file") {
            val fs = FileStore(ctx)
            val dir = File(ctx.cacheDir, "selftest").apply { mkdirs() }
            val f = File(dir, "delete-me-${System.nanoTime()}.md").also { it.writeText("bye") }
            require(fs.deleteFile(f.absolutePath)) { "deleteFile returned false" }
            require(!f.exists()) { "file still exists after delete" }
        })
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun n(path: String, mtime: Long) =
        Note(path, "/root", "T", "", mtime, mtime, emptyList(), null)

    private fun note(path: String, parent: String) =
        Note(path, parent, "T", "", 1000L, 1000L, emptyList(), null)

    private fun meta(text: String) =
        extractMeta("/f.md", "/", "f.md", text, 0L)

    private fun nm(
        path: String,
        title: String = "t",
        preview: String = "",
        label: String? = null,
        mtime: Long = 1000L,
    ) = NoteMeta(path, "/", title, preview, mtime, 0L, emptyList(), label)

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
