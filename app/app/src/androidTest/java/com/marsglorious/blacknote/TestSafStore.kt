package com.marsglorious.blacknote

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.marsglorious.blacknote.data.SafStore
import java.io.File

/**
 * File-backed [SafStore] for instrumented tests. Bypasses the SAF picker entirely so we
 * can drive [com.marsglorious.blacknote.viewmodel.AppViewModel] against a real on-device
 * directory and watch the production code path do its thing — write, rename, move,
 * delete, refresh — without needing the user to grant SAF permissions inside an
 * automated test.
 *
 * Everything else (NoteRepository, FTS index, ViewModel) is the production class
 * unchanged. This is the closest a JVM-controllable test can get to the real device
 * experience: real Android, real filesystem, real Compose UI if the test wants it.
 *
 * Construct one per test with a [rootDir] that the test creates and tears down.
 */
class TestSafStore(ctx: Context, private val rootDir: File) : SafStore(ctx) {
    init {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    private val testTreeUri: Uri = Uri.fromFile(rootDir)

    override suspend fun getTreeUri(): Uri = testTreeUri

    override fun root(uri: Uri): DocumentFile? = DocumentFile.fromFile(rootDir)

    override fun treeDoc(uri: Uri): DocumentFile? {
        val f = uri.toFile() ?: return null
        if (!f.exists() && !f.mkdirs()) return null
        return DocumentFile.fromFile(f)
    }

    override fun singleDoc(uri: Uri): DocumentFile? {
        val f = uri.toFile() ?: return null
        return DocumentFile.fromFile(f)
    }

    // --- IO -------------------------------------------------------------

    override fun readText(uri: Uri): String =
        uri.toFile()?.takeIf { it.exists() && it.isFile }?.readText() ?: ""

    override fun writeText(uri: Uri, text: String): Boolean {
        val f = uri.toFile() ?: return false
        return runCatching { f.writeText(text); true }.getOrDefault(false)
    }

    override fun createMarkdown(parent: DocumentFile, fileName: String): DocumentFile? {
        val safe = fileName.replace(Regex("[^A-Za-z0-9 _\\-]"), "").ifBlank { "Untitled" }
        val parentFile = parent.uri.toFile() ?: return null
        if (!parentFile.exists()) parentFile.mkdirs()
        val child = File(parentFile, "$safe.md")
        if (!child.exists()) child.createNewFile()
        return DocumentFile.fromFile(child)
    }

    override fun ensureSubfolder(parent: DocumentFile, name: String): DocumentFile? {
        val parentFile = parent.uri.toFile() ?: return null
        val sub = File(parentFile, name)
        if (!sub.exists() && !sub.mkdirs()) return null
        return DocumentFile.fromFile(sub)
    }

    override fun moveDocument(sourceUri: Uri, sourceParentUri: Uri, targetParentUri: Uri): Uri? {
        val src = sourceUri.toFile() ?: return null
        val tgtDir = targetParentUri.toFile() ?: return null
        if (!tgtDir.exists()) tgtDir.mkdirs()
        val dest = File(tgtDir, src.name)
        return if (src.renameTo(dest)) Uri.fromFile(dest) else null
    }

    override fun copyDocument(sourceUri: Uri, targetParentUri: Uri): Uri? {
        val src = sourceUri.toFile() ?: return null
        val tgtDir = targetParentUri.toFile() ?: return null
        if (!tgtDir.exists()) tgtDir.mkdirs()
        val dest = File(tgtDir, src.name)
        return runCatching { src.copyTo(dest, overwrite = false); Uri.fromFile(dest) }.getOrNull()
    }

    override fun deleteDocument(uri: Uri): Boolean = uri.toFile()?.delete() == true

    override fun renameDocument(uri: Uri, displayName: String): Uri? {
        val src = uri.toFile() ?: return null
        val dest = File(src.parentFile, displayName)
        return if (src.renameTo(dest)) Uri.fromFile(dest) else null
    }

    private fun Uri.toFile(): File? = if (scheme == "file") path?.let(::File) else null
}
