package com.marsglorious.blacknote.ui.list

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.marsglorious.blacknote.data.Note
import com.marsglorious.blacknote.ui.theme.MdColors
import com.marsglorious.blacknote.viewmodel.AppViewModel
import com.marsglorious.blacknote.viewmodel.UiState

@Composable
fun TrashScreen(state: UiState, viewModel: AppViewModel) {
    // System back must return to the list, not exit the app.
    BackHandler { viewModel.backToList() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MdColors.Background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(56.dp)) {
            IconButton(onClick = { viewModel.backToList() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = MdColors.OnSurface)
            }
            Spacer(Modifier.width(4.dp))
            Text("Trash", style = MaterialTheme.typography.titleLarge, color = MdColors.OnSurface)
            Spacer(Modifier.weight(1f))
            if (state.trashNotes.isNotEmpty()) {
                TextButton(onClick = { viewModel.requestEmptyTrash() }) {
                    Icon(Icons.Outlined.DeleteSweep, null, tint = MdColors.OnSurfaceDim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Empty trash", color = MdColors.OnSurfaceDim, fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (state.trashNotes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Trash is empty.",
                    color = MdColors.OnSurfaceDim, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.trashNotes, key = { it.path }) { note ->
                    TrashedCard(
                        note = note,
                        onRestore = { viewModel.restoreFromTrash(note.path) },
                        onDelete = { viewModel.requestDeletePermanently(note.path) },
                    )
                }
            }
        }
    }

    state.confirmDeleteForever?.let { uri ->
        val title = state.trashNotes.firstOrNull { it.path == uri }?.title ?: "this note"
        ConfirmDialog(
            title = "Delete forever?",
            message = "“$title” will be permanently deleted. This can't be undone.",
            confirmLabel = "Delete forever",
            onConfirm = { viewModel.confirmDeletePermanently() },
            onDismiss = { viewModel.cancelDeletePermanently() },
        )
    }
    if (state.confirmEmptyTrash) {
        ConfirmDialog(
            title = "Empty trash?",
            message = "All ${state.trashNotes.size} notes in the trash will be permanently deleted. This can't be undone.",
            confirmLabel = "Empty trash",
            onConfirm = { viewModel.confirmEmptyTrash() },
            onDismiss = { viewModel.cancelEmptyTrash() },
        )
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MdColors.Surface)
                .padding(20.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MdColors.OnSurface)
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MdColors.OnSurfaceDim)
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = MdColors.OnSurfaceDim) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MdColors.DangerBg, contentColor = MdColors.DangerFg,
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) { Text(confirmLabel) }
            }
        }
    }
}

@Composable
private fun TrashedCard(note: Note, onRestore: () -> Unit, onDelete: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MdColors.Surface)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            note.title.ifBlank { "Untitled" },
            style = MaterialTheme.typography.titleMedium,
            color = MdColors.OnSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (note.preview.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(note.preview,
                style = MaterialTheme.typography.bodyMedium,
                color = MdColors.OnSurfaceDim,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRestore) {
                Icon(Icons.Outlined.Restore, null, tint = MdColors.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Restore", color = MdColors.Accent, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteForever, null, tint = MdColors.OnSurfaceDim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Delete forever", color = MdColors.OnSurfaceDim, fontWeight = FontWeight.Medium)
            }
        }
    }
}
