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
import com.cosmotune.ui.orbit.OrbitFace
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.orbit.orbitTalking
import com.cosmotune.ui.theme.LocalReducedMotion

/**
 * A member's face with the speaking ring in Orbit (board batch-a, section 3; rule 2, the orbit is also who speaks): their picture as a
 * disc ([OrbitFace], their own colour and initial without one) and, while [speaking], the green orbit round it, a gap of the ground
 * and then the ring, drawn outside the face so it does not move or change size when someone starts talking. The ring breathes softly
 * between 60 and 100 % opacity; under reduced motion it stands still at full. [ground] is the optional ring in the ground colour the
 * overlapping faces draw round each other so their edges read.
 *
 * Decorative for a screen reader: the People row says "Speaking" in words, the strip and the chat name the person already.
 */
@Composable
internal fun RoomAvatar(url: String?, sizeDp: Int, uid: String, speaking: Boolean, modifier: Modifier = Modifier, ground: Color? = null, name: String = "") {
    Box(modifier.size(sizeDp.dp)) {
        OrbitFace(url, name, uid, sizeDp.dp, if (ground != null) Modifier.border(2.dp, ground, CircleShape) else Modifier)
        if (speaking) {
            val ringModifier = Modifier.size(sizeDp.dp).testTag(RoomTags.speaking(uid))
            if (LocalReducedMotion.current) StillRing(ringModifier, sizeDp) else BreathingRing(ringModifier, sizeDp)
        }
    }
}

@Composable
private fun StillRing(modifier: Modifier, sizeDp: Int) {
    val green = orbitTalking(orbitLook())
    Box(modifier.drawBehind { ring(green, 1f, sizeDp) })
}

/** The moving ring. Its opacity is read while drawing, so nothing recomposes per frame, and it only runs while someone speaks. */
@Composable
private fun BreathingRing(modifier: Modifier, sizeDp: Int) {
    val green = orbitTalking(orbitLook())
    val pulse = rememberInfiniteTransition(label = "speaking").animateFloat(
        initialValue = 1f,
        targetValue = RING_LOW_ALPHA,
        animationSpec = infiniteRepeatable(tween(RING_PERIOD_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "speaking ring",
    )
    Box(modifier.drawBehind { ring(green, pulse.value, sizeDp) })
}

/** The ring outside the face: a gap and a stroke that grow with the face (4 and 3 dp round a 72 dp face, 2 and 2 round a 32 dp one). */
private fun DrawScope.ring(color: Color, alpha: Float, sizeDp: Int) {
    val gap = (sizeDp * 0.055f).coerceIn(2f, 4f).dp.toPx()
    val width = (sizeDp * 0.042f).coerceIn(2f, 3f).dp.toPx()
    drawCircle(color, radius = size.minDimension / 2f + gap + width / 2f, alpha = alpha, style = Stroke(width))
}

private const val RING_LOW_ALPHA = 0.6f
private const val RING_PERIOD_MS = 700
