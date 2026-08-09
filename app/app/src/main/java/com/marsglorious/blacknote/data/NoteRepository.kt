package com.marsglorious.blacknote.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tree-aware note store. The on-disk source of truth is the folder the user picked;
 * we mirror everything we know into a SQLite index (see [SearchIndex]) that doubles as a
 * startup cache — so the list can render instantly from the index while a background file
 * scan refreshes the tree structure.
 *
 * Folders are derived from the file walk only — never persisted into the index — so the
 * index continues to deal only in flat notes (each note carries its `parent` path string,
 * which is enough to rebuild the tree on the Kotlin side).
 */
open class NoteRepository(
    private val ctx: Context,
    private val index: SearchIndex?,
    val fs: FileStore = FileStore(ctx),
) {
    open val hasNativeIndex: Boolean get() = index != null

    /** Instant — pulls whatever is currently cached in the FTS index. May be stale. */
    suspend fun cachedNotes(limit: Int = 20000): List<Note> = withContext(Dispatchers.IO) {
        if (index == null) return@withContext emptyList()
        runCatching {
            index.allSorted(limit.toUInt()).map { meta ->
                Note.fromMeta(meta).let { it.copy(preview = stripFrontmatter(it.preview)) }
            }
        }.getOrDefault(emptyList())
    }

    /** Walk the file tree, refresh the FTS index, return (notes, folders). */
    open suspend fun refreshTree(): TreeSnapshot = withContext(Dispatchers.IO) {
        val root = fs.getRootFile() ?: return@withContext TreeSnapshot.EMPTY
        val notes = mutableListOf<Note>()
        val folders = mutableListOf<FolderInfo>()
        val alivePaths = mutableListOf<String>()
        walk(root, rootPath = root.absolutePath, notes = notes, folders = folders, alivePaths = alivePaths)
        if (index != null) runCatching { index.retain(alivePaths) }
        TreeSnapshot(notes = notes.sortedWith(stableDisplayDesc), folders = folders)
    }

    /** Locate the trash folder by either current or legacy name. Does not create. */
    private fun findTrashFolder(root: File): File? =
        (File(root, TRASH_FOLDER_NAME).takeIf { it.isDirectory }
            ?: File(root, TRASH_FOLDER_LEGACY).takeIf { it.isDirectory })

    open suspend fun refreshTrash(): List<Note> = withContext(Dispatchers.IO) {
        val root = fs.getRootFile() ?: return@withContext emptyList()
        val trash = findTrashFolder(root) ?: return@withContext emptyList()
        val notes = mutableListOf<Note>()
        for (doc in trash.listFiles() ?: emptyArray()) {
            if (!doc.isFile || !doc.name.endsWith(".md", true)) continue
            val fileName = doc.name
            val mtime = doc.lastModified()
            notes += Note(
                path = doc.absolutePath,
                parent = trash.absolutePath,
                title = titleFromFileName(fileName),
                preview = "",
                modifiedMillis = mtime,
                createdMillis = mtime,
                tags = emptyList(),
                label = null,
            )
        }
        notes.sortedWith(stableDisplayDesc)
    }

    private fun walk(
        dir: File,
        rootPath: String,
        notes: MutableList<Note>,
        folders: MutableList<FolderInfo>,
        alivePaths: MutableList<String>,
        depth: Int = 0,
    ) {
        for (doc in dir.listFiles() ?: emptyArray()) {
            // Skip the trash folder (current or legacy name) and anything hidden.
            if (doc.isDirectory) {
                val n = doc.name
                if (n == TRASH_FOLDER_NAME || n == TRASH_FOLDER_LEGACY) continue
                if (n.startsWith(".")) continue
            }
            if (doc.isDirectory) {
                folders += FolderInfo(
                    path = doc.absolutePath,
                    parent = dir.absolutePath,
                    name = doc.name,
                    depth = depth,
                )
                walk(doc, rootPath, notes, folders, alivePaths, depth + 1)
            } else if (doc.isFile && doc.name.endsWith(".md", true)) {
                val fileName = doc.name
                val titleFromName = titleFromFileName(fileName)
                val path = doc.absolutePath
                val parent = dir.absolutePath
                val mtime = doc.lastModified()
                notes += Note(
                    path = path, parent = parent, title = titleFromName,
                    preview = "", modifiedMillis = mtime, createdMillis = mtime,
                    tags = emptyList(), label = null,
                )
                alivePaths += path
            }
        }
    }

    open suspend fun search(query: String, fallback: List<Note>, limit: Int = 200): List<Note> =
        withContext(Dispatchers.Default) {
            val q = query.trim()
            if (q.isEmpty()) return@withContext fallback
            if (index != null) {
                runCatching { index.query(q, limit.toUInt()).map { Note.fromMeta(it) } }
                    .getOrDefault(emptyList())
            } else {
                searchNotes(fallback.map { it.toMeta() }, q, limit.toUInt())
                    .map { Note.fromMeta(it) }
            }
        }

    open suspend fun read(path: String): String? = withContext(Dispatchers.IO) {
        fs.readTextOrNull(path)
    }

    /**
     * Read a single note's body, extract its metadata, upsert the FTS index, and return
     * an enriched copy with preview/tags/label populated.
     */
    open suspend fun enrichOne(note: Note): Note? = withContext(Dispatchers.IO) {
        val text = fs.readTextOrNull(note.path) ?: return@withContext null
        val fileName = File(note.path).name
        val meta = runCatching {
            extractMeta(note.path, note.parent, fileName, text, note.modifiedMillis)
        }.getOrNull() ?: return@withContext null
        val idx = index
        if (idx != null) {
            runCatching {
                idx.upsert(meta.path, meta.parent, note.title, text, meta.label, meta.tags,
                           meta.modifiedMillis, meta.createdMillis)
            }
        }
        val cleanPreview = stripFrontmatter(meta.preview)
        Note.fromMeta(meta).copy(title = note.title, preview = cleanPreview)
    }

    private fun stripFrontmatter(s: String): String {
        if (!s.startsWith("---")) return s
        val close = s.indexOf("\n---", startIndex = 3)
        if (close < 0) return s
        val after = close + 4
        val rest = if (after < s.length && s[after] == '\n') s.substring(after + 1) else s.substring(after)
        return rest.trimStart()
    }

    /**
     * Write [text] to [path]. Returns true on success.
     */
    open suspend fun write(path: String, parent: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val ok = fs.writeText(path, text)
        if (!ok) return@withContext false
        val idx = index
        if (idx != null) {
            runCatching {
                val fileName = File(path).name
                val meta = extractMeta(path, parent, fileName, text, System.currentTimeMillis())
                idx.upsert(meta.path, meta.parent, meta.title, text, meta.label, meta.tags,
                           meta.modifiedMillis, meta.createdMillis)
            }
        }
        true
    }

    open suspend fun create(parentFolder: String?): Pair<String, String>? = withContext(Dispatchers.IO) {
        val root = fs.getRootFile() ?: return@withContext null
        val target: File = if (parentFolder.isNullOrBlank() || parentFolder == root.absolutePath) root
            else File(parentFolder).takeIf { it.isDirectory } ?: root
        // Default name "Untitled.md", deduped on collision.
        var name = "Untitled"
        var i = 2
        while (File(target, "$name.md").exists()) { name = "Untitled $i"; i++; if (i > 999) break }
        val doc = fs.createMarkdown(target, name) ?: return@withContext null
        doc.absolutePath to target.absolutePath
    }

    open suspend fun moveToTrash(noteUri: String, parentUri: String): Boolean = withContext(Dispatchers.IO) {
        val tag = "BlackNote.moveToTrash"
        val root = fs.getRootFile()
            ?: run { android.util.Log.w(tag, "no root folder"); return@withContext false }
        val trash = findTrashFolder(root)
            ?: fs.ensureSubfolder(root, TRASH_FOLDER_NAME)
            ?: run {
                android.util.Log.w(tag, "could not create $TRASH_FOLDER_NAME in ${root.absolutePath}")
                return@withContext false
            }
        val sourceFile = File(noteUri)
        val result = fs.moveFile(sourceFile, trash)
        if (result == null) {
            // Try resolving parent from file directly
            val freshParent = sourceFile.parentFile
            val result2 = if (freshParent != null && freshParent != File(parentUri)) {
                fs.moveFile(sourceFile, trash)
            } else null
            if (result2 == null) {
                android.util.Log.w(tag, "moveFile returned null for $noteUri")
                return@withContext false
            }
        }
        val srcStill = sourceFile.exists()
        val destFile = File(trash, sourceFile.name)
        val destReadable = destFile.exists() && destFile.canRead()
        if (srcStill || !destReadable) {
            android.util.Log.w(tag, "post-move verify failed: srcStill=$srcStill destReadable=$destReadable")
            return@withContext false
        }
        runCatching { index?.delete(noteUri) }
        true
    }

    open suspend fun restoreFromTrash(noteUri: String): Boolean = withContext(Dispatchers.IO) {
        val root = fs.getRootFile() ?: return@withContext false
        val trash = findTrashFolder(root) ?: return@withContext false
        val sourceFile = File(noteUri)
        fs.moveFile(sourceFile, root) ?: return@withContext false
        true
    }

    open suspend fun deletePermanently(noteUri: String): Boolean = withContext(Dispatchers.IO) {
        val ok = fs.deleteFile(noteUri)
        if (ok) runCatching { index?.delete(noteUri) }
        ok
    }

    open suspend fun moveTo(noteUri: String, parentUri: String, targetFolderUri: String): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(targetFolderUri).takeIf { it.isDirectory } ?: return@withContext false
        val sourceFile = File(noteUri)
        val moved = fs.moveFile(sourceFile, targetDir) != null
        if (!moved) {
            // Try finding the actual parent from the file's current location
            val currentParent = sourceFile.parentFile
            if (currentParent != null && currentParent.absolutePath != parentUri) {
                val retried = fs.moveFile(sourceFile, targetDir) != null
                if (retried) {
                    runCatching { index?.delete(noteUri) }
                    return@withContext true
                }
            }
        }
        if (moved) runCatching { index?.delete(noteUri) }
        moved
    }

    open suspend fun copyTo(noteUri: String, targetFolderUri: String): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(targetFolderUri).takeIf { it.isDirectory } ?: return@withContext false
        fs.copyFile(File(noteUri), targetDir) != null
    }

    /** Permanently delete every note in the trash folder. Returns the number removed. */
    open suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) {
        val root = fs.getRootFile() ?: return@withContext 0
        val trash = findTrashFolder(root) ?: return@withContext 0
        var removed = 0
        for (doc in trash.listFiles() ?: emptyArray()) {
            if (!doc.isFile) continue
            if (fs.deleteFile(doc.absolutePath)) {
                runCatching { index?.delete(doc.absolutePath) }
                removed++
            }
        }
        removed
    }

    /**
     * Rename the note's file to match its title. Returns the (possibly new) path.
     */
    open suspend fun renameToMatchTitle(currentUri: String, parent: String, desiredTitle: String): String = withContext(Dispatchers.IO) {
        val current = File(currentUri)
        if (!current.exists()) return@withContext currentUri
        val currentName = current.name
        val base = sanitizeFileName(desiredTitle)
        val desiredName = "$base.md"
        if (currentName.equals(desiredName, ignoreCase = true)) return@withContext currentUri
        val parentDir = current.parentFile ?: return@withContext currentUri
        var attempt = desiredName
        var i = 2
        while (File(parentDir, attempt).exists()) {
            attempt = "$base ($i).md"; i++
            if (i > 50) break
        }
        val renamed = fs.renameFile(current, attempt) ?: return@withContext currentUri
        val newPath = renamed.absolutePath
        val idx = index
        if (idx != null) {
            runCatching { idx.delete(currentUri) }
            val text = fs.readText(newPath)
            val meta = extractMeta(newPath, parent, attempt, text, System.currentTimeMillis())
            runCatching {
                idx.upsert(meta.path, meta.parent, meta.title, text, meta.label, meta.tags,
                           meta.modifiedMillis, meta.createdMillis)
            }
        }
        newPath
    }

    /** Query the hashtag index for suggestions relevant to [noteBody] and [noteTitle]. */
    open suspend fun suggestHashtags(
        noteBody: String,
        noteTitle: String,
        excludeTags: Set<String>,
        limit: Int = 20,
    ): List<String> = withContext(Dispatchers.IO) {
        if (index == null) return@withContext emptyList()
        runCatching { index.suggestHashtags(noteBody, noteTitle, excludeTags, limit) }
            .getOrDefault(emptyList())
    }

    /** Create a folder under the root, returning its path string for optimistic UI updates. */
    suspend fun createFolderReturningUri(name: String): String? = withContext(Dispatchers.IO) {
        val root = fs.getRootFile() ?: return@withContext null
        val safe = sanitizeFileName(name, fallback = "Folder")
        if (File(root, safe).exists()) return@withContext null
        fs.ensureSubfolder(root, safe)?.absolutePath
    }
}

