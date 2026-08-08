package com.marsglorious.blacknote

import android.app.Application
import com.marsglorious.blacknote.data.NoteRepository
import com.marsglorious.blacknote.data.SearchIndex
import java.io.File

open class App : Application() {

    /** Null if the SQLite index failed to open — we then fall back to in-memory search. */
    var searchIndex: SearchIndex? = null
        private set

    var indexError: Throwable? = null
        private set

    private var _repository: NoteRepository? = null
    val repository: NoteRepository
        get() = _repository ?: NoteRepository(this, searchIndex).also { _repository = it }

    /**
     * Test-only injection point. Lets instrumented tests swap in a NoteRepository whose
     * FileStore is backed by a real on-device directory (no SAF picker, no caching) so
     * end-to-end bug repros can drive the production AppViewModel against a known fs.
     */
    @androidx.annotation.VisibleForTesting
    fun setRepositoryForTest(repo: NoteRepository) { _repository = repo }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        try {
            val dbPath = File(filesDir, "notes.db").absolutePath
            searchIndex = SearchIndex(dbPath)
        } catch (t: Throwable) {
            indexError = t
            CrashReporter.report(this, "SearchIndex.init", t)
            searchIndex = null
        }
    }
}
