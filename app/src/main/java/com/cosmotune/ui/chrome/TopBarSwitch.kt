package com.cosmotune.ui.chrome

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.music.ui.components.FrostedTopBar
import com.cosmotune.music.ui.components.topBarHeight
import dev.chrisbanes.haze.HazeState

/**
 * The phone's one top bar: [CosmoTopBar] while the new look is on (the shell exists), the old [FrostedTopBar] while it is off.
 *
 * The parameter list is [FrostedTopBar]'s, name for name and in the same order, and the old branch passes every argument by
 * name. That is the seam: when upstream adds a parameter to the old bar the old branch stops compiling, and this file is where
 * the new parameter has to be decided on. With the new look off nothing here runs but the old call.
 *
 * [transparentBackdrop], [artworkPageChrome] and [backButtonHazeState] describe the material of the old bar (its backdrop, its
 * glass and the haze it samples); the new bar draws nothing of its own and its buttons are glass circles, so they are not passed
 * on. [CosmoTopBar] takes the bar's [topBarHeight] (the status bar inset and the bar's own height), so every page's top padding
 * stays what it was.
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
    if (LocalCosmoShell.current != null) {
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
    } else {
        FrostedTopBar(
            title = title,
            scrolled = scrolled,
            modifier = modifier,
            transparentBackdrop = transparentBackdrop,
            artworkPageChrome = artworkPageChrome,
            backButtonHazeState = backButtonHazeState,
            trailingTitle = trailingTitle,
            onBack = onBack,
            refreshing = refreshing,
            pullFraction = pullFraction,
            accessory = accessory,
            actions = actions,
        )
    }
}
