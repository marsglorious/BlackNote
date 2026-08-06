package com.marsglorious.blacknote.ui.list

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.marsglorious.blacknote.BuildConfig
import com.marsglorious.blacknote.ui.theme.MdColors
import com.marsglorious.blacknote.viewmodel.AppViewModel

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    // System back must return to the list, not exit the app.
    androidx.activity.compose.BackHandler { viewModel.backToList() }
    val pickFolder = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.onFolderPicked(uri) }

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

        Spacer(Modifier.weight(1f))
        Text(
            "BlackNote v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MdColors.OnSurfaceFaint,
            modifier = Modifier.padding(bottom = 12.dp).align(Alignment.CenterHorizontally),
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
