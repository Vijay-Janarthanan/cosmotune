package com.cosmotune.music.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cosmotune.music.ui.utils.containSheetGestures
import com.cosmotune.ui.orbit.OrbitPopupCard
import dev.chrisbanes.haze.HazeState

/**
 * The card the output drawer's popups open into — the one [AudioPipelineDialog]
 * is drawn in, lifted out so the Cast picker is the same object rather than a
 * second one made to look like it.
 *
 * An Orbit dialog ([OrbitPopupCard]): a scrim that dismisses, a flat card that
 * swallows the tap so touching it does not, the title as its question and the
 * subtitle under it, a scrolling body, and Done as the one peach action. It
 * follows the player's light or dark colours; [hazeState] is kept for the
 * callers and blurs nothing (Orbit rule 4). The gestures stay inside it, so a
 * drag on the card does not pull the player down behind it.
 *
 * [content] is the scrolling body, handed a [BoxScope] so a body can lay
 * something behind its rows — the pipeline draws its signal line there.
 */
@Composable
internal fun AudioPopupCard(
    @Suppress("UNUSED_PARAMETER") hazeState: HazeState,
    title: String,
    subtitle: String,
    doneLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    OrbitPopupCard(
        title = title,
        subtitle = subtitle,
        doneLabel = doneLabel,
        onDismiss = onDismiss,
        modifier = modifier.containSheetGestures(),
        content = content,
    )
}
