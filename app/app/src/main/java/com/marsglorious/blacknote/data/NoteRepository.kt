package com.marsglorious.blacknote.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.marsglorious.blacknote.ffi.SearchIndex
import com.marsglorious.blacknote.ffi.extractMeta
import com.marsglorious.blacknote.ffi.searchNotes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tree-aware note store. The on-disk source of truth is the SAF folder the user picked;
 * we mirror everything we know into a SQLite FTS5 index that doubles as a startup cache —
 * so the list can render instantly from the index while a background SAF scan refreshes
 * the tree structure.
 *
 * Folders are derived from the SAF walk only — never persisted into the index — so the
 * index continues to deal only in flat notes (each note carries its `parent` URI string,
 * which is enough to rebuild the tree on the Kotlin side).
 */
open class NoteRepository(
    private val ctx: Context,
    private val index: SearchIndex?,
    val saf: SafStore = SafStore(ctx),
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

    /** Walk the SAF tree, refresh the FTS index, return (notes, folders). */
    open suspend fun refreshTree(): TreeSnapshot = withContext(Dispatchers.IO) {
        val tree = saf.getTreeUri() ?: return@withContext TreeSnapshot.EMPTY
        val root = saf.root(tree) ?: return@withContext TreeSnapshot.EMPTY
        val notes = mutableListOf<Note>()
        val folders = mutableListOf<FolderInfo>()
        val alivePaths = mutableListOf<String>()
        walk(root, rootUri = root.uri.toString(), notes = notes, folders = folders, alivePaths = alivePaths)
        if (index != null) runCatching { index.retain(alivePaths) }
        TreeSnapshot(notes = notes.sortedWith(stableDisplayDesc), folders = folders)
    }

    /** Locate the trash folder by either current or legacy name. Does not create. */
    private fun findTrashFolder(root: DocumentFile): DocumentFile? =
        (root.findFile(TRASH_FOLDER_NAME) ?: root.findFile(TRASH_FOLDER_LEGACY))
            ?.takeIf { it.isDirectory }

    open suspend fun refreshTrash(): List<Note> = withContext(Dispatchers.IO) {
        val tree = saf.getTreeUri() ?: return@withContext emptyList()
        val root = saf.root(tree) ?: return@withContext emptyList()
        val trash = findTrashFolder(root) ?: return@withContext emptyList()
        val notes = mutableListOf<Note>()
        for (doc in trash.listFiles()) {
            if (!doc.isFile || doc.name?.endsWith(".md", true) != true) continue
            val text = saf.readText(doc.uri)
            val fileName = doc.name ?: ""
            val meta = extractMeta(doc.uri.toString(), trash.uri.toString(), fileName, text, doc.lastModified())
            notes += Note.fromMeta(meta).copy(title = titleFromFileName(fileName))
        }
        notes.sortedWith(stableDisplayDesc)
    }

    private fun walk(
        dir: DocumentFile,
        rootUri: String,
        notes: MutableList<Note>,
        folders: MutableList<FolderInfo>,
        alivePaths: MutableList<String>,
        depth: Int = 0,
    ) {
        for (doc in dir.listFiles()) {
            // Skip the trash folder (current or legacy name) and anything hidden — any
            // dot-prefixed folder ".foo" follows the conventional Unix hidden marker
            // and shouldn't appear in the note list.
            if (doc.isDirectory) {
                val n = doc.name
                if (n == TRASH_FOLDER_NAME || n == TRASH_FOLDER_LEGACY) continue
                if (n != null && n.startsWith(".")) continue
            }
            if (doc.isDirectory) {
                folders += FolderInfo(
                    path = doc.uri.toString(),
                    parent = dir.uri.toString(),
                    name = doc.name ?: "?",
                    depth = depth,
                )
                walk(doc, rootUri, notes, folders, alivePaths, depth + 1)
            } else if (doc.isFile && (doc.name?.endsWith(".md", true) == true)) {
                // Fast path: do NOT read the file body here. With 4000 notes the previous
                // per-file SAF read meant the walk took 60+ seconds and rarely finished
                // before being cancelled by another refresh, which is why only the 500
                // FTS-cached notes ever showed up. Build a minimal Note from filename +
                // mtime; preview/tags/label are enriched in the background by
                // [enrichPreviews] once the walk has populated the full tree.
                val fileName = doc.name ?: ""
                val titleFromName = titleFromFileName(fileName)
                val path = doc.uri.toString()
                val parent = dir.uri.toString()
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
        saf.readTextOrNull(Uri.parse(path))
    }

    /**
     * Read a single note's body, extract its metadata, upsert the FTS index, and return
     * an enriched copy with preview/tags/label populated. Used by the ViewModel's
     * background enrichment loop after the fast walk has populated the bare tree.
     * Returns null on read failure so the caller can skip and try later.
     */
    open suspend fun enrichOne(note: Note): Note? = withContext(Dispatchers.IO) {
        val text = saf.readTextOrNull(Uri.parse(note.path)) ?: return@withContext null
        val meta = runCatching {
            extractMeta(note.path, note.parent, fileNameFromUriOrTitle(note), text, note.modifiedMillis)
        }.getOrNull() ?: return@withContext null
        val idx = index
        if (idx != null) {
            runCatching {
                idx.upsert(meta.path, meta.parent, note.title, text, meta.label, meta.tags,
                           meta.modifiedMillis, meta.createdMillis)
            }
        }
        // Preview shown on the card must not include the YAML frontmatter — that's
        // metadata, not prose. extractMeta may or may not have stripped it depending
        // on the Rust core's parser; do it again on the Kotlin side to be safe.
        val cleanPreview = stripFrontmatter(meta.preview)
        Note.fromMeta(meta).copy(title = note.title, preview = cleanPreview)
    }

    private fun stripFrontmatter(s: String): String {
        if (!s.startsWith("---")) return s
        // Look for the closing fence on its own line.
        val close = s.indexOf("\n---", startIndex = 3)
        if (close < 0) return s
        val after = close + 4
        val rest = if (after < s.length && s[after] == '\n') s.substring(after + 1) else s.substring(after)
        return rest.trimStart()
    }

    private fun fileNameFromUriOrTitle(note: Note): String {
        // Recover the on-disk filename. Try a fresh SAF lookup; fall back to title.md
        // if the provider can't surface it (rare, but possible on cloud providers).
        return saf.singleDoc(Uri.parse(note.path))?.name ?: "${note.title}.md"
    }

    /**
     * Write [text] to [path]. Returns true on success, false if the underlying SAF write
     * failed — callers (e.g. autosave, closeEditor) check this so silent edit-loss can be
     * detected instead of being eaten by the index update below.
     */
    open suspend fun write(path: String, parent: String, text: String): Boolean = withContext(Dispatchers.IO) {
        val ok = saf.writeText(Uri.parse(path), text)
        if (!ok) return@withContext false
        val idx = index
        if (idx != null) {
            runCatching {
                val fileName = saf.singleDoc(Uri.parse(path))?.name ?: ""
                val meta = extractMeta(path, parent, fileName, text, System.currentTimeMillis())
                idx.upsert(meta.path, meta.parent, meta.title, text, meta.label, meta.tags,
                           meta.modifiedMillis, meta.createdMillis)
            }
        }
        true
    }

    open suspend fun create(parentFolder: String?): Pair<String, String>? = withContext(Dispatchers.IO) {
        val tree = saf.getTreeUri() ?: return@withContext null
        val root = saf.root(tree) ?: return@withContext null
        val target: DocumentFile = if (parentFolder.isNullOrBlank() || parentFolder == root.uri.toString()) root
            else saf.treeDoc(Uri.parse(parentFolder)) ?: root
        // Default name "Untitled.md", deduped on collision. Closing the editor renames to the title.
        var name = "Untitled"
        var i = 2
        while (target.findFile("$name.md") != null) { name = "Untitled $i"; i++; if (i > 999) break }
        val doc = saf.createMarkdown(target, name) ?: return@withContext null
        doc.uri.toString() to target.uri.toString()
    }

    private fun findCurrentParentUri(root: DocumentFile, targetUri: Uri): Uri? {
        fun search(dir: DocumentFile): Uri? {
            for (doc in dir.listFiles()) {
                if (doc.uri == targetUri) {
                    return dir.uri
                }
                if (doc.isDirectory) {
                    val found = search(doc)
                    if (found != null) return found
                }
            }
            return null
        }
        return search(root)
    }

    open suspend fun moveToTrash(noteUri: String, parentUri: String): Boolean = withContext(Dispatchers.IO) {
        val tag = "BlackNote.moveToTrash"
        val tree = saf.getTreeUri()
            ?: run { android.util.Log.w(tag, "no tree uri"); return@withContext false }
        val root = saf.root(tree)
            ?: run { android.util.Log.w(tag, "no root for $tree"); return@withContext false }
        // Prefer an existing trash folder (current or legacy name). Only create a new one
        // if neither exists — and create with the dot-free name so providers that hide
        // dot-prefixed entries can see it back.
        val trash = findTrashFolder(root)
            ?: saf.ensureSubfolder(root, TRASH_FOLDER_NAME)
            ?: run {
                android.util.Log.w(tag, "could not create $TRASH_FOLDER_NAME in $root.uri")
                return@withContext false
            }
        val sourceParent = Uri.parse(parentUri)
        var newUri = saf.moveDocumentCompat(Uri.parse(noteUri), sourceParent, trash)
        if (newUri == null) {
            // Stored parent may be stale (cached / externally moved). Re-locate and retry.
            val fresh = findCurrentParentUri(root, Uri.parse(noteUri))
            if (fresh != null) {
                newUri = saf.moveDocumentCompat(Uri.parse(noteUri), fresh, trash)
            }
        }
        if (newUri == null) {
            android.util.Log.w(tag, "moveDocumentCompat returned null for $noteUri " +
                "(parent=$parentUri, trash=${trash.uri}, lastReadError=${saf.lastReadError})")
            return@withContext false
        }
        // Verify the move actually completed end-to-end: source must be gone, and the
        // new URI must be readable. If either check fails, the "successful" move was a
        // lie — return false so the UI restoration path kicks in.
        val srcStill = DocumentFile.fromSingleUri(ctx, Uri.parse(noteUri))?.exists() == true
        val destReadable = saf.readTextOrNull(newUri) != null
        if (srcStill || !destReadable) {
            android.util.Log.w(tag, "post-move verify failed: srcStill=$srcStill destReadable=$destReadable newUri=$newUri")
            return@withContext false
        }
        runCatching { index?.delete(noteUri) }
        true
    }

    open suspend fun restoreFromTrash(noteUri: String): Boolean = withContext(Dispatchers.IO) {
        val tree = saf.getTreeUri() ?: return@withContext false
        val root = saf.root(tree) ?: return@withContext false
        val trash = findTrashFolder(root) ?: return@withContext false
        saf.moveDocumentCompat(Uri.parse(noteUri), trash.uri, root) ?: return@withContext false
        true
    }

    open suspend fun deletePermanently(noteUri: String): Boolean = withContext(Dispatchers.IO) {
        val ok = saf.deleteDocument(Uri.parse(noteUri))
        if (ok) runCatching { index?.delete(noteUri) }
        ok
    }

    open suspend fun moveTo(noteUri: String, parentUri: String, targetFolderUri: String): Boolean = withContext(Dispatchers.IO) {
        val tree = saf.getTreeUri() ?: return@withContext false
        val root = saf.root(tree) ?: return@withContext false
        val sourceParent = Uri.parse(parentUri)
        val target = saf.treeDoc(Uri.parse(targetFolderUri))
            ?: return@withContext false
        var moved = saf.moveDocumentCompat(Uri.parse(noteUri), sourceParent, target) != null
        if (!moved) {
            val fresh = findCurrentParentUri(root, Uri.parse(noteUri))
            if (fresh != null) {
                moved = saf.moveDocumentCompat(Uri.parse(noteUri), fresh, target) != null
            }
        }
        if (moved) runCatching { index?.delete(noteUri) }
        moved
    }

    open suspend fun copyTo(noteUri: String, targetFolderUri: String): Boolean = withContext(Dispatchers.IO) {
        val target = saf.treeDoc(Uri.parse(targetFolderUri)) ?: return@withContext false
        saf.copyDocumentCompat(Uri.parse(noteUri), target) != null
    }

    /** Permanently delete every note in the trash folder. Returns the number removed. */
    open suspend fun emptyTrash(): Int = withContext(Dispatchers.IO) {
        val tree = saf.getTreeUri() ?: return@withContext 0
        val root = saf.root(tree) ?: return@withContext 0
        val trash = findTrashFolder(root) ?: return@withContext 0
        var removed = 0
        for (doc in trash.listFiles()) {
            if (!doc.isFile) continue
            if (saf.deleteDocument(doc.uri)) {
                runCatching { index?.delete(doc.uri.toString()) }
                removed++
            }
        }
        removed
    }

    /**
     * Rename the note's file to match its title. Returns the (possibly new) URI.
     * If the title is blank, the filename becomes "Untitled.md" (deduped if needed).
     */
    open suspend fun renameToMatchTitle(currentUri: String, parent: String, desiredTitle: String): String = withContext(Dispatchers.IO) {
        val current = saf.singleDoc(Uri.parse(currentUri))
            ?: return@withContext currentUri
        val currentName = current.name ?: return@withContext currentUri
        val base = sanitizeFileName(desiredTitle)
        val desiredName = "$base.md"
        if (currentName.equals(desiredName, ignoreCase = true)) return@withContext currentUri
        // Try the desired name, then append a counter on collision.
        val parentDoc = saf.treeDoc(Uri.parse(parent))
        var attempt = desiredName
        var i = 2
        while (parentDoc?.findFile(attempt) != null) {
            attempt = "$base ($i).md"; i++
            if (i > 50) break
        }
        val newUri = (parentDoc?.let { saf.renameDocumentCompat(Uri.parse(currentUri), it, attempt) }
            ?: saf.renameDocument(Uri.parse(currentUri), attempt))?.toString()
            ?: return@withContext currentUri
        val idx = index
        if (idx != null) {
            runCatching { idx.delete(currentUri) }
            val text = saf.readText(Uri.parse(newUri))
            val meta = extractMeta(newUri, parent, attempt, text, System.currentTimeMillis())
            runCatching {
                idx.upsert(meta.path, meta.parent, meta.title, text, meta.label, meta.tags,
                           meta.modifiedMillis, meta.createdMillis)
            }
        }
        newUri
    }

    suspend fun createFolder(name: String): Boolean = createFolderReturningUri(name) != null

    /** Same as [createFolder] but returns the new folder's URI string for optimistic UI updates. */
    suspend fun createFolderReturningUri(name: String): String? = withContext(Dispatchers.IO) {
        val tree = saf.getTreeUri() ?: return@withContext null
        val root = saf.root(tree) ?: return@withContext null
        val safe = sanitizeFileName(name, fallback = "Folder")
        if (root.findFile(safe) != null) return@withContext null
        root.createDirectory(safe)?.uri?.toString()
    }
}

/**
 * Make a string safe to use as a SAF display name while keeping every character that
 * filesystems actually allow — unicode letters (CJK, accents, emoji) included. The old
 * sanitizer whitelisted `[A-Za-z0-9 _-]`, which turned any non-English title into
 * "Untitled". Only characters that are illegal on common filesystems are removed,
 * plus leading dots (SAF providers hide dot-files) and trailing dots/spaces (FAT).
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

/** displayMillis descending + path tiebreaker — makes tree order deterministic across SAF walks. */
internal val stableDisplayDesc: Comparator<Note> =
    compareByDescending<Note> { it.displayMillis }.then(compareBy { it.path })

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
