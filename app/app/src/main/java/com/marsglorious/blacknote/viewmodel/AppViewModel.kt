package com.marsglorious.blacknote.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.marsglorious.blacknote.App
import com.marsglorious.blacknote.CrashReporter
import com.marsglorious.blacknote.data.FolderInfo
import com.marsglorious.blacknote.data.MarkdownFormat
import com.marsglorious.blacknote.data.Note
import com.marsglorious.blacknote.data.NoteRepository
import com.marsglorious.blacknote.data.TreeSnapshot
import com.marsglorious.blacknote.data.stableDisplayDesc
import com.marsglorious.blacknote.ffi.FormatKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Screen { LIST, EDITOR, TRASH, SETTINGS }
enum class ListViewMode { LIST, COLLAGE }
enum class EditorMode { EDIT, RENDER }

/** How the note list is ordered. Pinned notes always sort first regardless of mode. */
enum class SortMode(val label: String) {
    DATE_DESC("Newest first"),
    DATE_ASC("Oldest first"),
    MODIFIED_DESC("Recently edited"),
    TITLE_ASC("Title A–Z"),
}

/** Pinned-first, then the selected sort. Shared by the list screen's row builder. */
fun noteComparator(sortMode: SortMode, pinned: Set<String>): Comparator<Note> {
    // path is unique and stable across restarts — use it as a final tiebreaker so that
    // notes with equal primary sort keys (same-second timestamps, duplicate titles) always
    // appear in the same order regardless of the SAF walk's non-deterministic enumeration.
    val pathTie = compareBy<Note> { it.path }
    val base: Comparator<Note> = when (sortMode) {
        SortMode.DATE_DESC     -> compareByDescending<Note> { it.displayMillis }.then(pathTie)
        SortMode.DATE_ASC      -> compareBy<Note>           { it.displayMillis }.then(pathTie)
        SortMode.MODIFIED_DESC -> compareByDescending<Note> { it.modifiedMillis }.then(pathTie)
        SortMode.TITLE_ASC     -> compareBy<Note>           { it.title.lowercase() }.then(pathTie)
    }
    return compareByDescending<Note> { it.path in pinned }.then(base)
}

data class UiState(
    val screen: Screen = Screen.LIST,
    val listMode: ListViewMode = ListViewMode.LIST,
    val editorMode: EditorMode = EditorMode.EDIT,
    val hasFolder: Boolean = false,
    val query: String = "",
    val tree: TreeSnapshot = TreeSnapshot.EMPTY,
    val expandedFolders: Set<String> = emptySet(),
    val visibleNotes: List<Note> = emptyList(),
    /** Path → (title char positions, preview char positions). Empty when not searching. */
    val searchHighlights: Map<String, Pair<List<Int>, List<Int>>> = emptyMap(),
    val sortMode: SortMode = SortMode.DATE_DESC,
    val pinned: Set<String> = emptySet(),
    val trashNotes: List<Note> = emptyList(),
    val editingPath: String? = null,
    val editingParent: String = "",
    val editingTitle: TextFieldValue = TextFieldValue(""),
    val editingBody: TextFieldValue = TextFieldValue(""),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isSaving: Boolean = false,
    val isRefreshing: Boolean = false,
    val lastSavedAt: Long = 0L,
    val noteMenuFor: String? = null,
    val folderMenuFor: String? = null,
    val folderPickerFor: FolderPickerTask? = null,
    val showNewFolderDialog: Boolean = false,
    /** URI of the trash note awaiting "delete forever" confirmation, or null. */
    val confirmDeleteForever: String? = null,
    val confirmEmptyTrash: Boolean = false,
    val listScrollIndex: Int = 0,
    val listScrollOffset: Int = 0,
    val collageScrollIndex: Int = 0,
    val collageScrollOffset: Int = 0,
)

sealed class FolderPickerTask {
    data class Move(val noteUri: String, val noteParent: String) : FolderPickerTask()
    data class Copy(val noteUri: String) : FolderPickerTask()
}

class AppViewModel(private val app: App, private val repo: NoteRepository) : ViewModel() {
    private val _ui = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _ui.asStateFlow()

