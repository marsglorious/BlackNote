package com.marsglorious.blacknote.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore by preferencesDataStore(name = "blacknote_prefs")

open class SafStore(private val ctx: Context) {
    private val keyTree = stringPreferencesKey("notes_tree_uri")

    open suspend fun getTreeUri(): Uri? = ctx.dataStore.data
        .map { it[keyTree] }.first()?.let(Uri::parse)

    /** Small string preference store for UI state that must survive restarts. */
    open suspend fun getPref(name: String): String? =
        runCatching { ctx.dataStore.data.map { it[stringPreferencesKey(name)] }.first() }.getOrNull()

    open suspend fun setPref(name: String, value: String) {
        runCatching { ctx.dataStore.edit { it[stringPreferencesKey(name)] = value } }
    }

    open suspend fun saveTreeUri(uri: Uri) {
        ctx.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        ctx.dataStore.edit { it[keyTree] = uri.toString() }
    }

    open fun root(uri: Uri): DocumentFile? = treeDoc(uri)

    /**
     * Construct a tree-document handle for [uri]. Default uses [DocumentFile.fromTreeUri]
     * (content:// SAF). Tests/alternative providers can override to return a file-backed
     * [DocumentFile] so the rest of the app keeps using the same DocumentFile API.
     */
    open fun treeDoc(uri: Uri): DocumentFile? = DocumentFile.fromTreeUri(ctx, uri)

    /** Counterpart to [treeDoc] for single-document URIs. */
    open fun singleDoc(uri: Uri): DocumentFile? = DocumentFile.fromSingleUri(ctx, uri)

