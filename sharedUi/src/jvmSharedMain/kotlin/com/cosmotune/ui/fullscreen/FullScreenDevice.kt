package com.cosmotune.ui.fullscreen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoGlass

/** A level the full-screen player's drags change, 0..1: what it is now, and setting it. */
class FullScreenLevel(val get: () -> Float, val set: (Float) -> Unit)

/**
 * What a vertical drag on the full-screen player changes on this device: the screen's [brightness] (the left half) and the
 * [volume] (the right half). Either may be null where the device has none.
 */
@Stable
class FullScreenDevice(val brightness: FullScreenLevel?, val volume: FullScreenLevel?)

/**
 * The device's brightness and volume for the full-screen player's drags, or null where there are no such drags (the desktop:
 * no touch screen, no brightness). Android: the brightness of the window the player is drawn in (given back to the system's when
 * the player leaves) and the music stream's volume.
 */
@Composable
expect fun rememberFullScreenDevice(): FullScreenDevice?

/** The playback speed for the long press's 2x: what it is now, and setting it. Null where it cannot change (a live stream). */
class FullScreenSpeed(val get: () -> Float, val set: (Float) -> Unit)

/** The speed a long press holds while the finger stays down. */
const val FULL_SCREEN_HOLD_SPEED = 2f

/** Which level a vertical drag changes. */
enum class LevelKind { BRIGHTNESS, VOLUME }

/**
 * The brightness or volume meter shown while a drag changes it (a glass meter 36 x 160, radius 18, over the video): the level
 * filled from the bottom in white, its icon at the foot. The level is read while drawing. Video's player box uses it too.
 */
@Composable
fun LevelMeter(
    kind: LevelKind, level: FloatState, modifier: Modifier = Modifier,
    label: String = if (kind == LevelKind.BRIGHTNESS) "Brightness" else "Volume",
    width: androidx.compose.ui.unit.Dp = 36.dp, height: androidx.compose.ui.unit.Dp = 160.dp,
) {
    val shape = RoundedCornerShape(width / 2)
    Box(
        modifier.size(width, height)
            .semantics { contentDescription = label }
            .cosmoGlass(hazeState = null, shape = shape, overVideo = true)
            .drawBehind {
                val inset = 8.dp.toPx()
                val trackTop = inset
                val trackBottom = size.height - inset - 20.dp.toPx() - inset
                val trackH = (trackBottom - trackTop).coerceAtLeast(0f)
                val w = size.width - 2 * inset
                val corner = CornerRadius(w / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(inset, trackTop), Size(w, trackH), corner)
                val filled = trackH * level.floatValue.coerceIn(0f, 1f)
                if (filled > 0f) drawRoundRect(Color.White, Offset(inset, trackBottom - filled), Size(w, filled), corner)
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Icon(
            if (kind == LevelKind.BRIGHTNESS) CosmoIcons.Brightness else CosmoIcons.Volume, contentDescription = null, tint = Color.White,
            modifier = Modifier.padding(bottom = 8.dp).size(20.dp),
        )
    }
}
