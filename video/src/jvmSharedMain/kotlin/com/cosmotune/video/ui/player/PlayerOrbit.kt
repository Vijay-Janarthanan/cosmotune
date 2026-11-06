package com.cosmotune.video.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitRoundButton

/**
 * The player's one round button. Nothing sits on the picture except the essential controls, and those are Orbit round buttons
 * ([OrbitRoundButton]): white ink at 12 % over a 45 % black disc so the glyph reads on any frame (the player's own black and white are
 * the documented exception to the page's ink), solid white with a dark glyph while [on]. A button is 40 dp or larger; a smaller
 * [size] still reserves the 40 dp finger target round it.
 */
@Composable
internal fun PlayerButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    on: Boolean? = null,
    enabled: Boolean = true,
) {
    Box(modifier.clip(CircleShape).background(Color.Black.copy(alpha = BACKING_ALPHA))) {
        OrbitRoundButton(icon, contentDescription, Color.White, onClick, size = size, on = on == true, enabled = enabled)
    }
}

private const val BACKING_ALPHA = 0.45f
