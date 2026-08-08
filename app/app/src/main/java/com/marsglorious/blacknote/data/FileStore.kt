package com.marsglorious.blacknote.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.dataStore by preferencesDataStore(name = "blacknote_prefs")

open class FileStore(private val ctx: Context) {
    private val keyFolder = stringPreferencesKey("notes_folder_path")
    private val keyLegacyUri = stringPreferencesKey("notes_tree_uri")

    open suspend fun getFolderPath(): String? {
        ctx.dataStore.data.map { it[keyFolder] }.first()?.let { return it }
        // Migration from old SAF URI
        val legacy = ctx.dataStore.data.map { it[keyLegacyUri] }.first() ?: return null
        val path = safUriToPath(legacy) ?: return null
        saveFolderPath(path)
        return path
    }

    open suspend fun saveFolderPath(path: String) {
        ctx.dataStore.edit { prefs ->
            prefs[keyFolder] = path
            prefs.remove(keyLegacyUri)
        }
    }

    open suspend fun getRootFile(): File? =
        getFolderPath()?.let { File(it).takeIf { f -> f.isDirectory } }

    open suspend fun getPref(name: String): String? =
        runCatching { ctx.dataStore.data.map { it[stringPreferencesKey(name)] }.first() }.getOrNull()

    open suspend fun setPref(name: String, value: String) {
        runCatching { ctx.dataStore.edit { it[stringPreferencesKey(name)] = value } }
    }

    open fun readTextOrNull(path: String): String? =
        runCatching { File(path).readText() }.getOrNull()

    open fun readText(path: String): String =
        runCatching { File(path).readText() }.getOrDefault("")

    open fun writeText(path: String, text: String): Boolean =
        runCatching { File(path).writeText(text); true }.getOrDefault(false)

    open fun createMarkdown(parent: File, fileName: String): File? {
        val safe = sanitizeFileName(fileName)
        return runCatching {
            File(parent, "$safe.md").also { it.createNewFile() }
        }.getOrNull()
    }

    open fun ensureSubfolder(parent: File, name: String): File? {
        val dir = File(parent, name)
        return if (dir.isDirectory || dir.mkdirs()) dir else null
    }

    open fun moveFile(source: File, targetDir: File): File? {
        val dest = File(targetDir, source.name)
        return if (source.renameTo(dest)) dest
        else runCatching {
            dest.writeText(source.readText())
            if (dest.exists()) { source.delete(); dest } else null
        }.getOrNull()
    }

    open fun copyFile(source: File, targetDir: File): File? {
        val dest = File(targetDir, source.name)
        return runCatching { source.copyTo(dest, overwrite = false) }.getOrNull()
    }

    open fun deleteFile(path: String): Boolean =
        runCatching { File(path).delete() }.getOrDefault(false)

    open fun renameFile(file: File, newName: String): File? {
        val dest = File(file.parent ?: return null, newName)
        return if (file.renameTo(dest)) dest else null
    }

    private fun safUriToPath(uriString: String): String? =
        safUriToFilePath(Uri.parse(uriString))
}

/**
 * Convert a SAF tree URI to an absolute file path on internal storage.
 * Returns null for SD card and cloud storage URIs — those can't be accessed
 * via java.io.File regardless of permissions.
 */
fun safUriToFilePath(uri: Uri): String? = runCatching {
    val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
    val colon = docId.indexOf(':')
    if (colon < 0) return null
    val volume = docId.substring(0, colon)
    val rel = docId.substring(colon + 1)
    if (volume == "primary") "${Environment.getExternalStorageDirectory()}/$rel"
    else null
}.getOrNull()
