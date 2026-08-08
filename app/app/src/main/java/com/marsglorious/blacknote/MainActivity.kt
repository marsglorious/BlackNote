package com.marsglorious.blacknote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.marsglorious.blacknote.ui.CrashBanner
import com.marsglorious.blacknote.ui.editor.EditorScreen
import com.marsglorious.blacknote.ui.list.FolderPickerDialog
import com.marsglorious.blacknote.ui.list.NoteListScreen
import com.marsglorious.blacknote.ui.list.SettingsScreen
import com.marsglorious.blacknote.ui.list.TrashScreen
import com.marsglorious.blacknote.ui.theme.BlackNoteTheme
import com.marsglorious.blacknote.ui.theme.MdColors
import com.marsglorious.blacknote.viewmodel.AppViewModel
import com.marsglorious.blacknote.viewmodel.FolderPickerTask
import com.marsglorious.blacknote.viewmodel.Screen

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels { AppViewModel.factory(application as App) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BlackNoteTheme {
                val ctx = LocalContext.current
                LaunchedEffect(Unit) { viewModel.bootstrap(ctx) }
                LaunchedEffect(Unit) {
                    viewModel.shareEvents.collect { (title, body) ->
                        val text = if (title.isBlank()) body else "$title\n\n$body"
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, title)
                            putExtra(android.content.Intent.EXTRA_TEXT, text)
                        }
                        ctx.startActivity(android.content.Intent.createChooser(intent, "Share note"))
                    }
                }
                val ui by viewModel.uiState.collectAsState()
                var crashReport by remember { mutableStateOf(CrashReporter.lastReport(ctx)) }
                Column(Modifier.fillMaxSize().background(MdColors.Background)) {
                    crashReport?.let { report ->
                        CrashBanner(report = report, onDismiss = {
                            CrashReporter.clear(ctx)
                            crashReport = null
                        })
                    }
                    AnimatedContent(
                        targetState = ui.screen,
                        transitionSpec = {
                            when {
                                targetState == Screen.EDITOR ->
                                    (fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 12 })
                                        .togetherWith(fadeOut(tween(160)))
                                initialState == Screen.EDITOR ->
                                    fadeIn(tween(200)).togetherWith(
                                        fadeOut(tween(160)) + slideOutVertically(tween(220)) { it / 12 }
                                    )
                                else ->
                                    fadeIn(tween(200)).togetherWith(fadeOut(tween(160)))
                            }
                        },
                        label = "screen",
                    ) { screen ->
                        when (screen) {
                            Screen.EDITOR   -> EditorScreen(state = ui, viewModel = viewModel,
                                onBack = { viewModel.closeEditor() })
                            Screen.TRASH    -> TrashScreen(state = ui, viewModel = viewModel)
                            Screen.SETTINGS -> SettingsScreen(viewModel = viewModel)
                            Screen.LIST     -> NoteListScreen(state = ui, viewModel = viewModel)
                        }
                    }
                }

                ui.folderPickerFor?.let { task ->
                    // Compute rootUri robustly as the parent uri that is not itself a known folder path.
                    // This ensures the picker root correctly points to the top of the tree,
                    // and the passed folders list contains the subfolders within the selected (root) directory.
                    val knownFolderPaths = ui.tree.folders.map { it.path }.toSet()
                    val rootUri = (ui.tree.notes.map { it.parent } + ui.tree.folders.map { it.parent })
                        .firstOrNull { it !in knownFolderPaths }
                        ?: ui.tree.folders.firstOrNull()?.parent
                        ?: ui.tree.notes.firstOrNull()?.parent
                        ?: ""
                    FolderPickerDialog(
                        title = when (task) {
                            is FolderPickerTask.Move -> "Move to…"
                            is FolderPickerTask.Copy -> "Copy to…"
                        },
                        folders = ui.tree.folders,
                        rootUri = rootUri,
                        onDismiss = { viewModel.cancelFolderPicker() },
                        onPick = { viewModel.completeFolderPicker(it) },
                    )
                }
                if (ui.showNewFolderDialog) {
                    com.marsglorious.blacknote.ui.list.NewFolderDialog(
                        onDismiss = { viewModel.cancelNewFolder() },
                        onConfirm = { viewModel.createFolder(it) },
                    )
                }
            }
        }
    }
}
