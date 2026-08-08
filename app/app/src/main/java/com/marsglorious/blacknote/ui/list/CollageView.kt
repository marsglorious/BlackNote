package com.marsglorious.blacknote.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoveToInbox
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marsglorious.blacknote.data.Note
import com.marsglorious.blacknote.ui.theme.MdColors
import com.marsglorious.blacknote.viewmodel.AppViewModel
import com.marsglorious.blacknote.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollageGrid(state: UiState, viewModel: AppViewModel) {
    val notes = state.visibleNotes
    val gridState = androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState(
        initialFirstVisibleItemIndex = state.collageScrollIndex,
        initialFirstVisibleItemScrollOffset = state.collageScrollOffset
    )
    // Reset to top only on actual query change, not on first composition (which would wipe
    // the restored scroll position when returning from the editor).
    val initialQuery = androidx.compose.runtime.remember { state.query }
    androidx.compose.runtime.LaunchedEffect(state.query) {
        if (state.query != initialQuery) gridState.animateScrollToItem(0)
    }
    // A newly-created note that landed at the top asks the grid to scroll up to reveal it.
    androidx.compose.runtime.LaunchedEffect(state.scrollListToTop) {
        if (state.scrollListToTop) {
            gridState.scrollToItem(0)
            viewModel.consumeScrollToTop()
        }
    }
    // Continuously save current scroll position
    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.saveCollageScrollPosition(index, offset)
            }
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalStaggeredGrid(
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(end = 14.dp),
            columns = StaggeredGridCells.Fixed(2),
            verticalItemSpacing = 8.dp,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
        ) {
            val knownFolderPaths = state.tree.folders.map { it.path }.toSet()
            items(notes, key = { it.path }) { note ->
                val hl = state.searchHighlights[note.path]
                CollageTile(
                    note = note,
                    pinned = note.path in state.pinned,
                    // Colour-code by folder so membership is visible in the flat grid too.
                    accentColor = if (note.parent in knownFolderPaths) MdColors.folderColor(note.parent) else null,
                    titleHighlights = hl?.first.orEmpty(),
                    previewHighlights = hl?.second.orEmpty(),
                    onClick = { viewModel.openNote(note) },
                    onLongClick = { viewModel.openNoteMenu(note.path) },
                    showMenu = state.noteMenuFor == note.path,
                    onDismissMenu = { viewModel.closeNoteMenu() },
                    onMove = { viewModel.startMove(note.path, note.parent) },
                    onCopy = { viewModel.startCopy(note.path) },
                    onDelete = { viewModel.deleteToTrash(note.path, note.parent) },
                    onPin = { viewModel.togglePin(note.path) },
                    onShare = { viewModel.shareNote(note) },
                )
            }
        }
        DraggableScrollbar(
            state = gridState,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CollageTile(
    note: Note,
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
) {
    val titleText = note.title.ifBlank { "Untitled" }
    val styledTitle = androidx.compose.runtime.remember(titleText, titleHighlights) { highlight(titleText, titleHighlights) }
    val styledPreview = androidx.compose.runtime.remember(note.preview, previewHighlights) { highlight(note.preview, previewHighlights) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(14.dp))
                .background(MdColors.Surface)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            if (accentColor != null) {
                Box(Modifier.fillMaxHeight().width(4.dp).background(accentColor))
            }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pinned) {
                    androidx.compose.material3.Icon(
                        Icons.Outlined.PushPin, contentDescription = "Pinned",
                        tint = MdColors.Accent,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    styledTitle,
                    style = MaterialTheme.typography.titleMedium.copy(color = MdColors.OnSurface),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (note.preview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    styledPreview,
                    style = MaterialTheme.typography.bodyMedium.copy(color = MdColors.OnSurfaceDim),
                    maxLines = if (note.preview.length > 80) 8 else 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDate(note.modifiedMillis), fontSize = 11.sp, color = MdColors.OnSurfaceFaint)
                note.label?.let { lbl ->
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(MdColors.LabelChipBg)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(lbl, fontSize = 10.sp, color = MdColors.LabelChipFg, fontWeight = FontWeight.Medium)
                    }
                }
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

private val dateFmt = SimpleDateFormat("d/M/yy", Locale.getDefault())
private fun formatDate(ms: Long): String = dateFmt.format(Date(ms))
