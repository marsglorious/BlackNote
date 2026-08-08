package com.marsglorious.blacknote.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.marsglorious.blacknote.ffi.FormatKind
import com.marsglorious.blacknote.ui.theme.MdColors
import com.marsglorious.blacknote.viewmodel.UiState

@Composable
fun FormatToolbar(
    state: UiState,
    onApply: (FormatKind) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MdColors.SurfaceHi)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ToolButton(Icons.AutoMirrored.Outlined.Undo, "Undo", enabled = state.canUndo, onClick = onUndo)
        ToolButton(Icons.AutoMirrored.Outlined.Redo, "Redo", enabled = state.canRedo, onClick = onRedo)
        ToolButton(Icons.Outlined.FormatBold, "Format bold")         { onApply(FormatKind.BOLD) }
        ToolButton(Icons.Outlined.FormatItalic, "Format italic")       { onApply(FormatKind.ITALIC) }
        ToolButton(Icons.Outlined.FormatUnderlined, "Format underline")   { onApply(FormatKind.UNDERLINE) }
        ToolButton(Icons.Outlined.FormatStrikethrough, "Format strikethrough"){ onApply(FormatKind.STRIKE) }
        ToolButton(Icons.Outlined.FormatListNumbered, "Format ordered list") { onApply(FormatKind.ORDERED_LIST) }
        ToolButton(Icons.AutoMirrored.Outlined.FormatListBulleted, "Format bullet list") { onApply(FormatKind.BULLET_LIST) }
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, contentDescription = label,
            tint = if (enabled) MdColors.OnSurface else MdColors.OnSurfaceFaint,
        )
    }
}
