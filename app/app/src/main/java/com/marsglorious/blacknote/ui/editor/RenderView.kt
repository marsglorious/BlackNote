package com.marsglorious.blacknote.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marsglorious.blacknote.ui.theme.MdColors

@Composable
fun RenderView(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    onWikiLink: ((String) -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    val styled = remember(body, onWikiLink) { renderMarkdown(body, onWikiLink) }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .verticalScroll(scroll)
    ) {
        Column {
            if (title.isNotBlank()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = 28.sp, color = MdColors.OnSurface, fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(
                styled,
                style = MaterialTheme.typography.bodyLarge.copy(color = MdColors.OnSurface, lineHeight = 24.sp),
            )
        }
    }
}
