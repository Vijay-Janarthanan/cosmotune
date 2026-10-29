package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics

/*
 * The chip of the video feeds: a pill 34 dp tall. At rest it is a wash of the page's ink over the ground (a light grey on the light
 * theme, the near-black grey of the dark one); chosen, it is inverted: the ink as the fill and the ground as the label.
 */

private val PILL_HEIGHT = 34.dp
private val PILL_PADDING = 14.dp
private const val PILL_SP = 14f
private const val PILL_WEIGHT = 600

/** The ink's wash at rest, and under a press. */
private val REST_WASH = 0.10f
private val PRESS_WASH = 0.16f

/** A pill of the video feeds with [label]; [selected] inverts it. A tap calls [onClick]; [haptic] false plays nothing (the chosen chip's own tap). */
@Composable
internal fun PillChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, haptic: Boolean = true) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed = interaction.collectIsPressedAsState()
    val style = remember(type) {
        type.label.copy(fontSize = PILL_SP.sp, fontWeight = FontWeight(PILL_WEIGHT), letterSpacing = 0.sp)
    }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .height(PILL_HEIGHT)
            .cosmoFocusRing(interaction, PILL_HEIGHT / 2)
            .clip(CircleShape)
            .drawBehind {
                val ink = colors.live(CoverField.TEXT_PRIMARY)
                if (selected) {
                    drawRect(ink)
                } else {
                    drawRect(ink.copy(alpha = if (pressed.value) PRESS_WASH else REST_WASH))
                }
            }
            .semantics { this.selected = selected }
            .clickable(interaction, indication = null, role = Role.Tab) {
                if (haptic) haptics.play(Haptic.Tap)
                onClick()
            }
            .padding(horizontal = PILL_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = style,
            color = Color(if (selected) palette.backgroundTop else palette.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
