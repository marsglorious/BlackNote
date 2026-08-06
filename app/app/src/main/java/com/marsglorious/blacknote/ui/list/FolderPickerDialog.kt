package com.marsglorious.blacknote.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.marsglorious.blacknote.data.FolderInfo
import com.marsglorious.blacknote.ui.theme.MdColors

@Composable
fun FolderPickerDialog(
    title: String,
    folders: List<FolderInfo>,
    rootUri: String,
    onDismiss: () -> Unit,
    onPick: (folderUri: String) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MdColors.Surface)
                .padding(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MdColors.OnSurface)
            Spacer(Modifier.height(12.dp))
            val sortedFolders = folders.sortedWith(compareBy<FolderInfo> { it.depth }.thenBy { it.name.lowercase() })
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    FolderRow(name = "(root)", depth = 0, icon = Icons.Outlined.Home,
                        onClick = { onPick(rootUri) })
                }
                items(sortedFolders, key = { it.path }) { f ->
                    FolderRow(name = f.name, depth = f.depth + 1, icon = Icons.Outlined.Folder,
                        onClick = { onPick(f.path) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = MdColors.OnSurfaceDim) }
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, depth: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(start = (depth * 14).dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MdColors.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(name, color = MdColors.OnSurface)
    }
}
