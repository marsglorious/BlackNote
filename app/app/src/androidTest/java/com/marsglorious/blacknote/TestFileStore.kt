package com.marsglorious.blacknote

import android.content.Context
import com.marsglorious.blacknote.data.FileStore
import java.io.File

/**
 * File-backed [FileStore] for instrumented tests. Points the notes root at a real on-device
 * [rootDir] the test creates and tears down, so the production NoteRepository / ViewModel
 * exercise real filesystem I/O — write, rename, move, delete, refresh — without the folder
 * picker or storage permissions.
 *
 * Everything else (NoteRepository, FTS index, ViewModel, Compose UI) is the production class
 * unchanged. Construct one per test with a [rootDir] that the test creates and tears down.
 */
class TestFileStore(ctx: Context, private val rootDir: File) : FileStore(ctx) {
    init { if (!rootDir.exists()) rootDir.mkdirs() }

    // Prefs live in memory so tests don't touch (or leak into) the app's real DataStore.
    private val prefs = mutableMapOf<String, String>()

    override suspend fun getFolderPath(): String = rootDir.absolutePath
    override suspend fun saveFolderPath(path: String) { /* root is fixed to rootDir in tests */ }
    override suspend fun getPref(name: String): String? = prefs[name]
    override suspend fun setPref(name: String, value: String) { prefs[name] = value }
}
