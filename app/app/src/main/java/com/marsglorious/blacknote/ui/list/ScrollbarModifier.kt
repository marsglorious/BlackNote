package com.marsglorious.blacknote.ui.list

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.marsglorious.blacknote.ui.theme.MdColors
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Draggable scrollbar overlay for a LazyColumn. Renders on the right edge with a
 * wide finger-friendly hit area (16dp) but a slim visible thumb (5dp). The thumb
 * fades in on scroll and on user touch, and fades out ~1.2s after the user lets
 * go. Drag the thumb (or tap a position) to jump the list.
 */
@Composable
fun DraggableScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    color: Color = MdColors.OnSurfaceDim.copy(alpha = 0.95f),
    track: Color = MdColors.SurfaceHi.copy(alpha = 0.4f),
) {
    val density = LocalDensity.current
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(state, dragging) {
        snapshotFlow { state.isScrollInProgress to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collectLatest { (scrolling, _) ->
                alpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(80))
                if (!scrolling && !dragging) {
                    kotlinx.coroutines.delay(1200)
                    alpha.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(360))
                }
            }
    }

    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total <= 0) return
    val viewportH = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (viewportH <= 0) return
    // Average actual rendered item height of currently visible items, including spacing.
    // Far more accurate than just .size of the first visible item alone — the LazyColumn
    // has 8.dp vertical spacing between rows, and folder cards are shorter than note cards.
    val avgItemH = run {
        val visibles = info.visibleItemsInfo
        if (visibles.isEmpty()) 1f
        else {
            val first = visibles.first()
            val last = visibles.last()
            val span = (last.offset + last.size) - first.offset
            val count = (last.index - first.index + 1).coerceAtLeast(1)
            (span.toFloat() / count).coerceAtLeast(1f)
        }
    }
    val totalContentH = (total.toFloat() * avgItemH).coerceAtLeast(viewportH + 1f)
    val scrolledPx = state.firstVisibleItemIndex.toFloat() * avgItemH + state.firstVisibleItemScrollOffset
    val minThumbPx = with(density) { 40.dp.toPx() }
    val thumbHPx = (viewportH * viewportH / totalContentH).coerceAtLeast(minThumbPx)
    val maxScroll = (totalContentH - viewportH).coerceAtLeast(1f)
    val thumbTrackPx = (viewportH - thumbHPx).coerceAtLeast(1f)
    val thumbYPx = ((scrolledPx / maxScroll) * thumbTrackPx).coerceIn(0f, thumbTrackPx)

    // Ratio: 1 thumb-pixel moved = (maxScroll / thumbTrackPx) content-pixels scrolled.
    val scrollPerThumbPx = maxScroll / thumbTrackPx

    // pointerInput restarts its coroutine when any key changes. Using computed
    // geometry values (avgItemH, total, thumbTrackPx…) as keys means the drag
    // gesture is cancelled the moment the list starts scrolling and those values
    // shift. Capture them via rememberUpdatedState instead — the lambda always
    // sees the latest value without the block ever being restarted mid-gesture.
    val latestScrollPerThumbPx = rememberUpdatedState(scrollPerThumbPx)
    val latestThumbHPx         = rememberUpdatedState(thumbHPx)
    val latestThumbTrackPx     = rememberUpdatedState(thumbTrackPx)
    val latestMaxScroll        = rememberUpdatedState(maxScroll)
    val latestAvgItemH         = rememberUpdatedState(avgItemH)
    val latestTotal            = rememberUpdatedState(total)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(20.dp)
            .pointerInput(state) {
                detectTapGestures { tap ->
                    // Tap on track: jump so the thumb centers on the tap position.
                    val h     = latestThumbHPx.value
                    val track = latestThumbTrackPx.value
                    val maxS  = latestMaxScroll.value
                    val avgH  = latestAvgItemH.value
                    val tot   = latestTotal.value
                    val newThumbY = (tap.y - h / 2f).coerceIn(0f, track)
                    val fraction  = newThumbY / track
                    val targetIdx = ((fraction * maxS) / avgH).toInt().coerceIn(0, tot - 1)
                    val targetOff = ((fraction * maxS) - targetIdx * avgH).toInt().coerceAtLeast(0)
                    scope.launch { state.scrollToItem(targetIdx, targetOff) }
                }
            }
            .pointerInput(state) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        scope.launch { alpha.animateTo(1f) }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    // Synchronous, no coroutine per event. dispatchRawDelta is exactly
                    // how the LazyColumn responds to a finger drag on its own content,
                    // so the thumb tracks the finger at native scroll latency.
                    state.dispatchRawDelta(dragAmount.y * latestScrollPerThumbPx.value)
                }
            },
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            Modifier
                .width(if (dragging) 8.dp else 5.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(track.copy(alpha = track.alpha * alpha.value))
        )
        Box(
            Modifier
                .offset { IntOffset(0, thumbYPx.roundToInt()) }
                .width(if (dragging) 8.dp else 5.dp)
                .height(with(density) { thumbHPx.toDp() })
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = color.alpha * alpha.value))
        )
    }
}

