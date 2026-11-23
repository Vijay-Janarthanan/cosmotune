package com.cosmotune.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cosmotune.music.ui.components.topBarHeight
import dev.chrisbanes.haze.HazeState

/**
 * The phone's one top bar: [CosmoTopBar].
 *
 * The parameter list is upstream's `FrostedTopBar`'s, name for name and in the same order, so upstream's call site still merges.
 * [transparentBackdrop], [artworkPageChrome] and [backButtonHazeState] described the material of the old bar; the bar draws nothing
 * of its own and its buttons are glass circles, so they are accepted and ignored. [CosmoTopBar] takes the bar's [topBarHeight] (the
 * status bar inset and the bar's own height), so every page's top padding stays what it was.
 */
@Composable
fun TopBarSwitch(
    title: String,
    scrolled: Boolean,
    modifier: Modifier = Modifier,
    /** Leaves the whole bar transparent so the page's own top gradient is the backdrop. */
    transparentBackdrop: Boolean = false,
    /** Circular back surface and no collapsing title, for artwork-led pages. */
    artworkPageChrome: Boolean = false,
    /** Source sampled by floating top-bar surfaces when liquid glass is off. */
    backButtonHazeState: HazeState? = null,
    trailingTitle: String? = null,
    onBack: (() -> Unit)? = null,
    refreshing: Boolean = false,
    // A lambda, not a value: the drag changes every frame, and reading it in
    // the caller would recompose the whole app on each one.
    pullFraction: () -> Float = { 0f },
    /**
     * A page control filling the bar from the leading gutter to [actions], on
     * no surface of its own — the Search tab's field, which brings its own.
     * Root tabs have no back button there for it to collide with.
     */
    accessory: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    CosmoTopBar(
        title = title,
        scrolled = scrolled,
        height = topBarHeight(),
        modifier = modifier,
        trailingTitle = trailingTitle,
        onBack = onBack,
        refreshing = refreshing,
        pullFraction = pullFraction,
        accessory = accessory,
        actions = actions,
    )
}

/**
 * How far a page that leads with its own big heading (Settings, a mood's page) has been scrolled, heard from the scrolls its
 * lists pass up ([connection], on the box the pages stand in), so the bar's title can wait until that heading has gone under the
 * bar instead of repeating it right above it. [reset] when another page opens.
 */
class HeadingScroll {
    private var offset by androidx.compose.runtime.mutableFloatStateOf(0f)

    val connection = object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
        override fun onPostScroll(
            consumed: androidx.compose.ui.geometry.Offset,
            available: androidx.compose.ui.geometry.Offset,
            source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
        ): androidx.compose.ui.geometry.Offset {
            offset = (offset - consumed.y).coerceAtLeast(0f)
            return androidx.compose.ui.geometry.Offset.Zero
        }
    }

    /** Whether the page has moved more than [threshold] pixels (about its heading's height) off its top. */
    fun past(threshold: Float): Boolean = offset > threshold

    fun reset() { offset = 0f }
}
