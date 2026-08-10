package com.marsglorious.blacknote.ui.list

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import com.marsglorious.blacknote.data.FolderInfo
import com.marsglorious.blacknote.data.Note
import com.marsglorious.blacknote.data.safUriToFilePath
import com.marsglorious.blacknote.ui.theme.MdColors
import com.marsglorious.blacknote.viewmodel.AppViewModel
import com.marsglorious.blacknote.viewmodel.ListViewMode
import com.marsglorious.blacknote.viewmodel.SortMode
import com.marsglorious.blacknote.viewmodel.UiState
import com.marsglorious.blacknote.viewmodel.noteComparator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NoteListScreen(state: UiState, viewModel: AppViewModel, onGrantPermission: () -> Unit = {}) {
    val context = LocalContext.current
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val path = safUriToFilePath(uri)
            if (path != null) {
                java.io.File(path).mkdirs()
                viewModel.onFolderPicked(path)
            } else {
                android.widget.Toast.makeText(
                    context,
                    "Please choose a folder on your phone's internal storage",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MdColors.Background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            // Until bootstrap has resolved folder status, render nothing (just the themed
            // background) rather than flashing the "Pick a folder" screen for a frame.
            if (!state.folderKnown) return@Column
            if (!state.hasFolder) {
                EmptyFolderState(onPick = { pickFolder.launch(null) })
                return@Column
            }
            if (state.needsManagePermission) {
                ManagePermissionScreen(onGrant = onGrantPermission)
                return@Column
            }
            SearchBarWithMenu(
                query = state.query,
                onQuery = { viewModel.setQuery(it) },
                listMode = state.listMode,
                sortMode = state.sortMode,
                onSortMode = { viewModel.setSortMode(it) },
                onToggleListMode = { viewModel.toggleListMode() },
                onOpenTrash = { viewModel.openTrash() },
                onOpenSettings = { viewModel.openSettings() },
                onNewFolder = { viewModel.openNewFolderDialog() },
                onRefresh = { viewModel.refreshTree() },
                onRunTests = { viewModel.runSelfTests() },
            )
            Spacer(Modifier.height(8.dp))
            val nothing = state.tree.notes.isEmpty() && state.tree.folders.isEmpty()
            when {
                // Only after the first load completes — never flash "No notes yet" mid-load.
                nothing && !state.isRefreshing && state.initialLoadComplete -> EmptyNotesPlaceholder()
                state.listMode == ListViewMode.COLLAGE -> CollageGrid(state, viewModel)
                else -> ListView(state, viewModel)
            }
        }

        if (state.isRefreshing && state.tree.notes.isEmpty() && state.tree.folders.isEmpty()) {
            CircularProgressIndicator(
                color = MdColors.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 80.dp, end = 24.dp)
                    .size(24.dp),
            )
        }
        FloatingActionButton(
            onClick = { viewModel.newNote() },
            containerColor = MdColors.Accent,
            contentColor = MdColors.Background,
            shape = RoundedCornerShape(18.dp),
            elevation = FloatingActionButtonDefaults.elevation(0.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Outlined.Add, contentDescription = "New note") }

        state.selfTestResults?.let { results ->
            SelfTestDialog(results = results, onDismiss = { viewModel.dismissSelfTests() })
        }
    }
}

@Composable
private fun SelfTestDialog(
    results: List<com.marsglorious.blacknote.selftest.SelfTestResult>,
    onDismiss: () -> Unit,
) {
    val passed = results.count { it.passed }
    val allPassed = passed == results.size
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = MdColors.Accent) }
        },
        title = {
            Text(
                "Self-tests: $passed/${results.size} passed",
                color = if (allPassed) MdColors.Accent else MdColors.OnSurface,
            )
        },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                items(results) { r ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (r.passed) Icons.Outlined.Check else Icons.Outlined.Close,
                                contentDescription = if (r.passed) "passed" else "failed",
                                tint = if (r.passed) MdColors.Accent else MdColors.DangerFg,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(r.name, color = MdColors.OnSurface, style = MaterialTheme.typography.bodyMedium)
                        }
                        r.error?.let { err ->
                            Text(
                                err,
                                color = MdColors.DangerFg,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 26.dp, top = 2.dp),
                            )
                        }
                    }
                }
            }
        },
        containerColor = MdColors.SurfaceHi,
    )
}