/**
 * Draggable scrollbar overlay for a LazyVerticalStaggeredGrid. Mirrors the LazyListState
 * variant above; geometry is adapted for a fixed 2-column staggered layout.
 */
@Composable
fun DraggableScrollbar(
    state: LazyStaggeredGridState,
    modifier: Modifier = Modifier,
    color: Color = MdColors.OnSurfaceDim.copy(alpha = 0.95f),
    track: Color = MdColors.SurfaceHi.copy(alpha = 0.4f),
) {
    val density = LocalDensity.current
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(state, dragging) {
        snapshotFlow { state.isScrollInProgress to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collectLatest { (scrolling, _) ->
                alpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(80))
                if (!scrolling && !dragging) {
                    kotlinx.coroutines.delay(1200)
                    alpha.animateTo(0f, animationSpec = androidx.compose.animation.core.tween(360))
                }
            }
    }

    val info = state.layoutInfo
    val total = info.totalItemsCount
    if (total <= 0) return
    val viewportH = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (viewportH <= 0) return

    // Average height of currently visible items.
    val avgItemH = run {
        val visibles = info.visibleItemsInfo
        if (visibles.isEmpty()) 1f
        else visibles.map { it.size.height.toFloat() }.average().toFloat().coerceAtLeast(1f)
    }

    // 2-column grid: each "row" holds 2 items. Ceiling-divide for the last odd item.
    val estimatedRows = (total + 1) / 2
    val totalContentH = (estimatedRows.toFloat() * avgItemH).coerceAtLeast(viewportH + 1f)

    // firstVisibleItemIndex / 2 ≈ row index in a balanced 2-column grid.
    val scrolledPx = (state.firstVisibleItemIndex / 2f) * avgItemH + state.firstVisibleItemScrollOffset

    val minThumbPx = with(density) { 40.dp.toPx() }
    val thumbHPx = (viewportH * viewportH / totalContentH).coerceAtLeast(minThumbPx)
    val maxScroll = (totalContentH - viewportH).coerceAtLeast(1f)
    val thumbTrackPx = (viewportH - thumbHPx).coerceAtLeast(1f)
    val thumbYPx = ((scrolledPx / maxScroll) * thumbTrackPx).coerceIn(0f, thumbTrackPx)
    val scrollPerThumbPx = maxScroll / thumbTrackPx

    val latestScrollPerThumbPx = rememberUpdatedState(scrollPerThumbPx)
    val latestThumbHPx         = rememberUpdatedState(thumbHPx)
    val latestThumbTrackPx     = rememberUpdatedState(thumbTrackPx)
    val latestMaxScroll        = rememberUpdatedState(maxScroll)
    val latestAvgItemH         = rememberUpdatedState(avgItemH)
    val latestTotal            = rememberUpdatedState(total)

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(20.dp)
            .pointerInput(state) {
                detectTapGestures { tap ->
                    val h     = latestThumbHPx.value
                    val track = latestThumbTrackPx.value
                    val maxS  = latestMaxScroll.value
                    val avgH  = latestAvgItemH.value
                    val tot   = latestTotal.value
                    val newThumbY = (tap.y - h / 2f).coerceIn(0f, track)
                    val fraction  = newThumbY / track
                    // Item index proportional to fraction across all items.
                    val targetIdx = (fraction * tot).toInt().coerceIn(0, tot - 1)
                    val targetOff = ((fraction * maxS) - (targetIdx / 2f) * avgH)
                        .toInt().coerceAtLeast(0)
                    scope.launch { state.scrollToItem(targetIdx, targetOff) }
                }
            }
            .pointerInput(state) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        scope.launch { alpha.animateTo(1f) }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    state.dispatchRawDelta(dragAmount.y * latestScrollPerThumbPx.value)
                }
            },
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            Modifier
                .width(if (dragging) 8.dp else 5.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(track.copy(alpha = track.alpha * alpha.value))
        )
        Box(
            Modifier
                .offset { IntOffset(0, thumbYPx.roundToInt()) }
                .width(if (dragging) 8.dp else 5.dp)
                .height(with(density) { thumbHPx.toDp() })
                .clip(RoundedCornerShape(50))
                .background(color.copy(alpha = color.alpha * alpha.value))
        )
    }
}
