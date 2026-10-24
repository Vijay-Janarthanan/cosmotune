package com.cosmotune.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.components.ShelfRow
import com.cosmotune.music.ui.components.rememberShelfFlingBehavior
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * BC-32, rendered: a fling on a shelf settles with a card's leading edge at the start of the row, never with a card cut in half.
 * The control row (the same row without the shelf fling) shows the test can fail: the same fling leaves it between cards.
 */
class ShelfSnapInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val cards = (1..40).map { "card $it" }

    private fun fling(state: () -> LazyListState) {
        compose.onNodeWithTag("row").performTouchInput { swipeLeft(startX = right - 10f, endX = right - 190f, durationMillis = 60) }
        compose.waitForIdle()
        assertTrue(state().firstVisibleItemIndex > 0, "the fling moved the row")
    }

    @Test fun aFlungShelfSettlesOnACardEdge() {
        lateinit var state: LazyListState
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    state = rememberLazyListState()
                    ShelfRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.testTag("row"),
                        state = state,
                    ) {
                        items(cards) { Box(Modifier.size(width = 137.dp, height = 60.dp)) { Text(it) } }
                    }
                }
            }
        }
        fling { state }
        assertTrue(state.firstVisibleItemScrollOffset == 0, "settled mid-card at offset ${state.firstVisibleItemScrollOffset}")
    }

    @Test fun withoutTheShelfFlingTheSameFlingStopsBetweenCards() {
        lateinit var state: LazyListState
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    state = rememberLazyListState()
                    LazyRow(
                        state = state,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.testTag("row"),
                    ) {
                        items(cards) { Box(Modifier.size(width = 137.dp, height = 60.dp)) { Text(it) } }
                    }
                }
            }
        }
        fling { state }
        assertNotEquals(0, state.firstVisibleItemScrollOffset, "the control row happened to stop on an edge")
    }

    @Test fun theSharedBehaviourAlsoSnapsARowThatIsNotAShelf() {
        // The artist page's carousels and the desktop's rows are plain LazyRows given the same behaviour.
        lateinit var state: LazyListState
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    state = rememberLazyListState()
                    LazyRow(
                        state = state,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        flingBehavior = rememberShelfFlingBehavior(state),
                        modifier = Modifier.testTag("row"),
                    ) {
                        items(cards) { Box(Modifier.size(width = 290.dp, height = 60.dp)) { Text(it) } }
                    }
                }
            }
        }
        fling { state }
        assertTrue(state.firstVisibleItemScrollOffset == 0, "settled mid-column at offset ${state.firstVisibleItemScrollOffset}")
    }
}