@Composable
private fun ListView(state: UiState, viewModel: AppViewModel) {
    val rows = remember(state.tree, state.expandedFolders, state.visibleNotes, state.query, state.sortMode, state.pinned) {
        buildRows(state)
    }
    // Stable ID per note: oldest = 1, newest = N. Based on createdMillis ascending across
    // the full tree (not just visible) so IDs don't shift when folders collapse/expand.
    val noteIds = remember(state.tree.notes) {
        state.tree.notes
            .sortedBy { it.displayMillis }
            .mapIndexed { i, n -> n.path to (i + 1) }
            .toMap()
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = state.listScrollIndex,
        initialFirstVisibleItemScrollOffset = state.listScrollOffset
    )
    // Reset to top only when the search query actually changes — NOT on initial composition,
    // otherwise returning from the editor wipes the restored scroll position.
    val initialQuery = remember { state.query }
    LaunchedEffect(state.query) {
        if (state.query != initialQuery) listState.animateScrollToItem(0)
    }
    // A newly-created note that landed at the top asks the list to scroll up to reveal it.
    // One-shot: consume the flag so ordinary edits don't move the list.
    LaunchedEffect(state.scrollListToTop) {
        if (state.scrollListToTop) {
            listState.scrollToItem(0)
            viewModel.consumeScrollToTop()
        }
    }
    // Continuously save current scroll position so back returns to exact location
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.saveListScrollPosition(index, offset)
            }
    }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(end = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
    ) {
        items(rows, key = { it.key }) { row ->
            // animateItem() only runs on the items currently in the viewport, so it
            // stays smooth regardless of total note count.
            val itemAnim = Modifier.animateItem(
                fadeInSpec = tween(durationMillis = 160),
                fadeOutSpec = tween(durationMillis = 100),
            )
            when (row) {
                is Row.FolderRow -> FolderCard(
                    folder = row.folder,
                    expanded = row.expanded,
                    noteCount = row.noteCount,
                    color = MdColors.folderColor(row.folder.path),
                    onClick = { viewModel.toggleFolder(row.folder.path) },
                    onLongClick = { viewModel.openFolderMenu(row.folder.path) },
                    showMenu = state.folderMenuFor == row.folder.path,
                    onDismissMenu = { viewModel.closeFolderMenu() },
                    onNewNoteHere = { viewModel.newNote(parentFolder = row.folder.path) },
                    modifier = itemAnim,
                )
                is Row.NoteRow -> {
                    val hl = state.searchHighlights[row.note.path]
                    NoteCard(
                        note = row.note,
                        indent = row.indent,
                        idNumber = noteIds[row.note.path] ?: 0,
                        locationLabel = absolutePathFromUri(row.note.path),
                        showLocation = state.showFileLocation,
                        pinned = row.note.path in state.pinned,
                        // Notes nested in a folder carry that folder's colour; top-level notes don't.
                        accentColor = if (row.indent > 0) MdColors.folderColor(row.note.parent) else null,
                        titleHighlights = hl?.first.orEmpty(),
                        previewHighlights = hl?.second.orEmpty(),
                        onClick = { viewModel.openNote(row.note) },
                        onLongClick = { viewModel.openNoteMenu(row.note.path) },
                        showMenu = state.noteMenuFor == row.note.path,
                        onDismissMenu = { viewModel.closeNoteMenu() },
                        onMove = { viewModel.startMove(row.note.path, row.note.parent) },
                        onCopy = { viewModel.startCopy(row.note.path) },
                        onDelete = { viewModel.deleteToTrash(row.note.path, row.note.parent) },
                        onPin = { viewModel.togglePin(row.note.path) },
                        onShare = { viewModel.shareNote(row.note) },
                        onTagClick = { tag -> viewModel.setQuery(tag) },
                        modifier = itemAnim,
                    )
                }
            }
        }
    }
        DraggableScrollbar(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun SearchBarWithMenu(
    query: String,
    onQuery: (String) -> Unit,
    listMode: ListViewMode,
    sortMode: SortMode,
    onSortMode: (SortMode) -> Unit,
    onToggleListMode: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewFolder: () -> Unit,
    onRefresh: () -> Unit,
    onRunTests: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MdColors.SurfaceHi)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Menu, "Menu", tint = MdColors.OnSurface)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.background(MdColors.SurfaceHi),
            ) {
                DropdownMenuItem(
                    text = { Text("Refresh", color = MdColors.OnSurface) },
                    leadingIcon = { Icon(Icons.Outlined.Refresh, null, tint = MdColors.OnSurfaceDim) },
                    onClick = { menuOpen = false; onRefresh() },
                )
                DropdownMenuItem(
                    text = { Text("Sort by…", color = MdColors.OnSurface) },
                    leadingIcon = { Icon(Icons.Outlined.Sort, null, tint = MdColors.OnSurfaceDim) },
                    onClick = { menuOpen = false; sortMenuOpen = true },
                )
                DropdownMenuItem(
                    text = { Text("New folder", color = MdColors.OnSurface) },
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null, tint = MdColors.OnSurfaceDim) },
                    onClick = { menuOpen = false; onNewFolder() },
                )
                DropdownMenuItem(
                    text = { Text("Trash", color = MdColors.OnSurface) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MdColors.OnSurfaceDim) },
                    onClick = { menuOpen = false; onOpenTrash() },
                )
                DropdownMenuItem(
                    text = { Text("Settings", color = MdColors.OnSurface) },
                    leadingIcon = { Icon(Icons.Outlined.Settings, null, tint = MdColors.OnSurfaceDim) },
                    onClick = { menuOpen = false; onOpenSettings() },
                )
                DropdownMenuItem(
                    text = { Text("Run self-tests", color = MdColors.OnSurface) },
                    leadingIcon = { Icon(Icons.Outlined.Science, null, tint = MdColors.OnSurfaceDim) },
                    onClick = { menuOpen = false; onRunTests() },
                )
            }
            DropdownMenu(
                expanded = sortMenuOpen,
                onDismissRequest = { sortMenuOpen = false },
                modifier = Modifier.background(MdColors.SurfaceHi),
            ) {
                for (mode in SortMode.entries) {
                    DropdownMenuItem(
                        text = { Text(mode.label, color = MdColors.OnSurface) },
                        leadingIcon = {
                            if (mode == sortMode) {
                                Icon(Icons.Outlined.Check, null, tint = MdColors.Accent)
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                        },
                        onClick = { sortMenuOpen = false; onSortMode(mode) },
                    )
                }
            }
        }
        BasicTextFieldCompat(
            value = query,
            onValueChange = onQuery,
            placeholder = "Search notes",
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp).testTag("search_field"),
        )
        if (query.isEmpty()) {
            IconButton(onClick = onToggleListMode, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (listMode == ListViewMode.LIST) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda,
                    contentDescription = if (listMode == ListViewMode.LIST) "Collage view" else "List view",
                    tint = MdColors.OnSurface,
                )
            }
        } else {
            IconButton(
                onClick = { onQuery("") },
                modifier = Modifier.size(40.dp).testTag("clear_search"),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Clear search", tint = MdColors.OnSurface)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    folder: FolderInfo,
    expanded: Boolean,
    noteCount: Int,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    showMenu: Boolean = false,
    onDismissMenu: () -> Unit = {},
    onNewNoteHere: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val chevronAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "chevron",
    )
    Box(modifier.padding(start = (folder.depth * 4).dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(14.dp))
                .background(if (expanded) MdColors.FolderBgExpanded else MdColors.FolderBg)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            // The colour stripe + tinted icon are what tie this folder to its notes below.
            Box(Modifier.fillMaxHeight().width(4.dp).background(color))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = color,
                    modifier = Modifier.rotate(chevronAngle),
                )
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Outlined.Folder, null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(folder.name, color = MdColors.FolderFg, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (noteCount > 0) "$noteCount" else "—",
                    color = MdColors.FolderFg.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = onDismissMenu,
            modifier = Modifier.background(MdColors.SurfaceHi),
        ) {
            DropdownMenuItem(text = { Text("New note here", color = MdColors.OnSurface) },
                leadingIcon = { Icon(Icons.Outlined.NoteAdd, null, tint = MdColors.OnSurfaceDim) },
                onClick = onNewNoteHere)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteCard(
    note: Note,
    indent: Int,
    idNumber: Int = 0,
    locationLabel: String = "/",
    showLocation: Boolean = true,
    pinned: Boolean = false,
    accentColor: androidx.compose.ui.graphics.Color? = null,
    titleHighlights: List<Int> = emptyList(),
    previewHighlights: List<Int> = emptyList(),
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showMenu: Boolean,
    onDismissMenu: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit = {},
    onShare: () -> Unit = {},
    onTagClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val titleText = note.title.ifBlank { "Untitled" }
    val styledTitle = remember(titleText, titleHighlights) { highlight(titleText, titleHighlights) }
    val styledPreview = remember(note.preview, previewHighlights) { highlight(note.preview, previewHighlights) }
    Box(modifier.padding(start = (indent * 2).dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .testTag("note_card_${note.title.ifBlank { "Untitled" }}")
                .clip(RoundedCornerShape(16.dp))
                .background(MdColors.Surface)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            if (accentColor != null) {
                Box(Modifier.fillMaxHeight().width(4.dp).background(accentColor))
            }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Title shares its row with the date and id: the title takes whatever width it
            // needs (ellipsised) and the metadata sits to its right, saving the vertical space
            // a separate metadata row used to cost.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    Icon(
                        Icons.Outlined.PushPin, contentDescription = "Pinned",
                        tint = MdColors.Accent, modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    styledTitle,
                    style = MaterialTheme.typography.titleMedium.copy(color = MdColors.OnSurface),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                note.label?.let { lbl ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MdColors.LabelChipBg)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(lbl, fontSize = 11.sp, color = MdColors.LabelChipFg, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    formatDate(note.displayMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MdColors.MetaText,
                )
                if (idNumber > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$idNumber",
                        fontSize = 11.sp,
                        color = MdColors.Cyan,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (note.preview.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    styledPreview,
                    style = MaterialTheme.typography.bodyMedium.copy(color = MdColors.OnSurfaceDim),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            val folderName = if (indent > 0)
                note.parent.split('/').lastOrNull { it.isNotBlank() }?.lowercase()
            else null
            if (note.tags.isNotEmpty() || folderName != null) {
                Spacer(Modifier.height(6.dp))
                TagRow(
                    tags = note.tags,
                    virtualFolderTag = folderName,
                    folderColor = accentColor,
                    onTagClick = onTagClick,
                )
            }
            if (showLocation) {
                Spacer(Modifier.height(4.dp))
                Text(
                    locationLabel,
                    fontSize = 10.sp,
                    color = MdColors.MetaText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = onDismissMenu,
            modifier = Modifier.background(MdColors.SurfaceHi),
        ) {
            DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin", color = MdColors.OnSurface) },
                leadingIcon = { Icon(Icons.Outlined.PushPin, null, tint = MdColors.OnSurfaceDim) },
                onClick = onPin)
            DropdownMenuItem(text = { Text("Share", color = MdColors.OnSurface) },
                leadingIcon = { Icon(Icons.Outlined.Share, null, tint = MdColors.OnSurfaceDim) },
                onClick = onShare)
            DropdownMenuItem(text = { Text("Move to…", color = MdColors.OnSurface) },
                leadingIcon = { Icon(Icons.Outlined.MoveToInbox, null, tint = MdColors.OnSurfaceDim) },
                onClick = onMove)
            DropdownMenuItem(text = { Text("Copy to…", color = MdColors.OnSurface) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, tint = MdColors.OnSurfaceDim) },
                onClick = onCopy)
            DropdownMenuItem(text = { Text("Delete", color = MdColors.OnSurface) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MdColors.OnSurfaceDim) },
                onClick = onDelete)
        }
    }
}

@Composable
private fun ManagePermissionScreen(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Settings, null, tint = MdColors.OnSurfaceDim, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text("Files access required",
            style = MaterialTheme.typography.titleMedium, color = MdColors.OnSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            "Grant \"Allow management of all files\" in Settings so BlackNote can read your .md files.",
            style = MaterialTheme.typography.bodyMedium, color = MdColors.OnSurfaceDim,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onGrant,
            colors = ButtonDefaults.buttonColors(containerColor = MdColors.Accent, contentColor = MdColors.Background),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Open Settings") }
    }
}

@Composable
private fun EmptyFolderState(onPick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.CreateNewFolder, null, tint = MdColors.OnSurfaceDim, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text("Pick a folder for your .md notes",
            style = MaterialTheme.typography.titleMedium, color = MdColors.OnSurface)
        Spacer(Modifier.height(4.dp))
        Text("BlackNote will read and write Markdown files there.",
            style = MaterialTheme.typography.bodyMedium, color = MdColors.OnSurfaceDim)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onPick,
            colors = ButtonDefaults.buttonColors(containerColor = MdColors.Accent, contentColor = MdColors.Background),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Choose folder") }
    }
}

@Composable
private fun EmptyNotesPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No notes yet. Tap + to create one.",
            color = MdColors.OnSurfaceDim, style = MaterialTheme.typography.bodyMedium)
    }
}

