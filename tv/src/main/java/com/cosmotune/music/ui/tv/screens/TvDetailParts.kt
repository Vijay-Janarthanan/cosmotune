/*
 * Source: FORK.md companion source record TV-0027
 * Adaptations recorded in tv/UPSTREAM_IMPORT.json; authors credited in NOTICE.md.
 */
package com.cosmotune.music.ui.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.cosmotune.music.ui.icons.CosmotuneIcons
import com.cosmotune.music.ui.tv.components.tvFocusRing
import com.cosmotune.music.ui.tv.components.tvInitialFocus
import com.cosmotune.music.ui.tv.theme.TvGlass
import com.cosmotune.music.ui.tv.theme.tvOrbitLook
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitRoundButton

/**
 * The page's actions: Shuffle as a round tonal button, Play as the peach play
 * button (Orbit rule 5) and, on an artist, a star to subscribe. Focus is the
 * peach ring round whichever has it.
 *
 * [onFocused] fires as focus arrives in the row, so the page can scroll home:
 * the row sits at the top of the page, and the page should be at its top too.
 */
@Composable
internal fun TvActionRow(
    canPlay: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    subscribed: Boolean? = null,
    onToggleSubscription: () -> Unit = {},
    onFocused: () -> Unit = {},
) {
    Row(
        modifier = modifier.onFocusChanged { if (it.hasFocus) onFocused() },
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OrbitPlayButton(
            playing = false,
            onClick = { if (canPlay) onPlay() },
            look = tvOrbitLook(),
            size = 58.dp,
            modifier = Modifier
                .tvInitialFocus(canPlay)
                .tvFocusRing(CircleShape)
                .graphicsLayer { alpha = if (canPlay) 1f else 0.4f },
        )
        TvShuffleButton(enabled = canPlay, onClick = onShuffle)
        if (subscribed != null) {
            OrbitRoundButton(
                icon = if (subscribed) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                label = if (subscribed) "Unsubscribe" else "Subscribe",
                ink = TvGlass.Ink,
                onClick = onToggleSubscription,
                size = 44.dp,
                modifier = Modifier.tvFocusRing(CircleShape),
            )
        }
    }
}

/** Shuffle as the kit's round tonal button, ringed in peach on focus. */
@Composable
internal fun TvShuffleButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OrbitRoundButton(
        icon = CosmotuneIcons.Shuffle,
        label = "Shuffle",
        ink = TvGlass.Ink,
        onClick = { if (enabled) onClick() },
        size = 44.dp,
        modifier = modifier
            .tvFocusRing(CircleShape)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
    )
}