/**
 * Make a string safe to use as a file name while keeping every character that
 * filesystems actually allow — unicode letters (CJK, accents, emoji) included.
 */
internal fun sanitizeFileName(name: String, fallback: String = "Untitled"): String {
    val cleaned = name
        .replace(Regex("[/\\\\:*?\"<>|\\p{Cntrl}]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimStart('.')
        .trimEnd('.', ' ')
        .take(120)
    return cleaned.ifBlank { fallback }
}

/** Strip ".md" (case-insensitive) and clean up — used by the list to derive the title. */
internal fun titleFromFileName(fileName: String): String {
    val base = fileName.removeSuffix(".md").removeSuffix(".MD")
        .removeSuffix(".Md").removeSuffix(".mD")
        .trim()
    return base.ifBlank { "Untitled" }
}

/** modifiedMillis descending + path tiebreaker. */
internal val stableDisplayDesc: Comparator<Note> =
    compareByDescending<Note> { it.modifiedMillis }.then(compareBy { it.path })

/**
 * Reconstruct the folder tree from a flat note list using each note's `parent` path, so the
 * startup DB cache can show folders at the top instantly — before the file walk runs. The
 * walk still adds any empty folders (those containing no notes) a moment later.
 */
internal fun foldersFromNotes(notes: List<Note>, rootPath: String): List<FolderInfo> {
    val root = rootPath.trimEnd('/')
    val paths = LinkedHashSet<String>()
    for (n in notes) {
        var p = n.parent.trimEnd('/')
        // Add this note's folder and every ancestor up to (but not including) the root.
        while (p.startsWith("$root/")) {
            paths.add(p)
            val slash = p.lastIndexOf('/')
            if (slash <= 0) break
            p = p.substring(0, slash)
        }
    }
    return paths.mapNotNull { path ->
        val slash = path.lastIndexOf('/')
        val name = path.substring(slash + 1)
        if (name == TRASH_FOLDER_NAME || name == TRASH_FOLDER_LEGACY || name.startsWith(".")) {
            return@mapNotNull null
        }
        FolderInfo(
            path = path,
            parent = path.substring(0, slash),
            name = name,
            depth = path.removePrefix("$root/").count { it == '/' },
        )
    }.sortedBy { it.path }
}

data class FolderInfo(
    val path: String,
    val parent: String,
    val name: String,
    val depth: Int,
)

data class TreeSnapshot(
    val notes: List<Note>,
    val folders: List<FolderInfo>,
) {
    companion object { val EMPTY = TreeSnapshot(emptyList(), emptyList()) }
}
