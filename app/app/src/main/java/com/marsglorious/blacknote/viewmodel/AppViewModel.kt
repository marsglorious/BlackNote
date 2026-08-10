package com.marsglorious.blacknote.viewmodel

import android.content.Context
import androidx.compose.ui.text.TextRange
import java.io.File
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
import com.marsglorious.blacknote.data.FormatKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

enum class Screen { LIST, EDITOR, TRASH, SETTINGS }
enum class ListViewMode { LIST, COLLAGE }
enum class EditorMode { EDIT, RENDER }

/**
 * A ranked hashtag suggestion for the picker.
 *
 * @param tag        the hashtag (without the leading '#'), in its most-recently-used casing
 * @param noteCount  how many notes in the tree already carry this tag
 * @param score      importance score for the current note (see [AppViewModel.rankHashtagSuggestions]):
 *                   combines how often the tag's words are mentioned here, how similar this note
 *                   is to the notes already tagged with it (shared vocabulary + comparable length),
 *                   plus the tag's popularity and recency.
 */
data class HashtagSuggestion(
    val tag: String,
    val noteCount: Int,
    val score: Int,
)

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
        SortMode.DATE_DESC     -> compareByDescending<Note> { it.modifiedMillis }.then(pathTie)
        SortMode.DATE_ASC      -> compareBy<Note>           { it.modifiedMillis }.then(pathTie)
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
    /** True when the app has a folder path but MANAGE_EXTERNAL_STORAGE hasn't been granted
     *  yet, meaning the file walk will return empty results on Android 11+. */
    val needsManagePermission: Boolean = false,
    /** False until bootstrap has resolved whether a folder is configured. Used to avoid
     *  flashing the "Pick a folder" screen during the brief async startup read. */
    val folderKnown: Boolean = false,
    /** False until the first tree load completes. Guards the "No notes yet" placeholder so it
     *  can't flash before notes have actually been loaded. */
    val initialLoadComplete: Boolean = false,
    val query: String = "",
    val tree: TreeSnapshot = TreeSnapshot.EMPTY,
    val expandedFolders: Set<String> = emptySet(),
    val visibleNotes: List<Note> = emptyList(),
    /** Path → (title char positions, preview char positions). Empty when not searching. */
    val searchHighlights: Map<String, Pair<List<Int>, List<Int>>> = emptyMap(),
    val sortMode: SortMode = SortMode.DATE_DESC,
    /** Whether the folder-path line is shown under each note in the list. */
    val showFileLocation: Boolean = true,
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
    /** One-shot: set when a newly-created note lands at the top of the list, so the list
     *  scrolls up to reveal it. Cleared by the UI once consumed. Other edits leave it false
     *  so the list stays where the user scrolled to. */
    val scrollListToTop: Boolean = false,
    val collageScrollIndex: Int = 0,
    val collageScrollOffset: Int = 0,
    /** Non-null while the self-test results dialog is showing. */
    val selfTestResults: List<com.marsglorious.blacknote.selftest.SelfTestResult>? = null,
    /** True while the hashtag suggestion picker row is visible above the format toolbar. */
    val hashtagPickerOpen: Boolean = false,
    /** Ranked hashtag suggestions for the current note; populated when [hashtagPickerOpen] = true. */
    val hashtagSuggestions: List<HashtagSuggestion> = emptyList(),
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

    // Path of the note just created via newNote(), tracked until its editor closes so we
    // can decide whether returning to the list should scroll to the top (see closeEditor).
    private var pendingNewNotePath: String? = null

    // The body and (displayed) title the current note was opened with. closeEditor compares
    // against these so opening a note and closing it WITHOUT edits never rewrites the file —
    // which would otherwise bump its modified date and reorder the list.
    private var openedNoteBody: String = ""
    private var openedNoteTitle: String = ""

    private fun storagePermissionGranted(): Boolean {
        // Android 11+ (API 30+): need MANAGE_EXTERNAL_STORAGE to list generic files
        // like .md via java.io.File. Without it, File.listFiles() on external storage
        // silently omits non-media files under scoped storage rules.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
            return android.os.Environment.isExternalStorageManager()
        // Android 10 and below: WRITE_EXTERNAL_STORAGE with requestLegacyExternalStorage=true
        // gives full storage access.
        return androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun bootstrap(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        viewModelScope.launch {
            loadPersistedUiPrefs()
            val folderPath = repo.fs.getFolderPath()
            val hasFolder = folderPath != null
            // Permission is checked separately from hasFolder so the folder choice is
            // remembered across restarts even when MANAGE_EXTERNAL_STORAGE hasn't been
            // granted yet. Without this split, storagePermissionGranted() returning false
            // would reset hasFolder = false every restart, forcing the user to re-pick.
            val needsPermission = hasFolder && !storagePermissionGranted()
            _ui.update { it.copy(hasFolder = hasFolder, needsManagePermission = needsPermission, folderKnown = true) }
            if (!hasFolder || needsPermission) return@launch
            loadNotesFromDiskAndCache(folderPath)
        }
    }

    private suspend fun loadNotesFromDiskAndCache(folderPath: String) {
        // Show database cache instantly. Filter out any legacy content:// URI paths that
        // were stored before the migration to direct file access.
        val cached = repo.cachedNotes().filter { !it.path.startsWith("content://") }
        if (cached.isNotEmpty()) {
            val folders = com.marsglorious.blacknote.data.foldersFromNotes(cached, folderPath)
            _ui.update { s -> s.copy(
                tree = TreeSnapshot(notes = cached, folders = folders),
                visibleNotes = orderedVisible(cached, s, folders),
            ) }
        }
        refreshTree()
    }

    /** Called after the user grants MANAGE_EXTERNAL_STORAGE (or WRITE_EXTERNAL_STORAGE on
     *  Android ≤10). Re-checks the permission and loads notes if now granted. */
    fun onPermissionGranted() {
        if (!storagePermissionGranted()) return
        _ui.update { it.copy(needsManagePermission = false) }
        viewModelScope.launch {
            val folderPath = repo.fs.getFolderPath() ?: return@launch
            loadNotesFromDiskAndCache(folderPath)
        }
    }

    private suspend fun loadPersistedUiPrefs() {
        val sort = repo.fs.getPref(PREF_SORT_MODE)?.let { v ->
            SortMode.entries.firstOrNull { it.name == v }
        } ?: SortMode.DATE_DESC
        val listMode = repo.fs.getPref(PREF_LIST_MODE)?.let { v ->
            ListViewMode.entries.firstOrNull { it.name == v }
        } ?: ListViewMode.LIST
        val expanded = repo.fs.getPref(PREF_EXPANDED)?.split('\n')?.filter { it.isNotBlank() }?.toSet()
            ?: emptySet()
        val pinned = repo.fs.getPref(PREF_PINNED)?.split('\n')?.filter { it.isNotBlank() }?.toSet()
            ?: emptySet()
        val showLocation = repo.fs.getPref(PREF_SHOW_LOCATION) != "false"
        _ui.update { it.copy(sortMode = sort, listMode = listMode, expandedFolders = expanded,
            pinned = pinned, showFileLocation = showLocation) }
    }

    fun setShowFileLocation(show: Boolean) {
        _ui.update { it.copy(showFileLocation = show) }
        viewModelScope.launch { repo.fs.setPref(PREF_SHOW_LOCATION, show.toString()) }
    }

    fun onFolderPicked(path: String) {
        viewModelScope.launch {
            val previous = runCatching { repo.fs.getFolderPath() }.getOrNull()
            repo.fs.saveFolderPath(path)
            val needsPermission = !storagePermissionGranted()
            if (previous != null && previous != path) {
                // Different folder — the old tree, expansion state, pins and pending
                // removals all refer to paths under the previous root. Without this
                // reset, mergeSnapshot preserves the old folder's notes as "optimistic
                // entries" and the user sees both folders' notes interleaved.
                synchronized(pendingRemovals) { pendingRemovals.clear() }
                _ui.update { it.copy(
                    hasFolder = true,
                    needsManagePermission = needsPermission,
                    tree = TreeSnapshot.EMPTY,
                    visibleNotes = emptyList(),
                    expandedFolders = emptySet(),
                    searchHighlights = emptyMap(),
                    query = "",
                    listScrollIndex = 0, listScrollOffset = 0,
                    collageScrollIndex = 0, collageScrollOffset = 0,
                ) }
                repo.fs.setPref(PREF_EXPANDED, "")
            } else {
                _ui.update { it.copy(hasFolder = true, needsManagePermission = needsPermission) }
            }
            if (!needsPermission) refreshTree()
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
                        initialLoadComplete = true,
                    )
                }
                scheduleEnrich()
            } catch (t: kotlinx.coroutines.CancellationException) {
                // Refresh was superseded by a newer one — not an error, don't log it.
                throw t
            } catch (t: Throwable) {
                CrashReporter.report(app, "refreshTree", t)
                _ui.update { it.copy(isRefreshing = false, initialLoadComplete = true) }
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
                        val newNotes = s.tree.notes.map { n ->
                            val e = snapshot[n.path] ?: return@map n
                            // Preserve createdMillis and modifiedMillis from the current
                            // in-memory note. enrichOne updates the FTS with the real
                            // frontmatter values (for next startup), but changing them
                            // here would reorder the list mid-session. The walk already
                            // had the correct values from refreshTree's FTS pre-population.
                            e.copy(createdMillis = n.createdMillis, modifiedMillis = n.modifiedMillis)
                        }
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
            // Restore preview/tags/label that the fast walk didn't read.
            // Sort keys (modifiedMillis, path) come from the SAF walk and are not touched.
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
            .filter { it.path !in freshNotePaths && it.path !in pendingRemovals && !it.path.startsWith("content://") }
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
        viewModelScope.launch { repo.fs.setPref(PREF_EXPANDED, snapshot) }
    }

    fun setSortMode(mode: SortMode) {
        _ui.update { s ->
            s.copy(
                sortMode = mode,
                visibleNotes = orderedVisible(s.tree.notes, s.copy(sortMode = mode), s.tree.folders),
            )
        }
        viewModelScope.launch { repo.fs.setPref(PREF_SORT_MODE, mode.name) }
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
        viewModelScope.launch { repo.fs.setPref(PREF_PINNED, snapshot) }
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
            for (k in needle.indices) out += (i + k)
            from = i + needle.length
        }
        return out
    }

    fun toggleListMode() {
        _ui.update { it.copy(listMode = if (it.listMode == ListViewMode.LIST) ListViewMode.COLLAGE else ListViewMode.LIST) }
        val snapshot = _ui.value.listMode.name
        viewModelScope.launch { repo.fs.setPref(PREF_LIST_MODE, snapshot) }
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
                    IllegalStateException("repo.create returned null — likely no storage permission " +
                        "or createFile failed (folder=${repo.fs.getFolderPath()})"))
                return@launch
            }
            val (path, parent) = created
            // Remember which note we just created. When its editor closes we decide whether
            // the list should scroll to the top — only if the note actually lands at the top
            // (see closeEditor). Otherwise the list stays wherever the user had scrolled.
            pendingNewNotePath = path
            _ui.update { it.copy(folderMenuFor = null) }
            openNoteRaw(path, parent, "", fileNameFor(path))
            refreshTree()
        }
    }

    private fun fileNameFor(path: String): String =
        File(path).name

    /** The UI has scrolled the list to the top in response to [scrollListToTop]; clear it. */
    fun consumeScrollToTop() { _ui.update { it.copy(scrollListToTop = false) } }

    /**
     * True when [notePath] is a top-level note that sorts first — i.e. it renders at the very
     * top of the list. Notes nested in a subfolder, or that don't sort first, are not "at the
     * top" and must not trigger a scroll. Mirrors the list's row-building order.
     */
    internal fun isNoteAtTopOfList(
        notePath: String,
        notes: List<Note>,
        folders: List<com.marsglorious.blacknote.data.FolderInfo>,
        sortMode: SortMode,
        pinned: Set<String>,
    ): Boolean {
        val knownFolderPaths = folders.map { it.path }.toSet()
        val note = notes.firstOrNull { it.path == notePath } ?: return false
        // Nested note → the folder above it occupies the top, so it isn't at the top.
        if (note.parent in knownFolderPaths) return false
        val topLevelNotes = notes.filter { it.parent !in knownFolderPaths }
        val first = topLevelNotes.sortedWith(noteComparator(sortMode, pinned)).firstOrNull()
        return first?.path == notePath
    }

    fun openNote(note: Note) {
        // Stale database entry from before the file-access migration — these paths are
        // dead and cannot be read or written. Trigger a refresh so the list rebuilds
        // from the real file system and the entry disappears.
        if (note.path.startsWith("content://")) { refreshTree(); return }
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
                delay(80.milliseconds)
                body = repo.read(note.path)
            }
            if (body == null) {
                android.util.Log.w("BlackNote.AppViewModel",
                    "openNote: read returned null for ${note.path}")
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
                        IllegalStateException("file exists but read kept failing for ${refreshed.path}"))
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

    /** True for the auto-generated default names ("Untitled", "Untitled 2", …). */
    private fun isDefaultUntitled(title: String): Boolean =
        Regex("""^Untitled( \d+)?$""").matches(title)

    internal fun openNoteRaw(path: String, parent: String, raw: String, fileName: String = "") {
        // Title is the on-disk file name (minus .md). Body is whatever's in the file —
        // no `# Heading` stripping anymore, so the body field always shows exactly what
        // the user typed. Saving the title field renames the file.
        // A note that still carries its default "Untitled" / "Untitled N" name opens with an
        // empty title field (the editor shows a faint "Title" hint) so the user can type
        // straight away instead of backspacing the placeholder out. The listing still shows
        // "Untitled" for these files. On close, an empty title leaves the file name as-is.
        val fileTitle = com.marsglorious.blacknote.data.titleFromFileName(fileName)
        val titleLine = if (isDefaultUntitled(fileTitle)) "" else fileTitle
        val titleTfv = TextFieldValue(titleLine, TextRange(titleLine.length))
        val bodyTfv = TextFieldValue(raw, TextRange(0))
        openedNoteBody = raw
        openedNoteTitle = titleLine
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
        // Detect real edits up front. Opening a note and closing it unchanged must not write
        // the file (that would bump its modified date and reorder the list) nor rename it.
        val bodyChanged = bodyText != openedNoteBody
        val titleChanged = titleText != openedNoteTitle
        val isNewNote = path == pendingNewNotePath
        viewModelScope.launch {
            if (path != null && !path.startsWith("content://")) {
                if (!bodyChanged && !titleChanged && !isNewNote) {
                    // Existing note opened without edits — leave the file and its timestamps
                    // completely alone (no write, no reorder). A brand-new note still falls
                    // through so it gets inserted into the list and can scroll into view.
                    return@launch
                }
                val wrote = if (bodyChanged) {
                    runCatching { repo.write(path, parent, bodyText) }
                        .onFailure { CrashReporter.report(app, "closeEditor.write.throw", it) }
                        .getOrDefault(false)
                } else true
                if (bodyChanged && !wrote) {
                    CrashReporter.report(app, "closeEditor.save",
                        IllegalStateException("write returned false for $path bodyLen=${bodyText.length}"))
                }
                val renamedUri: String? = if (wrote && titleChanged && titleText.isNotBlank()) {
                    // Blank title → leave the file name as-is (default "Untitled" notes open
                    // with an empty field; closing without typing must not rename the file).
                    runCatching { repo.renameToMatchTitle(path, parent, titleText) }
                        .onFailure { CrashReporter.report(app, "closeEditor.rename.throw", it) }
                        .getOrNull()
                } else null
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
                    val original = _ui.value.tree.notes.firstOrNull { it.path == path || it.path == finalUri }
                    val fileName = fileNameFor(finalUri).ifBlank { "Untitled.md" }
                    // Only a real content write advances the modified date; a rename-only close
                    // keeps the file's existing mtime (renaming doesn't touch it).
                    val mtime = if (bodyChanged) now else (original?.modifiedMillis ?: now)
                    // Re-derive tags/label from the persisted text so hashtags the user just
                    // typed appear in the list row immediately. Reusing original?.tags kept the
                    // stale set until the next restart re-read them from the index cache.
                    val meta = runCatching {
                        com.marsglorious.blacknote.data.extractMeta(finalUri, parent, fileName, persisted, mtime)
                    }.getOrNull()
                    val justSaved = Note(
                        path = finalUri, parent = parent,
                        title = com.marsglorious.blacknote.data.titleFromFileName(fileName),
                        preview = persisted.take(200),
                        modifiedMillis = mtime,
                        createdMillis = original?.createdMillis ?: now,
                        tags = meta?.tags ?: original?.tags ?: emptyList(),
                        label = meta?.label ?: original?.label,
                    )
                    if (finalUri != path) pendingRemovals.add(path)
                    if (isNewNote) pendingNewNotePath = null
                    _ui.update { st ->
                        val rest = st.tree.notes.filter { it.path != path && it.path != finalUri }
                        val merged = (listOf(justSaved) + rest).sortedWith(stableDisplayDesc)
                        // Only a brand-new note that actually lands at the top of the list
                        // makes the list scroll up; every other edit leaves scroll untouched.
                        val toTop = isNewNote &&
                            isNoteAtTopOfList(finalUri, merged, st.tree.folders, st.sortMode, st.pinned)
                        st.copy(
                            tree = st.tree.copy(notes = merged),
                            visibleNotes = orderedVisible(merged, st, st.tree.folders),
                            scrollListToTop = st.scrollListToTop || toTop,
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
        val next = continueList(b) ?: b
        history.record(EditorSnapshot(_ui.value.editingTitle, next))
        _ui.update { it.copy(editingBody = next, canUndo = history.canUndo, canRedo = history.canRedo) }
        scheduleSave()
    }

    /**
     * When the user presses Enter at the end of a list item, automatically start
     * the next item. On an empty item, pressing Enter exits the list instead.
     * Returns a modified TextFieldValue, or null if no list continuation applies.
     */
    private fun continueList(new: TextFieldValue): TextFieldValue? {
        if (!new.selection.collapsed) return null
        val cursor = new.selection.start
        if (cursor == 0 || new.text.getOrNull(cursor - 1) != '\n') return null
        // Only act on single-character insertions (the newline itself).
        val old = _ui.value.editingBody
        if (new.text.length - old.text.length != 1) return null

        // Find the line that was just ended.
        val lineEnd = cursor - 1
        val lineStart = (new.text.lastIndexOf('\n', lineEnd - 1) + 1).coerceAtLeast(0)
        val prevLine = new.text.substring(lineStart, lineEnd)

        // Bullet list: "- content"
        if (prevLine.startsWith("- ")) {
            val content = prevLine.removePrefix("- ")
            return if (content.isBlank()) {
                // Empty item → exit list: remove "- " and the newline we just added.
                val text = new.text.substring(0, lineStart) + new.text.substring(cursor)
                TextFieldValue(text, androidx.compose.ui.text.TextRange(lineStart))
            } else {
                val text = new.text.substring(0, cursor) + "- " + new.text.substring(cursor)
                TextFieldValue(text, androidx.compose.ui.text.TextRange(cursor + 2))
            }
        }

        // Ordered list: "N. content"
        var k = 0
        while (k < prevLine.length && prevLine[k].isDigit()) k++
        if (k > 0 && prevLine.startsWith(". ", k)) {
            val num = prevLine.substring(0, k).toIntOrNull() ?: return null
            val content = prevLine.substring(k + 2)
            return if (content.isBlank()) {
                val text = new.text.substring(0, lineStart) + new.text.substring(cursor)
                TextFieldValue(text, androidx.compose.ui.text.TextRange(lineStart))
            } else {
                val marker = "${num + 1}. "
                val text = new.text.substring(0, cursor) + marker + new.text.substring(cursor)
                TextFieldValue(text, androidx.compose.ui.text.TextRange(cursor + marker.length))
            }
        }

        return null
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

    /** Run the on-device self-test harness and show the results dialog. */
    fun runSelfTests() {
        viewModelScope.launch {
            val results = withContext(Dispatchers.Default) {
                com.marsglorious.blacknote.selftest.SelfTest.runAll(app)
            }
            _ui.update { it.copy(selfTestResults = results) }
        }
    }
    fun dismissSelfTests() { _ui.update { it.copy(selfTestResults = null) } }

    /** Open the hashtag picker: rank suggestions from tags used across the whole tree. */
    fun openHashtagPicker() {
        val s = _ui.value
        val body = s.editingBody.text
        val title = s.editingTitle.text
        val existing = Regex("""(?<![a-zA-Z0-9_])#([a-zA-Z][a-zA-Z0-9_\-/]*)""")
            .findAll("$body $title")
            .map { it.groupValues[1].lowercase() }
            .toHashSet()
        val suggestions = rankHashtagSuggestions(s.tree.notes, body, title, existing)
        _ui.update { it.copy(hashtagPickerOpen = true, hashtagSuggestions = suggestions) }
    }

    /**
     * Rank hashtags collected from every note in the tree by relevance to the current note.
     * Sourced from the in-memory tree — the same tags the list renders — rather than the DB
     * index, so a tag added moments ago is suggestable immediately and the picker can't come
     * up empty while the list plainly shows tags.
     *
     * The importance score for each tag blends:
     *  - **Mentions** (×45): how often the tag's word(s) already appear in this note's text.
     *  - **Vocabulary overlap** (×8/word): how many of this note's words also appear in the
     *    notes already carrying the tag — "is this note about the same things?".
     *  - **Length similarity** (up to 40): how close this note's length is to the average
     *    length of notes with the tag — a rough proxy for matching style/format.
     *  - **Popularity** (×10/note) and **recency** (up to 100, decaying over 30 days).
     */
    private fun rankHashtagSuggestions(
        notes: List<Note>,
        body: String,
        title: String,
        excludeTags: Set<String>,
        limit: Int = 20,
    ): List<HashtagSuggestion> {
        // Aggregate, per tag, the info needed to score: usage count, recency, the combined
        // vocabulary of its notes, and their average length.
        class TagAgg(var display: String) {
            var count = 0
            var lastUsedMillis = 0L
            val vocab = HashSet<String>()
            var totalLen = 0L
        }
        val agg = HashMap<String, TagAgg>()
        for (n in notes) {
            if (n.tags.isEmpty()) continue
            val noteWords = wordsOf(n.title + " " + n.preview)
            val noteLen = (n.title.length + n.preview.length).toLong()
            for (raw in n.tags) {
                if (raw.isBlank()) continue
                val a = agg.getOrPut(raw.lowercase()) { TagAgg(raw) }
                a.count++
                if (n.modifiedMillis > a.lastUsedMillis) {
                    a.lastUsedMillis = n.modifiedMillis
                    a.display = raw // keep the casing of the most recently used note
                }
                a.vocab.addAll(noteWords)
                a.totalLen += noteLen
            }
        }
        if (agg.isEmpty()) return emptyList()

        val currentText = "$body $title"
        val currentWords = wordsOf(currentText)
        val currentWordSet = currentWords.toHashSet()
        val currentLen = currentText.length.toLong()
        val now = System.currentTimeMillis()

        return agg.entries
            .filter { it.key !in excludeTags }
            .map { (key, a) ->
                val tagWords = key.split(Regex("[^a-z0-9_/-]+")).filter { it.length >= 3 }
                // Mentions: occurrences of the tag (or its sub-words) in the current note.
                val mentions = currentWords.count { it == key } +
                    tagWords.sumOf { tw -> currentWords.count { it == tw } }
                val mentionScore = mentions.toLong() * 45
                // Overlap: current note's words that also appear in the tag group's vocabulary.
                val overlap = if (currentWordSet.isEmpty()) 0 else currentWordSet.count { it in a.vocab }
                val overlapScore = overlap.toLong() * 8
                // Length similarity to the tag group's average note length.
                val avgLen = if (a.count > 0) a.totalLen / a.count else 0L
                val lengthScore = if (avgLen == 0L || currentLen == 0L) 0L else {
                    val diff = kotlin.math.abs(currentLen - avgLen).toDouble() / maxOf(currentLen, avgLen)
                    (40 * (1.0 - diff.coerceIn(0.0, 1.0))).toLong()
                }
                val groupScore = a.count.toLong() * 10
                val ageDays = (now - a.lastUsedMillis) / 86_400_000L
                val recencyScore = (100 * (1.0 - (ageDays / 30.0).coerceIn(0.0, 1.0))).toLong()
                val total = mentionScore + overlapScore + lengthScore + groupScore + recencyScore
                HashtagSuggestion(tag = a.display, noteCount = a.count, score = total.toInt())
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /** Split text into lowercase word tokens (≥3 chars) for similarity/mention analysis. */
    private fun wordsOf(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9_/-]+")).filter { it.length >= 3 }

    fun closeHashtagPicker() {
        _ui.update { it.copy(hashtagPickerOpen = false) }
    }

    /**
     * Append [tag] as `#tag ` at the end of the note body. Tags go to the bottom rather than
     * at the cursor because tapping a picker chip pulls focus off the editor, leaving a stale
     * (often position-0) caret — inserting there dropped the tag at the top of the note.
     * Consecutive tags stay grouped on one line; otherwise the tag starts a fresh line.
     */
    fun insertHashtag(tag: String) {
        val cur = _ui.value.editingBody
        val trimmed = cur.text.trimEnd()
        val newText = when {
            trimmed.isEmpty() -> "#$tag "
            trimmed.substringAfterLast('\n').trimStart().startsWith("#") -> "$trimmed #$tag "
            else -> "$trimmed\n\n#$tag "
        }
        val next = androidx.compose.ui.text.input.TextFieldValue(
            newText, androidx.compose.ui.text.TextRange(newText.length)
        )
        history.record(EditorSnapshot(_ui.value.editingTitle, next))
        _ui.update { it.copy(
            editingBody = next,
            canUndo = history.canUndo, canRedo = history.canRedo,
            hashtagPickerOpen = false,
        ) }
        scheduleSave()
    }

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
                val rootPath = runCatching { repo.fs.getFolderPath() }.getOrNull()
                if (rootPath != null) {
                    _ui.update { s ->
                        val newFolder = FolderInfo(
                            path = createdUri, parent = rootPath, name = safe, depth = 0,
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
        const val PREF_SHOW_LOCATION = "ui_show_file_location"

        fun factory(app: App) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(app, app.repository) as T
        }
    }
}
