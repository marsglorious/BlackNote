package com.marsglorious.blacknote.ui.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Regression test for scrollbar drag cancellation.
 *
 * Root cause: pointerInput(state, total, avgItemH) restarts its coroutine whenever
 * avgItemH changes — which happens on every scroll frame when item heights are mixed.
 * That restart cancels any in-flight drag gesture immediately after it begins.
 * Taps are unaffected (they complete in one frame); only sustained drags fail.
 *
 * Items alternate between 40dp and 80dp heights so that avgItemH shifts as the list
 * scrolls, reliably triggering the restart on the unfixed code path.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScrollbarDragTest {

    @get:Rule
    val rule = createComposeRule()

    private val itemCount = 80

    // Alternating heights ensure avgItemH is not constant as the list scrolls,
    // which is what triggers the pointerInput key change and drag cancellation.
    private fun itemHeight(index: Int) = if (index % 3 == 0) 80.dp else 40.dp

    private fun setUpScrollableList(): LazyListState {
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    items(itemCount) { idx ->
                        Box(Modifier.height(itemHeight(idx))) { Text("Item $idx") }
                    }
                }
                DraggableScrollbar(state = state, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
        rule.waitForIdle()
        return state
    }

    @Test
    fun scrollbarTap_jumpsListForward() {
        val state = setUpScrollableList()

        val before = state.firstVisibleItemIndex

        // Tap near the bottom of the scrollbar — should jump the list forward.
        rule.onRoot().performTouchInput {
            click(Offset(right - 5f, bottom * 0.85f))
        }
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()

        val after = state.firstVisibleItemIndex
        assertTrue(
            "Tap near bottom of scrollbar should advance firstVisibleItemIndex " +
            "(before=$before, after=$after)",
            after > before + 10,
        )
    }

    @Test
    fun scrollbarDrag_scrollsListForward() {
        val state = setUpScrollableList()

        val before = state.firstVisibleItemIndex

        // Drag from the top portion of the scrollbar strip to near the bottom.
        // The scrollbar occupies the right 20dp of the Box; target x = right - 5f.
        // A long, slow swipe ensures the drag gesture survives several compose frames.
        rule.onRoot().performTouchInput {
            swipe(
                start = Offset(right - 5f, top + height * 0.05f),
                end   = Offset(right - 5f, bottom - height * 0.05f),
                durationMillis = 800,
            )
        }
        rule.mainClock.advanceTimeBy(300)
        rule.waitForIdle()

        val after = state.firstVisibleItemIndex
        assertTrue(
            "Scrollbar drag should advance firstVisibleItemIndex significantly " +
            "(before=$before, after=$after). If after==before, the drag gesture " +
            "was cancelled mid-swipe due to pointerInput key instability.",
            after > before + 15,
        )
    }
}
