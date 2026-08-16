package com.marsglorious.blacknote.ui.list

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.marsglorious.blacknote.BuildConfig
import com.marsglorious.blacknote.data.safUriToFilePath
import com.marsglorious.blacknote.ui.theme.MdColors
import com.marsglorious.blacknote.viewmodel.AppViewModel

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    // System back must return to the list, not exit the app.
    androidx.activity.compose.BackHandler { viewModel.backToList() }
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    // Use the system folder picker for a familiar UI, then convert the result to a real
    // file path. SAF is only used for picking — all subsequent I/O is direct file access.
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val path = safUriToFilePath(uri)
            if (path != null) {
                java.io.File(path).mkdirs()
                viewModel.onFolderPicked(path)
            } else {
                Toast.makeText(
                    context,
                    "Please choose a folder on your phone's internal storage",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

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
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = MdColors.OnSurface)
        }
        Spacer(Modifier.height(12.dp))

        SettingsCard(
            title = "Notes folder",
            subtitle = "Choose where BlackNote stores your .md files",
            icon = Icons.Outlined.CreateNewFolder,
            onClick = { pickFolder.launch(null) },
        )

        Spacer(Modifier.height(12.dp))

        SettingsToggle(
            title = "Show file location",
            subtitle = "Display each note's folder path in the list",
            checked = state.showFileLocation,
            onCheckedChange = { viewModel.setShowFileLocation(it) },
        )

        Spacer(Modifier.height(12.dp))

        SettingsToggle(
            title = "Show hashtag score details",
            subtitle = "Break down each suggestion's score by mentions, vocabulary overlap, length match, group size, and recency",
            checked = state.showHashtagScoreDetails,
            onCheckedChange = { viewModel.setShowHashtagScoreDetails(it) },
        )

        Spacer(Modifier.weight(1f))
        val gitHash = remember {
            try { context.assets.open("git_hash.txt").bufferedReader().readText().trim() }
            catch (_: Exception) { "?" }
        }
        Text(
            "BlackNote v${BuildConfig.VERSION_NAME} · $gitHash",
            style = MaterialTheme.typography.bodySmall,
            color = MdColors.OnSurfaceFaint,
            modifier = Modifier.padding(bottom = 12.dp).align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MdColors.Surface)
            .clickable { onCheckedChange(!checked) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = MdColors.OnSurface, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MdColors.OnSurfaceDim, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.width(12.dp))
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = MdColors.Accent,
                uncheckedColor = MdColors.OnSurfaceFaint,
                checkmarkColor = MdColors.Background,
            ),
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MdColors.Surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MdColors.Accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = MdColors.OnSurface, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MdColors.OnSurfaceDim, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = MdColors.Accent, contentColor = MdColors.Background),
            shape = RoundedCornerShape(12.dp),
        ) { Text("Change") }
    }
}
