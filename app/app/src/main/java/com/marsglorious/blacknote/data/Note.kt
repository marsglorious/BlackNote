package com.marsglorious.blacknote.data

import com.marsglorious.blacknote.ffi.NoteMeta

data class Note(
    val path: String,
    val parent: String,
    val title: String,
    val preview: String,
    val modifiedMillis: Long,
    val createdMillis: Long,
    val tags: List<String>,
    val label: String?,
) {
    /** Date to show on cards — front-matter `created` wins, else file mtime. */
    val displayMillis: Long get() = if (createdMillis > 0) createdMillis else modifiedMillis

    companion object {
        fun fromMeta(m: NoteMeta) = Note(
            path = m.path,
            parent = m.parent,
            title = m.title,
            preview = m.preview,
            modifiedMillis = m.modifiedMillis,
            createdMillis = m.createdMillis,
            tags = m.tags,
            label = m.label,
        )
    }

    fun toMeta() = NoteMeta(path, parent, title, preview, modifiedMillis, createdMillis, tags, label)
}

sealed class TreeEntry {
    abstract val path: String
    abstract val parent: String
    abstract val name: String
    abstract val depth: Int

    data class FolderEntry(
        override val path: String,
        override val parent: String,
        override val name: String,
        override val depth: Int,
        val noteCount: Int,
    ) : TreeEntry()

    data class NoteEntry(
        override val path: String,
        override val parent: String,
        override val name: String,
        override val depth: Int,
        val note: Note,
    ) : TreeEntry()
}

/**
 * Folder name we move deleted notes into. Plain "Trash" (no dot) — Samsung's
 * ExternalStorageProvider and several others silently fail to create dot-prefixed
 * directories or hide them from listFiles() after creation. We still recognise the
 * legacy ".Trash" for users who have data there already (see [TRASH_FOLDER_LEGACY]).
 */
const val TRASH_FOLDER_NAME = "Trash"
const val TRASH_FOLDER_LEGACY = ".Trash"