    /** One-shot (title, body) pairs for the system share sheet. Collected by MainActivity. */
    private val _shareEvents = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 1)
    val shareEvents: kotlinx.coroutines.flow.SharedFlow<Pair<String, String>> = _shareEvents

    private val history = EditorHistory()

    private var saveJob: Job? = null
    private var refreshJob: Job? = null
    private var enrichJob: Job? = null
    private var searchJob: Job? = null
    // URIs the user has explicitly removed (deleted or moved) but the SAF walk may
    // still surface for a short while due to provider listing cache. mergeSnapshot
    // suppresses these so the deleted/moved note doesn't get resurrected into the UI.
    private val pendingRemovals = java.util.Collections.synchronizedSet(HashSet<String>())

    fun bootstrap(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        viewModelScope.launch {
            loadPersistedUiPrefs()
            val hasFolder = repo.saf.getTreeUri() != null
            _ui.update { it.copy(hasFolder = hasFolder) }
            if (!hasFolder) return@launch
            val cached = repo.cachedNotes()
            if (cached.isNotEmpty()) {
                _ui.update { s -> s.copy(
                    tree = TreeSnapshot(notes = cached, folders = emptyList()),
                    visibleNotes = orderedVisible(cached, s, emptyList()),
                ) }
            }
            refreshTree()
        }
    }

    private suspend fun loadPersistedUiPrefs() {
        val sort = repo.saf.getPref(PREF_SORT_MODE)?.let { v ->
            SortMode.entries.firstOrNull { it.name == v }
        } ?: SortMode.DATE_DESC
        val listMode = repo.saf.getPref(PREF_LIST_MODE)?.let { v ->
            ListViewMode.entries.firstOrNull { it.name == v }
        } ?: ListViewMode.LIST
        val expanded = repo.saf.getPref(PREF_EXPANDED)?.split('\n')?.filter { it.isNotBlank() }?.toSet()
            ?: emptySet()
        val pinned = repo.saf.getPref(PREF_PINNED)?.split('\n')?.filter { it.isNotBlank() }?.toSet()
            ?: emptySet()
        _ui.update { it.copy(sortMode = sort, listMode = listMode, expandedFolders = expanded, pinned = pinned) }
    }

    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            val previous = runCatching { repo.saf.getTreeUri() }.getOrNull()
            repo.saf.saveTreeUri(uri)
            if (previous != null && previous.toString() != uri.toString()) {
                // Different folder — the old tree, expansion state, pins and pending
                // removals all refer to URIs under the previous root. Without this
                // reset, mergeSnapshot preserves the old folder's notes as "optimistic
                // entries" and the user sees both folders' notes interleaved.
                synchronized(pendingRemovals) { pendingRemovals.clear() }
                _ui.update { it.copy(
                    hasFolder = true,
                    tree = TreeSnapshot.EMPTY,
                    visibleNotes = emptyList(),
                    expandedFolders = emptySet(),
                    searchHighlights = emptyMap(),
                    query = "",
                    listScrollIndex = 0, listScrollOffset = 0,
                    collageScrollIndex = 0, collageScrollOffset = 0,
                ) }
                repo.saf.setPref(PREF_EXPANDED, "")
            } else {
                _ui.update { it.copy(hasFolder = true) }
            }
            refreshTree()
        }
    }

    fun refreshTree() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _ui.update { it.copy(isRefreshing = true) }
            try {
                val snap = repo.refreshTree()
                _ui.update { s ->
                    val merged = mergeSnapshot(s.tree, snap)
                    s.copy(
                        tree = merged,
                        visibleNotes = orderedVisible(merged.notes, s, merged.folders),
                        isRefreshing = false,
                    )
                }
                scheduleEnrich()
            } catch (t: kotlinx.coroutines.CancellationException) {
                // Refresh was superseded by a newer one — not an error, don't log it.
                throw t
            } catch (t: Throwable) {
                CrashReporter.report(app, "refreshTree", t)
                _ui.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /**
     * Read note bodies in the background to fill in preview / tags / label that the
     * fast walk skipped, and to keep the FTS index in sync. Updates the UI in batches
     * so re-renders don't thrash on a 4000-note tree. Cancellable: any new refreshTree
     * starts a fresh enrichment pass.
     */
    private fun scheduleEnrich() {
        enrichJob?.cancel()
        enrichJob = viewModelScope.launch {
            val toEnrich = _ui.value.tree.notes.filter { it.preview.isEmpty() }
            if (toEnrich.isEmpty()) return@launch
            val batchSize = 25
            val enrichedByPath = HashMap<String, Note>(batchSize * 2)
            for ((i, note) in toEnrich.withIndex()) {
                val enriched = repo.enrichOne(note) ?: continue
                enrichedByPath[note.path] = enriched
                val isBatchEnd = (i + 1) % batchSize == 0 || i == toEnrich.lastIndex
                if (isBatchEnd) {
                    val snapshot = enrichedByPath.toMap()
                    enrichedByPath.clear()
                    _ui.update { s ->
                        val newNotes = s.tree.notes.map { n -> snapshot[n.path] ?: n }
                        s.copy(
                            tree = s.tree.copy(notes = newNotes),
                            visibleNotes = orderedVisible(newNotes, s, s.tree.folders),
                        )
                    }
                }
            }
        }
    }

    /**
     * Merge the freshly-walked SAF snapshot with the current in-memory tree, preserving
     * any optimistic entries (just-created notes/folders) that the walk hasn't picked up
     * yet — the underlying ExternalStorageProvider often serves a stale child listing
     * for several seconds after createFile/createDirectory. Without this merge, refresh
     * would wipe the optimistic insert and the user would see their fresh entry vanish.
     */
    private fun mergeSnapshot(existing: TreeSnapshot, fresh: TreeSnapshot): TreeSnapshot {
        val existingByPath = existing.notes.associateBy { it.path }
        // Drop entries the user has explicitly removed (delete-to-trash, move-to-folder).
        // Without this, mergeSnapshot would treat the gone-from-walk source URI as an
        // optimistic entry to preserve, and the note would reappear in its old location.
        val freshNotes = fresh.notes.filter { it.path !in pendingRemovals }
        val freshFolders = fresh.folders.filter { it.path !in pendingRemovals }
        // Once a removed URI is gone from the fresh walk we can stop suppressing it —
        // the SAF cache has caught up, so the entry won't come back next time.
        val freshAllPaths = (fresh.notes.map { it.path } + fresh.folders.map { it.path }).toSet()
        synchronized(pendingRemovals) {
            pendingRemovals.removeAll { it !in freshAllPaths && existingByPath[it] == null }
        }
        val enrichedFresh = freshNotes.map { f ->
            val cached = existingByPath[f.path]
            if (cached != null && f.preview.isEmpty() && cached.preview.isNotEmpty()) {
                f.copy(
                    preview = cached.preview,
                    tags = if (f.tags.isEmpty()) cached.tags else f.tags,
                    label = f.label ?: cached.label,
                )
            } else f
        }
        val freshNotePaths = freshNotes.mapTo(HashSet()) { it.path }
        val freshFolderPaths = freshFolders.mapTo(HashSet()) { it.path }
        val extraNotes = existing.notes
            .filter { it.path !in freshNotePaths && it.path !in pendingRemovals }
        val extraFolders = existing.folders
            .filter { it.path !in freshFolderPaths && it.path !in pendingRemovals }
        return TreeSnapshot(
            notes = (enrichedFresh + extraNotes).sortedWith(stableDisplayDesc),
            folders = freshFolders + extraFolders,
        )
    }

    fun toggleFolder(path: String) {
        _ui.update { s ->
            val next = if (path in s.expandedFolders) s.expandedFolders - path else s.expandedFolders + path
            s.copy(
                expandedFolders = next,
                visibleNotes = orderedVisible(s.tree.notes, s.copy(expandedFolders = next), s.tree.folders),
            )
        }
        persistExpanded()
    }

    private fun persistExpanded() {
        val snapshot = _ui.value.expandedFolders.joinToString("\n")
        viewModelScope.launch { repo.saf.setPref(PREF_EXPANDED, snapshot) }
    }

    fun setSortMode(mode: SortMode) {
        _ui.update { s ->
            s.copy(
                sortMode = mode,
                visibleNotes = orderedVisible(s.tree.notes, s.copy(sortMode = mode), s.tree.folders),
            )
        }
        viewModelScope.launch { repo.saf.setPref(PREF_SORT_MODE, mode.name) }
    }

    fun togglePin(path: String) {
        _ui.update { s ->
            val next = if (path in s.pinned) s.pinned - path else s.pinned + path
            s.copy(
                pinned = next,
                noteMenuFor = null,
                visibleNotes = orderedVisible(s.tree.notes, s.copy(pinned = next), s.tree.folders),
            )
        }
        val snapshot = _ui.value.pinned.joinToString("\n")
        viewModelScope.launch { repo.saf.setPref(PREF_PINNED, snapshot) }
    }

    fun setQuery(q: String) {
        // Field update must be synchronous — see HANDOVER gotcha 8 (IME desync).
        _ui.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (q.isBlank()) {
                _ui.update { s ->
                    if (s.query.isNotBlank()) return@update s
                    s.copy(
                        visibleNotes = orderedVisible(s.tree.notes, s, s.tree.folders),
                        searchHighlights = emptyMap(),
                    )
                }
                return@launch
            }
            val needle = q.trim()
            val notes = _ui.value.tree.notes
            // Strict case-insensitive substring match on title / preview / label / tags —
            // results the user can recognise as matches.
            val matched = notes.filter { n ->
                n.title.contains(needle, ignoreCase = true) ||
                n.preview.contains(needle, ignoreCase = true) ||
                (n.label?.contains(needle, ignoreCase = true) == true) ||
                n.tags.any { it.contains(needle, ignoreCase = true) }
            }
            val highlights = matched.associate { n ->
                n.path to (indicesOf(n.title, needle) to indicesOf(n.preview, needle))
            }
            // Full-text hits from the FTS index: notes whose *body* matches even though
            // the visible title/preview doesn't. The index existed since v1.0 but search
            // never consulted it — body text was simply unfindable.
            val bodyHits: List<Note> = if (repo.hasNativeIndex) {
                val substringPaths = matched.mapTo(HashSet()) { it.path }
                val byPath = notes.associateBy { it.path }
                runCatching { repo.search(needle, emptyList()) }.getOrDefault(emptyList())
                    .mapNotNull { hit -> byPath[hit.path] }   // only notes that still exist in the tree
                    .filter { it.path !in substringPaths }
            } else emptyList()
            val ordered = (matched + bodyHits)
                .sortedWith(noteComparator(_ui.value.sortMode, _ui.value.pinned))
            _ui.update { s ->
                if (s.query != q) return@update s
                s.copy(visibleNotes = ordered, searchHighlights = highlights)
            }
        }
    }

    /** All character offsets where [needle] starts inside [haystack] (case-insensitive). */
    private fun indicesOf(haystack: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from, ignoreCase = true)
            if (i < 0) break
            for (k in 0 until needle.length) out += (i + k)
            from = i + needle.length
        }
        return out
    }

    fun toggleListMode() {
        _ui.update { it.copy(listMode = if (it.listMode == ListViewMode.LIST) ListViewMode.COLLAGE else ListViewMode.LIST) }
        val snapshot = _ui.value.listMode.name
        viewModelScope.launch { repo.saf.setPref(PREF_LIST_MODE, snapshot) }
    }

    fun saveListScrollPosition(index: Int, offset: Int) {
        _ui.update { it.copy(listScrollIndex = index, listScrollOffset = offset) }
    }

    fun saveCollageScrollPosition(index: Int, offset: Int) {
        _ui.update { it.copy(collageScrollIndex = index, collageScrollOffset = offset) }
    }

    /** Visibility filter + pinned-first sort, applied together everywhere the list changes. */
    private fun orderedVisible(notes: List<Note>, s: UiState, folders: List<FolderInfo>): List<Note> =
        filterVisible(notes, s.query, s.expandedFolders, folders)
            .sortedWith(noteComparator(s.sortMode, s.pinned))

    private fun filterVisible(notes: List<Note>, query: String, expanded: Set<String>, folders: List<FolderInfo>): List<Note> {
        if (query.isNotBlank()) return notes
        if (folders.isEmpty()) return notes
        val rootParents = folders.filter { it.depth == 0 }.map { it.parent }.toSet()
        return notes.filter { n ->
            if (n.parent in rootParents) return@filter true
            var p = n.parent
            while (true) {
                if (p in expanded) {
                    val f = folders.firstOrNull { it.path == p } ?: return@filter true
                    if (f.depth == 0) return@filter true
                    p = f.parent
                } else return@filter false
            }
            @Suppress("UNREACHABLE_CODE") true
        }
    }

    fun newNote(parentFolder: String? = null) {
        viewModelScope.launch {
            val created = repo.create(parentFolder = parentFolder)
            if (created == null) {
                CrashReporter.report(app, "newNote.create",
                    IllegalStateException("repo.create returned null — likely no tree permission " +
                        "or createFile failed (treeUri=${repo.saf.getTreeUri()})"))
                return@launch
            }
            val (path, parent) = created
            // Reset saved scroll position so when the user closes the editor the list
            // shows the just-created (most recent) note at the top instead of restoring
            // wherever they were scrolled to before tapping +.
            _ui.update { it.copy(
                folderMenuFor = null,
                listScrollIndex = 0, listScrollOffset = 0,
                collageScrollIndex = 0, collageScrollOffset = 0,
            ) }
            openNoteRaw(path, parent, "", fileNameFor(path))
            refreshTree()
        }
    }

    private fun fileNameFor(path: String): String =
        repo.saf.singleDoc(Uri.parse(path))?.name ?: ""

    fun openNote(note: Note) {
        // Already opening this one — ignore re-taps.
        if (_ui.value.editingPath == note.path && _ui.value.screen == Screen.EDITOR) return
        // Read first, switch second. The previous "flip to editor instantly" approach caused
        // a visible flash when the read returned null (stale URI / transient SAF error):
        // editor would open then immediately bounce back to the list, and the next tap on
        // the now-stale cache row repeated the cycle.
        viewModelScope.launch {
            // Single retry — Samsung / cloud SAF providers transiently return null on read
            // even for files that exist. A second attempt usually succeeds.
            var body = repo.read(note.path)
            if (body == null) {
                delay(80)
                body = repo.read(note.path)
            }
            if (body == null) {
                android.util.Log.w("BlackNote.AppViewModel",
                    "openNote: read returned null for ${note.path} (lastReadError=${repo.saf.lastReadError})")
                // Stale URI? Refresh and *wait* — earlier behavior fire-and-forgot the
                // refresh, so rapid taps cancelled it before it could land, leaving the
                // user unable to open the note "no matter how many times" they tapped.
                refreshTreeAwait()
                // Try to relocate the same logical note. Title-based lookup is brittle
                // since titles now follow file names (and rename changes the title), so
                // start with URI equality (still works when the URI is just transiently
                // unreadable) and fall back to parent+name proximity.
                val current = _ui.value.tree.notes
                val refreshed = current.firstOrNull { it.path == note.path }
                    ?: current.firstOrNull { it.parent == note.parent && it.title == note.title }
                    ?: current.firstOrNull { it.title == note.title }
                if (refreshed != null) {
                    // One more read attempt against the (possibly fresh) URI.
                    val recoveredBody = repo.read(refreshed.path)
                    if (recoveredBody != null) {
                        openNoteRaw(refreshed.path, refreshed.parent, recoveredBody, fileNameFor(refreshed.path))
                        return@launch
                    }
                    // Read still failing — but the file *exists* (walk found it) and the
                    // user is tapping it. Open the editor anyway with an empty body so
                    // they can at least navigate in. Surface the read failure so they
                    // know not to overwrite content blindly.
                    CrashReporter.report(app, "openNote.unreadable",
                        IllegalStateException("file exists but read kept failing for ${refreshed.path} " +
                            "(lastReadError=${repo.saf.lastReadError})"))
                    openNoteRaw(refreshed.path, refreshed.parent, "", fileNameFor(refreshed.path))
                }
                return@launch
            }
            openNoteRaw(note.path, note.parent, body, fileNameFor(note.path))
        }
    }

    /**
     * Open the note whose title matches a [[wiki link]] target. Falls back to substring
     * match so "[[groceries]]" finds "Groceries 2026". No-op when nothing matches.
     */
    fun openWikiLink(target: String) {
        val t = target.trim()
        if (t.isEmpty()) return
        val notes = _ui.value.tree.notes
        val hit = notes.firstOrNull { it.title.equals(t, ignoreCase = true) }
            ?: notes.firstOrNull { it.title.contains(t, ignoreCase = true) }
            ?: return
        // Leaving the current note — persist it the same way back does.
        if (_ui.value.screen == Screen.EDITOR) closeEditor(navigate = false)
        openNote(hit)
    }

    /** Refresh the tree and suspend until it actually completes (or fails). */
    private suspend fun refreshTreeAwait() {
        refreshJob?.cancel()
        val job = viewModelScope.launch {
            _ui.update { it.copy(isRefreshing = true) }
            try {
                val snap = repo.refreshTree()
                _ui.update { s ->
                    val merged = mergeSnapshot(s.tree, snap)
                    s.copy(
                        tree = merged,
                        visibleNotes = orderedVisible(merged.notes, s, merged.folders),
                        isRefreshing = false,
                    )
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                CrashReporter.report(app, "refreshTreeAwait", t)
                _ui.update { it.copy(isRefreshing = false) }
            }
        }
        refreshJob = job
        job.join()
    }

    internal fun openNoteRaw(path: String, parent: String, raw: String, fileName: String = "") {
        // Title is the on-disk file name (minus .md). Body is whatever's in the file —
        // no `# Heading` stripping anymore, so the body field always shows exactly what
        // the user typed. Saving the title field renames the file.
        val titleLine = com.marsglorious.blacknote.data.titleFromFileName(fileName)
        val titleTfv = TextFieldValue(titleLine, TextRange(titleLine.length))
        val bodyTfv = TextFieldValue(raw, TextRange(0))
        history.reset(EditorSnapshot(titleTfv, bodyTfv))
        _ui.update {
            it.copy(
                screen = Screen.EDITOR,
                editorMode = EditorMode.EDIT,
                editingPath = path, editingParent = parent,
                editingTitle = titleTfv, editingBody = bodyTfv,
                canUndo = false, canRedo = false,
            )
        }
    }

    fun toggleEditorMode() {
        _ui.update { it.copy(editorMode = if (it.editorMode == EditorMode.EDIT) EditorMode.RENDER else EditorMode.EDIT) }
    }

    fun closeEditor(navigate: Boolean = true) {
        // Snapshot the editor contents BEFORE we swap to LIST so the background
        // save sees them, but flip the screen immediately so the user sees no delay.
        val s = _ui.value
        val path = s.editingPath
        val parent = s.editingParent
        val titleText = s.editingTitle.text
        val bodyText = s.editingBody.text
        saveJob?.cancel()
        _ui.update {
            it.copy(
                screen = if (navigate) Screen.LIST else it.screen,
                editorMode = EditorMode.EDIT,
                editingPath = null, editingParent = "",
                editingTitle = TextFieldValue(""), editingBody = TextFieldValue(""),
                canUndo = false, canRedo = false,
            )
        }
        viewModelScope.launch {
            if (path != null) {
                val wrote = runCatching { repo.write(path, parent, bodyText) }
                    .onFailure { CrashReporter.report(app, "closeEditor.write.throw", it) }
                    .getOrDefault(false)
                val renamedUri: String? = if (wrote) {
                    runCatching { repo.renameToMatchTitle(path, parent, titleText) }
                        .onFailure { CrashReporter.report(app, "closeEditor.rename.throw", it) }
                        .getOrNull()
                } else {
                    CrashReporter.report(app, "closeEditor.save",
                        IllegalStateException("write returned false for $path bodyLen=${bodyText.length} " +
                            "lastReadError=${repo.saf.lastReadError}"))
                    null
                }
                val finalUri = renamedUri ?: path
                // SAF providers (Samsung's ExternalStorageProvider in particular) often
                // serve stale child-listings right after createFile, so refreshTree's
                // listFiles() walk won't see a brand-new note until the provider's cache
                // catches up — which can take until the app is restarted. Splice the just-
                // saved note into the in-memory tree directly so the user sees it
                // immediately. Source the preview from a fresh read of the persisted file
                // (not the editor buffer) so the list can't desync with disk; if the read
                // fails, skip the optimistic insert and let refreshTree do its thing.
                val persisted = runCatching { repo.read(finalUri) }.getOrNull()
                if (persisted != null) {
                    val now = System.currentTimeMillis()
                    // Keep the original creation date — the optimistic entry used to stamp
                    // createdMillis = now, which made an old note's card date jump to
                    // "just now" until the next full refresh corrected it.
                    val original = _ui.value.tree.notes.firstOrNull { it.path == path || it.path == finalUri }
                    val fileName = fileNameFor(finalUri).ifBlank { "Untitled.md" }
                    val justSaved = Note(
                        path = finalUri, parent = parent,
                        title = com.marsglorious.blacknote.data.titleFromFileName(fileName),
                        preview = persisted.take(200),
                        modifiedMillis = now,
                        createdMillis = original?.createdMillis ?: now,
                        tags = original?.tags ?: emptyList(),
                        label = original?.label,
                    )
                    // A rename means the old URI is dead — suppress it so a stale walk
                    // doesn't resurrect the pre-rename file alongside the renamed one.
                    if (finalUri != path) pendingRemovals.add(path)
                    _ui.update { st ->
                        val rest = st.tree.notes.filter { it.path != path && it.path != finalUri }
                        val merged = (listOf(justSaved) + rest).sortedWith(stableDisplayDesc)
                        st.copy(
                            tree = st.tree.copy(notes = merged),
                            visibleNotes = orderedVisible(merged, st, st.tree.folders),
                        )
                    }
                }
            }
            refreshTree()
        }
    }

    fun onTitleChange(t: TextFieldValue) {
        history.record(EditorSnapshot(t, _ui.value.editingBody))
        _ui.update { it.copy(editingTitle = t, canUndo = history.canUndo, canRedo = history.canRedo) }
        scheduleSave()
    }

    fun onBodyChange(b: TextFieldValue) {
        history.record(EditorSnapshot(_ui.value.editingTitle, b))
        _ui.update { it.copy(editingBody = b, canUndo = history.canUndo, canRedo = history.canRedo) }
        scheduleSave()
    }

    fun undo() {
        val prev = history.undo() ?: return
        _ui.update { it.copy(
            editingTitle = prev.title, editingBody = prev.body,
            canUndo = history.canUndo, canRedo = history.canRedo,
        ) }
        scheduleSave()
    }

    fun redo() {
        val next = history.redo() ?: return
        _ui.update { it.copy(
            editingTitle = next.title, editingBody = next.body,
            canUndo = history.canUndo, canRedo = history.canRedo,
        ) }
        scheduleSave()
    }

    fun format(kind: FormatKind) {
        val cur = _ui.value.editingBody
        val range = EditorHistory.safeRange(cur)
        val r = MarkdownFormat.apply(cur.text, range.first, range.last, kind)
        val next = TextFieldValue(r.text, TextRange(r.selStart, r.selEnd))
        history.record(EditorSnapshot(_ui.value.editingTitle, next))
        _ui.update { it.copy(editingBody = next, canUndo = history.canUndo, canRedo = history.canRedo) }
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(500); saveNow() }
    }

    fun saveNow() {
        val s = _ui.value
        val path = s.editingPath ?: return
        viewModelScope.launch {
            _ui.update { it.copy(isSaving = true) }
            // Body verbatim — title is the file name and renames separately on close.
            val ok = runCatching { repo.write(path, s.editingParent, s.editingBody.text) }.getOrDefault(false)
            if (!ok) {
                CrashReporter.report(app, "saveNow",
                    IllegalStateException("autosave write failed for $path"))
            }
            _ui.update { it.copy(isSaving = false, lastSavedAt = System.currentTimeMillis()) }
        }
    }

    /** Share a note from the list — reads the body fresh so the share always matches disk. */
    fun shareNote(note: Note) {
        _ui.update { it.copy(noteMenuFor = null) }
        viewModelScope.launch {
            val body = repo.read(note.path) ?: note.preview
            _shareEvents.tryEmit(note.title to body)
        }
    }

    /** Share the note currently open in the editor, straight from the buffer. */
    fun shareCurrentNote() {
        val s = _ui.value
        if (s.editingPath == null) return
        _shareEvents.tryEmit(s.editingTitle.text to s.editingBody.text)
    }

    fun openNoteMenu(path: String) { _ui.update { it.copy(noteMenuFor = path) } }
    fun closeNoteMenu() { _ui.update { it.copy(noteMenuFor = null) } }
    fun openFolderMenu(path: String) { _ui.update { it.copy(folderMenuFor = path) } }
    fun closeFolderMenu() { _ui.update { it.copy(folderMenuFor = null) } }

    fun startMove(noteUri: String, parent: String) {
        _ui.update { it.copy(noteMenuFor = null, folderPickerFor = FolderPickerTask.Move(noteUri, parent)) }
    }
    fun startCopy(noteUri: String) {
        _ui.update { it.copy(noteMenuFor = null, folderPickerFor = FolderPickerTask.Copy(noteUri)) }
    }
    fun cancelFolderPicker() { _ui.update { it.copy(folderPickerFor = null) } }

    fun completeFolderPicker(targetFolderUri: String) {
        val task = _ui.value.folderPickerFor ?: return
        // Optimistically drop the source note from the list immediately for Move tasks,
        // and remember the URI as a pending removal so a stale SAF walk doesn't put it
        // back in the source folder. Copy tasks keep the source in place.
        if (task is FolderPickerTask.Move) {
            pendingRemovals.add(task.noteUri)
            _ui.update { s ->
                val rest = s.tree.notes.filter { it.path != task.noteUri }
                s.copy(
                    tree = s.tree.copy(notes = rest),
                    visibleNotes = orderedVisible(rest, s, s.tree.folders),
                )
            }
        }
        viewModelScope.launch {
            val ok = when (task) {
                is FolderPickerTask.Move -> repo.moveTo(task.noteUri, task.noteParent, targetFolderUri)
                is FolderPickerTask.Copy -> repo.copyTo(task.noteUri, targetFolderUri)
            }
            if (!ok) {
                // Move/copy failed — undo the optimistic removal so the note returns.
                if (task is FolderPickerTask.Move) pendingRemovals.remove(task.noteUri)
                CrashReporter.report(app, "completeFolderPicker",
                    IllegalStateException("$task to $targetFolderUri returned false"))
            }
            _ui.update { it.copy(folderPickerFor = null) }
            refreshTree()
        }
    }

    fun deleteToTrash(noteUri: String, parent: String) {
        pendingRemovals.add(noteUri)
        _ui.update { current ->
            val filteredNotes = current.tree.notes.filter { it.path != noteUri }
            val filteredVisible = current.visibleNotes.filter { it.path != noteUri }
            current.copy(
                noteMenuFor = null,
                tree = current.tree.copy(notes = filteredNotes),
                visibleNotes = filteredVisible
            )
        }
        viewModelScope.launch {
            val moved = repo.moveToTrash(noteUri, parent)
            if (!moved) {
                // Optimistic remove lied to the user — put the note back so it isn't lost
                // from view, and report so the failure is visible.
                pendingRemovals.remove(noteUri)
                refreshTreeAwait()
                CrashReporter.report(app, "deleteToTrash",
                    IllegalStateException("moveToTrash returned false for $noteUri"))
                return@launch
            }
            // Refresh main tree (off-screen) and trash listing (the one user sees).
            refreshTree()
            val trashNotes = repo.refreshTrash()
            _ui.update { it.copy(trashNotes = trashNotes) }
        }
    }

    fun openTrash() {
        // Navigate immediately so the screen opens without waiting for SAF I/O.
        _ui.update { it.copy(screen = Screen.TRASH) }
        viewModelScope.launch {
            val notes = repo.refreshTrash()
            _ui.update { it.copy(trashNotes = notes) }
        }
    }

    fun restoreFromTrash(noteUri: String) {
        viewModelScope.launch {
            val ok = repo.restoreFromTrash(noteUri)
            if (!ok) {
                CrashReporter.report(app, "restoreFromTrash",
                    IllegalStateException("restoreFromTrash returned false for $noteUri"))
            }
            val notes = repo.refreshTrash()
            _ui.update { it.copy(trashNotes = notes) }
            refreshTree()
        }
    }

    /** "Delete forever" now asks first — a one-tap permanent delete was too easy to fat-finger. */
    fun requestDeletePermanently(noteUri: String) {
        _ui.update { it.copy(confirmDeleteForever = noteUri) }
    }
    fun cancelDeletePermanently() { _ui.update { it.copy(confirmDeleteForever = null) } }
    fun confirmDeletePermanently() {
        val uri = _ui.value.confirmDeleteForever ?: return
        _ui.update { it.copy(confirmDeleteForever = null) }
        viewModelScope.launch {
            val ok = repo.deletePermanently(uri)
            if (!ok) {
                CrashReporter.report(app, "deletePermanently",
                    IllegalStateException("deletePermanently returned false for $uri"))
            }
            val notes = repo.refreshTrash()
            _ui.update { it.copy(trashNotes = notes) }
        }
    }

    fun requestEmptyTrash() { _ui.update { it.copy(confirmEmptyTrash = true) } }
    fun cancelEmptyTrash() { _ui.update { it.copy(confirmEmptyTrash = false) } }
    fun confirmEmptyTrash() {
        _ui.update { it.copy(confirmEmptyTrash = false) }
        viewModelScope.launch {
            runCatching { repo.emptyTrash() }
                .onFailure { CrashReporter.report(app, "emptyTrash", it) }
            val notes = repo.refreshTrash()
            _ui.update { it.copy(trashNotes = notes) }
        }
    }

    fun openSettings() { _ui.update { it.copy(screen = Screen.SETTINGS) } }
    fun backToList() { _ui.update { it.copy(screen = Screen.LIST) } }

    fun openNewFolderDialog() { _ui.update { it.copy(showNewFolderDialog = true) } }
    fun cancelNewFolder() { _ui.update { it.copy(showNewFolderDialog = false) } }
    fun createFolder(name: String) {
        viewModelScope.launch {
            val createdUri = repo.createFolderReturningUri(name)
            _ui.update { it.copy(showNewFolderDialog = false) }
            if (createdUri == null) {
                CrashReporter.report(app, "createFolder",
                    IllegalStateException("createFolder('$name') returned null — folder exists or createDirectory failed"))
            } else {
                // Same SAF cache problem as new notes: createDirectory often isn't
                // reflected in the parent's listFiles() until the app restarts.
                // Splice the folder into the in-memory tree so it shows up now;
                // refreshTree below reconciles when the cache catches up.
                val safe = com.marsglorious.blacknote.data.sanitizeFileName(name, fallback = "Folder")
                val rootUri = repo.saf.getTreeUri()?.toString()
                if (rootUri != null) {
                    _ui.update { s ->
                        val newFolder = FolderInfo(
                            path = createdUri, parent = rootUri, name = safe, depth = 0,
                        )
                        val merged = s.tree.folders.filter { it.path != createdUri } + newFolder
                        s.copy(tree = s.tree.copy(folders = merged))
                    }
                }
            }
            refreshTree()
        }
    }

    companion object {
        const val PREF_SORT_MODE = "ui_sort_mode"
        const val PREF_LIST_MODE = "ui_list_mode"
        const val PREF_EXPANDED = "ui_expanded_folders"
        const val PREF_PINNED = "ui_pinned_paths"

        fun factory(app: App) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(app, app.repository) as T
        }
    }
}
