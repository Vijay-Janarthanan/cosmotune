package com.cosmotune.ui.chrome

import androidx.compose.runtime.Composable
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
