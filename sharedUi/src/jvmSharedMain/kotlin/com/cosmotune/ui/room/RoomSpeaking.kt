package com.cosmotune.ui.room

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.account.AccountAvatar
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion

/**
 * A member's face with the speaking ring (design section 7): a 2.5 dp `accent` ring over the avatar's edge while [speaking], so the
 * face does not move or change size when someone starts talking. The ring breathes softly between 60 and 100 % opacity; under reduced
 * motion it stands still at full. [ground] is the optional ring in the ground colour the header strip draws round each face, so the
 * overlapping edges read; the speaking ring is drawn over it.
 *
 * Decorative for a screen reader: the People row says "Speaking" in words, the strip and the chat name the person already.
 */
@Composable
internal fun RoomAvatar(url: String?, sizeDp: Int, uid: String, speaking: Boolean, modifier: Modifier = Modifier, ground: Color? = null) {
    Box(modifier.size(sizeDp.dp)) {
        AccountAvatar(url, sizeDp, if (ground != null) Modifier.border(2.dp, ground, CircleShape) else Modifier)
        if (speaking) {
            val ringModifier = Modifier.size(sizeDp.dp).testTag(RoomTags.speaking(uid))
            if (LocalReducedMotion.current) StillRing(ringModifier) else BreathingRing(ringModifier)
        }
    }
}

@Composable
private fun StillRing(modifier: Modifier) {
    val accent = Color(LocalCosmoColors.current.palette.accent)
    Box(modifier.drawBehind { ring(accent, 1f) })
}

/** The moving ring. Its opacity is read while drawing, so nothing recomposes per frame, and it only runs while someone speaks. */
@Composable
private fun BreathingRing(modifier: Modifier) {
    val accent = Color(LocalCosmoColors.current.palette.accent)
    val pulse = rememberInfiniteTransition(label = "speaking").animateFloat(
        initialValue = 1f,
        targetValue = RING_LOW_ALPHA,
        animationSpec = infiniteRepeatable(tween(RING_PERIOD_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "speaking ring",
    )
    Box(modifier.drawBehind { ring(accent, pulse.value) })
}

private fun DrawScope.ring(color: Color, alpha: Float) {
    val width = RING_DP.dp.toPx()
    drawCircle(color, radius = size.minDimension / 2f - width / 2f, alpha = alpha, style = Stroke(width))
}

private const val RING_DP = 2.5f
private const val RING_LOW_ALPHA = 0.6f
private const val RING_PERIOD_MS = 700