private val dateFmt = SimpleDateFormat("d/M/yy, h:mm a", Locale.getDefault())
private fun formatDate(ms: Long): String = dateFmt.format(Date(ms)).lowercase()

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TagRow(
    tags: List<String>,
    virtualFolderTag: String? = null,
    folderColor: androidx.compose.ui.graphics.Color? = null,
    onTagClick: (String) -> Unit = {},
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Virtual folder hashtag shown first so folder membership reads by colour.
        if (virtualFolderTag != null && folderColor != null) {
            HashtagChip(virtualFolderTag, folderColor, fontSize = 10.sp) { onTagClick(virtualFolderTag) }
        }
        // Show up to 4 tags; "+N" chip if more
        val visible = tags.take(4)
        for (tag in visible) {
            HashtagChip(tag, MdColors.hashtagColor(tag), fontSize = 10.sp) { onTagClick(tag) }
        }
        if (tags.size > visible.size) {
            val more = tags.size - visible.size
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(MdColors.SurfaceHi)
                    .border(1.dp, MdColors.OnSurfaceFaint.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
                    .padding(horizontal = 3.dp, vertical = 0.dp)
            ) {
                Text("+$more", fontSize = 10.sp, color = MdColors.OnSurfaceDim, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * A bevelled hashtag pill: a top-to-bottom colour gradient with a matching outline gives it
 * a raised, glassy look rather than a flat tint. [label] is rendered as `#label`.
 */
@Composable
private fun HashtagChip(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.52f), color.copy(alpha = 0.28f))
                )
            )
            .border(1.dp, color.copy(alpha = 0.55f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 0.dp)
    ) {
        Text("#$label", fontSize = fontSize, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** Wraps the given text in an AnnotatedString with a highlight span on each matched char index. */
internal fun highlight(text: String, positions: List<Int>): AnnotatedString {
    if (positions.isEmpty()) return AnnotatedString(text)
    val hl = SpanStyle(
        background = MdColors.LabelChipBg,
        color = MdColors.LabelChipFg,
        fontWeight = FontWeight.Bold,
    )
    return buildAnnotatedString {
        append(text)
        // Coalesce consecutive indices into runs so the background paints continuously.
        val sorted = positions.filter { it in text.indices }.sorted()
        if (sorted.isEmpty()) return@buildAnnotatedString
        var runStart = sorted[0]
        var runEnd = sorted[0]
        for (i in 1 until sorted.size) {
            if (sorted[i] == runEnd + 1) {
                runEnd = sorted[i]
            } else {
                addStyle(hl, runStart, runEnd + 1)
                runStart = sorted[i]; runEnd = sorted[i]
            }
        }
        addStyle(hl, runStart, runEnd + 1)
    }
}

/**
 * Extract a human-readable path from a note path string. For plain file paths
 * (the new default) this returns the path directly, trimming the storage root prefix
 * so the user sees a short relative path. Handles legacy SAF content:// URIs for
 * users migrating from old data.
 */
internal fun absolutePathFromUri(uriString: String): String {
    // Plain file path — extract a user-friendly relative portion.
    if (uriString.startsWith("/")) {
        // Strip common storage prefixes to show a shorter path.
        val storageRoot = "/storage/emulated/0/"
        val sdcardRoot = "/sdcard/"
        return when {
            uriString.startsWith(storageRoot) -> "/" + uriString.removePrefix(storageRoot).trimStart('/')
            uriString.startsWith(sdcardRoot) -> "/" + uriString.removePrefix(sdcardRoot).trimStart('/')
            else -> uriString
        }
    }
    // Legacy SAF URI fallback.
    val docMarker = "/document/"
    val treeMarker = "/tree/"
    val idStart = when {
        uriString.contains(docMarker) -> uriString.indexOf(docMarker) + docMarker.length
        uriString.contains(treeMarker) -> uriString.indexOf(treeMarker) + treeMarker.length
        else -> return "/"
    }
    val docId = android.net.Uri.decode(uriString.substring(idStart))
    val colon = docId.indexOf(':')
    val rel = if (colon >= 0) docId.substring(colon + 1) else docId
    return "/" + rel.trimStart('/')
}

private sealed class Row {
    abstract val key: String
    data class FolderRow(val folder: FolderInfo, val expanded: Boolean, val noteCount: Int) : Row() {
        override val key get() = "f:${folder.path}"
    }
    data class NoteRow(val note: Note, val indent: Int) : Row() {
        override val key get() = "n:${note.path}"
    }
}

private fun buildRows(state: UiState): List<Row> {
    val rows = mutableListOf<Row>()
    val notesByParent = state.tree.notes.groupBy { it.parent }
    val foldersByParent = state.tree.folders.groupBy { it.parent }
    val ordering = noteComparator(state.sortMode, state.pinned)

    fun countDescendantNotes(folderPath: String): Int {
        var total = notesByParent[folderPath]?.size ?: 0
        for (child in foldersByParent[folderPath].orEmpty()) total += countDescendantNotes(child.path)
        return total
    }

    val visibleNotePaths = state.visibleNotes.map { it.path }.toSet()
    if (state.query.isNotBlank()) {
        for (n in state.visibleNotes) rows += Row.NoteRow(n, indent = 0)
        return rows
    }

    fun walk(parentPath: String, depth: Int) {
        val folders = foldersByParent[parentPath].orEmpty().sortedBy { it.name.lowercase() }
        val notes = notesByParent[parentPath].orEmpty().sortedWith(ordering)
        for (f in folders) {
            val expanded = f.path in state.expandedFolders
            rows += Row.FolderRow(f, expanded, countDescendantNotes(f.path))
            if (expanded) walk(f.path, depth + 1)
        }
        for (n in notes) {
            if (n.path in visibleNotePaths) rows += Row.NoteRow(n, depth)
        }
    }

    val knownFolderPaths = state.tree.folders.map { it.path }.toSet()
    val rootParents = (state.tree.notes.map { it.parent } + state.tree.folders.map { it.parent })
        .filter { it !in knownFolderPaths }
        .distinct()
    for (rp in rootParents) walk(rp, 0)
    return rows
}
