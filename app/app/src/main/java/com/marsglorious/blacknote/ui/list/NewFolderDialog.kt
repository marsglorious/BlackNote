package com.marsglorious.blacknote.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.marsglorious.blacknote.ui.theme.MdColors

@Composable
fun NewFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MdColors.Surface)
                .padding(16.dp)
        ) {
            Text("New folder", style = MaterialTheme.typography.titleMedium, color = MdColors.OnSurface)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MdColors.SurfaceHi)
                    .padding(12.dp)
            ) {
                BasicTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    textStyle = TextStyle(color = MdColors.OnSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(MdColors.Accent),
                )
                if (name.isEmpty()) {
                    Text("Folder name", color = MdColors.OnSurfaceFaint, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = MdColors.OnSurfaceDim) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { if (name.isNotBlank()) onConfirm(name) },
                    enabled = name.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = MdColors.Accent, contentColor = MdColors.Background),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Create") }
            }
        }
    }
}