    open fun readText(uri: Uri): String = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
    }.getOrDefault("")

    /** Last exception thrown by readTextOrNull. Lets callers diagnose why a read returned null. */
    @Volatile var lastReadError: Throwable? = null
        private set

    open fun readTextOrNull(uri: Uri): String? {
        // Tier 1: ContentResolver.openInputStream. Works on most providers.
        try {
            val s = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            if (s != null) { lastReadError = null; return s }
            // openInputStream returned null with no exception — Samsung's
            // ExternalStorageProvider and several cloud providers do this
            // intermittently for tree-document URIs that are nevertheless valid.
            // Fall through to tier 2 instead of treating the note as stale.
            lastReadError = IllegalStateException("openInputStream returned null for $uri")
        } catch (t: Throwable) {
            lastReadError = t
            android.util.Log.w("BlackNote.SafStore",
                "readTextOrNull($uri) tier1 failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        // Tier 2: ParcelFileDescriptor via DocumentsContract.openDocument. Different
        // code path inside the provider; often succeeds when tier 1 returns null.
        try {
            val s = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes().decodeToString() }
            }
            if (s != null) { lastReadError = null; return s }
            lastReadError = IllegalStateException("openFileDescriptor returned null for $uri")
        } catch (t: Throwable) {
            lastReadError = t
            android.util.Log.w("BlackNote.SafStore",
                "readTextOrNull($uri) tier2 failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        return null
    }

    /**
     * Write text to a SAF URI, returning true on success. We try mode "wt" first (truncate),
     * then fall back to "w" — some providers (Samsung Documents on certain SDK levels) refuse
     * "wt" with UnsupportedOperationException, which previously meant edits silently no-op'd.
     */
    open fun writeText(uri: Uri, text: String): Boolean {
        for (mode in arrayOf("wt", "w")) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri, mode)?.use { it.write(text.toByteArray()) }
                    ?: throw IOException("openOutputStream returned null")
                true
            }
            if (ok.isSuccess) return true
            android.util.Log.w("BlackNote.SafStore",
                "writeText($uri) mode=$mode failed: ${ok.exceptionOrNull()?.javaClass?.simpleName}: ${ok.exceptionOrNull()?.message}")
        }
        return false
    }

    open fun createMarkdown(parent: DocumentFile, fileName: String): DocumentFile? {
        val safe = sanitizeFileName(fileName)
        return parent.createFile("text/markdown", "$safe.md")
    }

    open fun ensureSubfolder(parent: DocumentFile, name: String): DocumentFile? {
        parent.findFile(name)?.let { if (it.isDirectory) return it }
        return parent.createDirectory(name)
    }

    open fun moveDocument(sourceUri: Uri, sourceParentUri: Uri, targetParentUri: Uri): Uri? {
        return runCatching {
            DocumentsContract.moveDocument(ctx.contentResolver, sourceUri, sourceParentUri, targetParentUri)
        }.getOrNull()
    }

    /**
     * Move with a copy+delete fallback. Many SAF providers (cloud, some SD cards) do not
     * support DocumentsContract.moveDocument and return null / throw — without the fallback,
     * delete-to-trash and move-to-folder silently no-op.
     */
    /** Minimal target abstraction so the manual fallback can be unit-tested without DocumentFile. */
    fun interface TargetFolder {
        /** Create a child with the given display name; return its URI (or null on failure). */
        fun createChild(displayName: String): Uri?
    }

    open fun moveDocumentCompat(sourceUri: Uri, sourceParentUri: Uri, targetParent: DocumentFile): Uri? =
        moveDocumentCompat(sourceUri, sourceParentUri, targetParent.uri, TargetFolder { name ->
            targetParent.createFile("text/markdown", name)?.uri
        })

    /**
     * Two-tier move: provider moveDocument → manual read/create/write/delete-and-verify.
     *
     * We intentionally skip DocumentsContract.copyDocument as a middle tier. On Samsung's
     * ExternalStorageProvider it returns a non-null URI even when no file appears at the
     * destination, which previously caused delete-to-trash to silently lose the note: the
     * source got deleted, the optimistic UI removal stuck, and the trash listing was empty.
     *
     * The manual fallback uses the same two-tier read as readTextOrNull (openInputStream
     * then openFileDescriptor) and only deletes the source after confirming the target file
     * is both written and readable back.
     */
    open fun moveDocumentCompat(
        sourceUri: Uri,
        sourceParentUri: Uri,
        targetParentUri: Uri,
        target: TargetFolder,
    ): Uri? {
        // Tier 1: provider moveDocument. Samsung's ExternalStorageProvider sometimes
        // returns a non-null URI without actually performing the move — accept tier 1
        // only if the destination is readable AND the source no longer exists.
        moveDocument(sourceUri, sourceParentUri, targetParentUri)?.let { newUri ->
            val destOk = readTextOrNull(newUri) != null
            val srcGone = DocumentFile.fromSingleUri(ctx, sourceUri)?.exists() != true
            if (destOk && srcGone) return newUri
            // Tier 1 lied. Fall through to manual fallback. Don't delete newUri yet —
            // if destOk it might be a real new file we want; we'll detect duplication
            // below and let the manual path overwrite cleanly.
            if (destOk && !srcGone) {
                // Both source and dest exist — provider did a copy, not a move.
                // Delete source ourselves to complete the move.
                if (deleteDocument(sourceUri)) return newUri
                // Couldn't delete source — leave the new copy and report failure so the
                // caller's recovery path keeps the source-side state consistent.
                deleteDocument(newUri)
            }
        }
        // Tier 2: manual read / create / write / verify / delete-source.
        val src = DocumentFile.fromSingleUri(ctx, sourceUri)
        val name = src?.name ?: sourceUri.lastPathSegment ?: return null
        val text = readTextOrNull(sourceUri) ?: return null
        val newUri = target.createChild(name) ?: return null
        if (!writeText(newUri, text)) {
            deleteDocument(newUri)
            return null
        }
        if (readTextOrNull(newUri) == null) {
            deleteDocument(newUri)
            return null
        }
        if (!deleteDocument(sourceUri)) {
            // Couldn't remove the source — back out so we don't end up with two copies.
            deleteDocument(newUri)
            return null
        }
        return newUri
    }

    open fun copyDocument(sourceUri: Uri, targetParentUri: Uri): Uri? {
        return runCatching {
            DocumentsContract.copyDocument(ctx.contentResolver, sourceUri, targetParentUri)
        }.getOrNull()
    }

    /**
     * Copy with verification and a manual fallback, mirroring [moveDocumentCompat].
     * DocumentsContract.copyDocument on Samsung's ExternalStorageProvider can return a
     * non-null URI with no file behind it — the same lie that used to break
     * delete-to-trash. Verify the destination is readable; if not, do the copy by hand.
     */
    open fun copyDocumentCompat(sourceUri: Uri, targetParent: DocumentFile): Uri? {
        copyDocument(sourceUri, targetParent.uri)?.let { newUri ->
            if (readTextOrNull(newUri) != null) return newUri
            // Phantom destination — clean it up (harmless if nothing is there) and fall through.
            deleteDocument(newUri)
        }
        val name = singleDoc(sourceUri)?.name ?: sourceUri.lastPathSegment ?: return null
        val text = readTextOrNull(sourceUri) ?: return null
        val newDoc = targetParent.createFile("text/markdown", name) ?: return null
        if (!writeText(newDoc.uri, text) || readTextOrNull(newDoc.uri) == null) {
            deleteDocument(newDoc.uri)
            return null
        }
        return newDoc.uri
    }

    open fun deleteDocument(uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }.getOrDefault(false)

    open fun renameDocument(uri: Uri, displayName: String): Uri? =
        runCatching { DocumentsContract.renameDocument(ctx.contentResolver, uri, displayName) }.getOrNull()

    /**
     * Rename with a copy+delete fallback. Several SAF providers (Samsung's
     * ExternalStorageProvider notably) silently return null from renameDocument, which is
     * how the title-on-the-card and file-name-on-disk drift apart for users. The fallback
     * reads the source, creates a new file with the requested display name in the same
     * parent, writes the bytes, and deletes the source.
     */
    open fun renameDocumentCompat(uri: Uri, parent: DocumentFile, displayName: String): Uri? {
        renameDocument(uri, displayName)?.let { return it }
        // Manual fallback.
        val bytes = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        val newDoc = parent.createFile("text/markdown", displayName) ?: return null
        if (!writeText(newDoc.uri, bytes.decodeToString())) {
            deleteDocument(newDoc.uri)
            return null
        }
        deleteDocument(uri)
        return newDoc.uri
    }
}
