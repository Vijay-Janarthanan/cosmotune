package com.cosmotune.ui.sheets

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitToastPill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalReducedMotion

/** It comes up from this far below. */
private val TOAST_RISE = 16.dp

/** 8 dp above whatever sits under it (the dock). */
private val TOAST_LIFT = 8.dp

/**
 * A brief confirmation ("Added to Evening drive") in Orbit (board batch-a, section 4): an ink pill with ground-coloured words, 16 dp
 * in from the sides and 8 dp above the dock, as wide as its words need, with at most one action at its end, a round [actionIcon]
 * button (Undo, named [actionLabel] for a screen reader) or, for a problem ([alert]: the dark card), a peach [actionLabel] button
 * (Retry). It rises 16 dp into place on [CosmoMotion.move] (a short fade when motion is reduced) and is read out politely as soon as
 * it appears. Its life (4 s, and the next toast replacing it) is the host's.
 */
@Composable
fun CosmoToast(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: (() -> Unit)? = null,
    alert: Boolean = false,
) {
    val reduced = LocalReducedMotion.current
    // 0 while it waits below, 1 in place; read in the layer block, so the rise recomposes nothing.
    val arrival = remember { Animatable(0f) }
    LaunchedEffect(Unit) { arrival.animateTo(1f, CosmoMotion.move(reduced)) }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin)
            .padding(bottom = TOAST_LIFT)
            .graphicsLayer {
                val progress = arrival.value
                alpha = progress.coerceIn(0f, 1f)
                translationY = if (reduced) 0f else (1f - progress) * TOAST_RISE.toPx()
            }
            .semantics { liveRegion = LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        OrbitToastPill(message, orbitFonts(), orbitLook(), alert = alert, actionLabel = actionLabel, actionIcon = actionIcon, onAction = onAction)
    }
}
