package com.cosmotune.music.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState

/*
 * Lists on a round screen. A row that runs to the edge of a circle loses its corners and the ends of its words wherever the circle
 * is narrower than the row, so every list keeps a margin in from the bezel on each side (7 % of the screen, Wear's own list margin
 * for a round face); text that wraps keeps a wider one ([WearTextInset]). The top and bottom of a list fade into the ground, so a
 * row scrolled up under the clock goes dark rather than running through it.
 */

/** How far a list's rows sit in from the bezel on each side. */
@Composable
fun wearEdgeInset(): Dp = (LocalConfiguration.current.screenWidthDp * 0.07f).dp

/** The extra inset for lines of text that wrap (a hint, a message), so their ends clear the curve. */
val WearTextInset = 10.dp

/** [padding] (the scaffold's) with the round screen's side margins added. */
@Composable
fun wearListPadding(padding: PaddingValues): PaddingValues {
    val direction = LocalLayoutDirection.current
    val side = wearEdgeInset()
    return PaddingValues(
        start = padding.calculateStartPadding(direction) + side,
        end = padding.calculateEndPadding(direction) + side,
        top = padding.calculateTopPadding(),
        bottom = padding.calculateBottomPadding(),
    )
}

/** Fades the top [top] and bottom [bottom] fractions of what is drawn here into transparency (the ground shows through). */
fun Modifier.wearEdgeFade(top: Float = 0.16f, bottom: Float = 0.1f): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                top * 0.55f to Color.Black.copy(alpha = 0.35f),
                top to Color.Black,
                1f - bottom to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** A watch list on the ground: side margins for the round screen, the clock's fade at the top, [spacing] between rows. */
@Composable
fun WearList(
    state: ScalingLazyListState,
    padding: PaddingValues,
    spacing: Dp = 4.dp,
    content: ScalingLazyListScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(WearOrbit.Ground)) {
        ScalingLazyColumn(
            state = state,
            contentPadding = wearListPadding(padding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            modifier = Modifier.fillMaxSize().wearEdgeFade(),
            content = content,
        )
    }
}
