package com.marsglorious.blacknote.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marsglorious.blacknote.ui.theme.MdColors
import com.marsglorious.blacknote.viewmodel.AppViewModel
import com.marsglorious.blacknote.viewmodel.EditorMode
import com.marsglorious.blacknote.viewmodel.HashtagSuggestion
import com.marsglorious.blacknote.viewmodel.UiState
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

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
            val currentInNote = remember(state.editingBody.text) {
                Regex("""(?<![a-zA-Z0-9_])#([a-zA-Z][a-zA-Z0-9_\-/]*)""")
                    .findAll(state.editingBody.text)
                    .map { it.groupValues[1].lowercase() }
                    .toSet()
            }
            val quoteTags = remember(state.editingBody.text) {
                AppViewModel.detectQuoteTags(state.editingBody.text)
            }
            val pendingQuoteTags = quoteTags.filter { it.lowercase() !in currentInNote }
            val suggestionKey = if (pendingQuoteTags.isEmpty()) "" else pendingQuoteTags.sorted().joinToString(",")
            var autoAddedKey by remember { mutableStateOf("") }
            LaunchedEffect(suggestionKey) {
                if (suggestionKey.isNotEmpty() && suggestionKey != autoAddedKey) {
                    pendingQuoteTags.forEach { viewModel.toggleHashtag(it) }
                    autoAddedKey = suggestionKey
                }
            }
            if (state.hashtagPickerOpen) {
                HashtagPickerPanel(
                    suggestions = state.hashtagSuggestions,
                    currentInNote = currentInNote,
                    showScoreDetails = state.showHashtagScoreDetails,
                    onTap = { viewModel.toggleHashtag(it) },
                    onPickNew = { viewModel.insertHashtag(it) },
                    onDismiss = { viewModel.closeHashtagPicker() },
                )
            }
            FormatToolbar(
                state = state,
                onApply = { viewModel.format(it) },
                onUndo = { viewModel.undo() },
                onRedo = { viewModel.redo() },
                onHashtag = { viewModel.openHashtagPicker() },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HashtagPickerPanel(
    suggestions: List<HashtagSuggestion>,
    currentInNote: Set<String>,
    showScoreDetails: Boolean,
    onTap: (String) -> Unit,
    onPickNew: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    var showNewTagInput by remember { mutableStateOf(false) }
    var newTagText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val halfScreen = (LocalConfiguration.current.screenHeightDp / 2).dp

    LaunchedEffect(showNewTagInput) {
        if (showNewTagInput) { delay(60); focusRequester.requestFocus() }
    }

    Column(Modifier.fillMaxWidth().background(MdColors.SurfaceHi2)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Hashtags",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MdColors.OnSurfaceDim,
                modifier = Modifier.weight(1f),
            )
            if (suggestions.isNotEmpty()) {
                Text("${suggestions.size}", fontSize = 11.sp, color = MdColors.OnSurfaceFaint, modifier = Modifier.padding(end = 2.dp))
            }
            TextButton(
                onClick = { showNewTagInput = !showNewTagInput; if (!showNewTagInput) newTagText = ""; expanded = true },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Text("+ new #", fontSize = 11.sp, color = MdColors.Accent, fontWeight = FontWeight.Medium)
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MdColors.OnSurfaceDim,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Close, "Close hashtag picker", tint = MdColors.OnSurfaceDim, modifier = Modifier.size(18.dp))
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(180)),
            exit = shrinkVertically(tween(150)),
        ) {
            Column {
                HorizontalDivider(color = MdColors.Divider, thickness = 1.dp)

                // Inline new-tag input
                if (showNewTagInput) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MdColors.SurfaceHi)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("#", color = MdColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(4.dp))
                        BasicTextField(
                            value = newTagText,
                            onValueChange = { raw ->
                                newTagText = raw.lowercase()
                                    .filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '/' }
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MdColors.OnSurface),
                            cursorBrush = SolidColor(MdColors.Accent),
                            modifier = Modifier.weight(1f).focusRequester(focusRequester),
                            decorationBox = { inner ->
                                Box {
                                    if (newTagText.isEmpty()) Text("type a tag name…", color = MdColors.OnSurfaceFaint, fontSize = 14.sp)
                                    inner()
                                }
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                        TextButton(
                            onClick = {
                                val tag = newTagText.trim()
                                if (tag.isNotBlank()) { onPickNew(tag); newTagText = ""; showNewTagInput = false }
                            },
                            enabled = newTagText.isNotBlank(),
                        ) {
                            Text("Add", color = if (newTagText.isNotBlank()) MdColors.Accent else MdColors.OnSurfaceFaint, fontSize = 13.sp)
                        }
                        TextButton(onClick = { newTagText = ""; showNewTagInput = false }) {
                            Text("Cancel", color = MdColors.OnSurfaceDim, fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = MdColors.Divider, thickness = 1.dp)
                }

                if (suggestions.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(72.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No hashtags yet — tap \"+ new #\" to create one",
                            fontSize = 12.sp,
                            color = MdColors.OnSurfaceDim,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                } else {
                    val chipScroll = rememberScrollState()
                    Box(Modifier.fillMaxWidth().heightIn(max = halfScreen)) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().verticalScroll(chipScroll).padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Glowing (in-note) chips come first so they're always visible at the top
                            for (s in suggestions.filter { it.tag.lowercase() in currentInNote }) {
                                HashtagChip(s, showScoreDetails, isActive = true, onTap = onTap)
                            }
                            for (s in suggestions.filter { it.tag.lowercase() !in currentInNote }) {
                                HashtagChip(s, showScoreDetails, isActive = false, onTap = onTap)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HashtagChip(
    s: HashtagSuggestion,
    showScoreDetails: Boolean,
    isActive: Boolean,
    onTap: (String) -> Unit,
) {
    val color = MdColors.hashtagColor(s.tag)
    val shape = RoundedCornerShape(10.dp)
    val bgTop = if (isActive) color.copy(alpha = 0.92f) else color.copy(alpha = 0.50f)
    val bgBot = if (isActive) color.copy(alpha = 0.70f) else color.copy(alpha = 0.26f)
    val borderColor = if (isActive) color else color.copy(alpha = 0.50f)
    val borderWidth = if (isActive) 1.5.dp else 1.dp
    val textColor = if (isActive) MdColors.Background else color
    val subColor = if (isActive) MdColors.Background.copy(alpha = 0.75f) else color.copy(alpha = 0.80f)
    Box(
        Modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(bgTop, bgBot)))
            .border(borderWidth, borderColor, shape)
            .clickable { onTap(s.tag) }
            .padding(horizontal = 11.dp, vertical = 8.dp),
    ) {
        Column {
            Text("#${s.tag}", color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${s.noteCount} ${if (s.noteCount == 1) "note" else "notes"}  ·  ★${s.score}",
                color = subColor,
                fontSize = 10.sp,
            )
            if (showScoreDetails) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = borderColor.copy(alpha = 0.25f), thickness = 1.dp)
                Spacer(Modifier.height(3.dp))
                ScoreRow("mentions", s.scoreMentions, "Tag words (or synonyms) found in this note — title hits count 5×, body 1×", color)
                ScoreRow("overlap", s.scoreOverlap, "Rare words this note shares with tagged notes — uncommon words count more than common ones", color)
                ScoreRow("length", s.scoreLength, "How close this note's length is to the tag group's average", color)
                ScoreRow("group", s.scoreGroup, "Popularity on a log scale — minor boost for commonly used tags", color)
                ScoreRow("recency", s.scoreRecency, "How recently any note was tagged with it — decays to 0 over 30 days", color)
            }
        }
    }
}

@Composable
private fun ScoreRow(label: String, value: Int, description: String, color: androidx.compose.ui.graphics.Color) {
    Column(Modifier.padding(bottom = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 9.sp,
                color = color.copy(alpha = 0.60f),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(52.dp),
            )
            Text(
                "+$value",
                fontSize = 9.sp,
                color = color.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            description,
            fontSize = 8.sp,
            color = color.copy(alpha = 0.48f),
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun EditorScrollIndicator(scroll: ScrollState, modifier: Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(scroll.value) {
        alpha.animateTo(1f, tween(80))
        delay(1200.milliseconds)
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
