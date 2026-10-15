package com.cosmotune.ui.components

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.theme.LocalCosmoColors

/**
 * Visible keyboard focus: while [interaction] is focused, draws the content, then a 2 dp gap in `backgroundBottom` and a
 * ring around it, outside the bounds, with the corner radius [radius] grown by the gap and half the ring.
 *
 * The ring is the B recipe of the B+C system (`--focus-ring` in `tokens-v3bc.css`): `accentStrong`, 3 dp thick, for
 * controls that are part of the page. Controls that float over content or video ([floating]) take the C recipe: `textPrimary`
 * ink, 2 dp thick. Both rails are the engine's, so the ring keeps its contrast on every cover.
 */
@Composable
fun Modifier.cosmoFocusRing(interaction: InteractionSource, radius: Dp, floating: Boolean = false): Modifier {
    val focused = interaction.collectIsFocusedAsState()
    val colors = LocalCosmoColors.current
    return drawWithContent {
        drawContent()
        if (focused.value) {
            val gap = FOCUS_GAP.toPx()
            val thickness = (if (floating) FOCUS_THICKNESS_C else FOCUS_THICKNESS_B).toPx()
            val ink = colors.live(if (floating) CoverField.TEXT_PRIMARY else CoverField.ACCENT_STRONG)
            drawRingOutside(0f, gap, radius.toPx(), colors.live(CoverField.BACKGROUND_BOTTOM))
            drawRingOutside(gap, gap + thickness, radius.toPx(), ink)
        }
    }
}

internal val FOCUS_GAP = 2.dp
internal val FOCUS_THICKNESS_B = 3.dp
internal val FOCUS_THICKNESS_C = 2.dp

/** A rounded ring that spans [from] to [to] pixels outside the bounds of the drawn box. */
private fun DrawScope.drawRingOutside(from: Float, to: Float, radius: Float, color: Color) {
    val centre = (from + to) / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(-centre, -centre),
        size = Size(size.width + 2 * centre, size.height + 2 * centre),
        cornerRadius = CornerRadius(radius + centre),
        style = Stroke(width = to - from),
    )
}
