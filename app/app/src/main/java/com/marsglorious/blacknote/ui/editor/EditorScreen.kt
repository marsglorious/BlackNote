package com.marsglorious.blacknote.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marsglorious.blacknote.ui.theme.MdColors
import com.marsglorious.blacknote.viewmodel.AppViewModel
import com.marsglorious.blacknote.viewmodel.EditorMode
import com.marsglorious.blacknote.viewmodel.UiState
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(state: UiState, viewModel: AppViewModel, onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(
        Modifier
            .fillMaxSize()
            .background(MdColors.Background)
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding(),
    ) {
        EditorTopBar(
            onBack = onBack,
            mode = state.editorMode,
            onToggleMode = { viewModel.toggleEditorMode() },
            onShare = { viewModel.shareCurrentNote() },
            savingHint = if (state.isSaving) "saving…" else null,
        )
        Spacer(Modifier.height(6.dp))
        if (state.editorMode == EditorMode.RENDER) {
            RenderView(
                title = state.editingTitle.text,
                body = state.editingBody.text,
                modifier = Modifier.weight(1f),
                onWikiLink = { viewModel.openWikiLink(it) },
            )
        } else {
            TitleField(value = state.editingTitle, onChange = { viewModel.onTitleChange(it) })
            Spacer(Modifier.height(4.dp))
            BodyField(
                value = state.editingBody,
                onChange = { viewModel.onBodyChange(it) },
                modifier = Modifier.weight(1f),
            )
            FormatToolbar(
                state = state,
                onApply = { viewModel.format(it) },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
            )
        }
    }
}

@Composable
private fun EditorTopBar(
    onBack: () -> Unit,
    mode: EditorMode,
    onToggleMode: () -> Unit,
    onShare: () -> Unit,
    savingHint: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = MdColors.OnSurface)
        }
        Spacer(Modifier.weight(1f))
        if (savingHint != null) {
            Text(savingHint, fontSize = 12.sp, color = MdColors.OnSurfaceDim)
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Outlined.Share, contentDescription = "Share note", tint = MdColors.OnSurface)
        }
        IconButton(onClick = onToggleMode) {
            Icon(
                if (mode == EditorMode.EDIT) Icons.Outlined.MenuBook else Icons.Outlined.Edit,
                contentDescription = if (mode == EditorMode.EDIT) "Preview" else "Edit",
                tint = MdColors.OnSurface,
            )
        }
    }
}

@Composable
private fun TitleField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = false,
            textStyle = MaterialTheme.typography.titleLarge.copy(
                fontSize = 28.sp, color = MdColors.OnSurface, fontWeight = FontWeight.SemiBold
            ),
            cursorBrush = SolidColor(MdColors.Accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.testTag("editor_title"),
        )
        if (value.text.isEmpty()) {
            Text(
                "Title",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 28.sp, color = MdColors.OnSurfaceFaint
                ),
            )
        }
    }
}

@Composable
private fun BodyField(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val styler = remember { MarkdownVisualTransformation() }
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(scroll)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MdColors.OnSurface),
                cursorBrush = SolidColor(MdColors.Accent),
                visualTransformation = styler,
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).testTag("editor_body"),
            )
            if (value.text.isEmpty()) {
                Text(
                    "Start writing in Markdown…",
                    style = MaterialTheme.typography.bodyLarge.copy(color = MdColors.OnSurfaceFaint),
                )
            }
        }
        EditorScrollIndicator(scroll, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun EditorScrollIndicator(scroll: ScrollState, modifier: Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(scroll.value) {
        alpha.animateTo(1f, tween(80))
        delay(1200)
        alpha.animateTo(0f, tween(360))
    }
    if (scroll.maxValue > 0) {
        BoxWithConstraints(modifier.width(14.dp).fillMaxHeight()) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val viewportPx = constraints.maxHeight.toFloat()
            val contentPx = viewportPx + scroll.maxValue
            val minThumbPx = with(density) { 40.dp.toPx() }
            val thumbPx = (viewportPx * viewportPx / contentPx).coerceAtLeast(minThumbPx)
            val thumbY = (scroll.value.toFloat() / scroll.maxValue) * (viewportPx - thumbPx)
            Box(
                Modifier
                    .offset { IntOffset(0, thumbY.toInt()) }
                    .width(5.dp)
                    .height(with(density) { thumbPx.toDp() })
                    .clip(RoundedCornerShape(50))
                    .background(MdColors.OnSurfaceDim.copy(alpha = alpha.value * 0.95f))
                    .align(Alignment.TopEnd)
            )
        }
    }
}
