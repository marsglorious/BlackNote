package com.marsglorious.blacknote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marsglorious.blacknote.ui.theme.MdColors

@Composable
fun CrashBanner(report: String, onDismiss: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MdColors.LabelChipBg)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.BugReport, contentDescription = null, tint = MdColors.LabelChipFg)
            Spacer(Modifier.width(8.dp))
            Text(
                if (expanded) "Last crash" else "Last crash — tap to expand",
                color = MdColors.LabelChipFg,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f).clickable { expanded = !expanded },
            )
            IconButton(onClick = { copy(ctx, report); toast(ctx, "Copied crash log") }) {
                Icon(Icons.Outlined.ContentCopy, "Copy", tint = MdColors.LabelChipFg)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, "Dismiss", tint = MdColors.LabelChipFg)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .heightIn(max = 220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MdColors.Background)
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    report,
                    color = MdColors.OnSurface,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("BlackNote crash", text))
}
private fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
