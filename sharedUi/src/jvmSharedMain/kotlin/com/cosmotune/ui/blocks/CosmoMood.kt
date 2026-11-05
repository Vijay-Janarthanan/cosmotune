package com.cosmotune.ui.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cosmotune.design.BlockMetrics
import com.cosmotune.design.LabelFit
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.components.FitLabel
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics

/**
 * A mood tile of the Explore page (DESIGN_SYSTEM.md 4.9): flat `surface1`, 12 dp corners, 92 dp tall (116 with [big]), the label at the top
 * left, shrunk a step at a time down to 12 sp until it fits on whole words (a name of one word on its one line, a longer one on two lines
 * broken at spaces: [LabelFit]), and a square cover of the mood flush with the bottom right corner, untilted. It fills the width it is
 * given; the page lays the tiles out ([BlockMetrics.moodRows]).
 */
@Composable
fun CosmoMoodTile(
    label: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = false,
) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val haptics = rememberHaptics()
    val labelSize = if (big) 20.sp else 16.sp
    Box(
        modifier
            .fillMaxWidth()
            .height(BlockMetrics.moodTileHeightDp(big).dp)
            .clip(RoundedCornerShape(CosmoRadii.rMd))
            .background(Color(palette.surface))
            .clickable(role = Role.Button) { haptics.play(Haptic.Select); onClick() },
    ) {
        CoverImage(
            url = coverUrl,
            size = if (big) 72.dp else 56.dp,
            radius = CosmoRadii.r0,
            modifier = Modifier.align(Alignment.BottomEnd),
            shadow = false,
        )
        // The line height is a ratio (em), so it follows the label when it shrinks.
        val style = type.forText(
            TextStyle(fontFamily = type.textFamily, fontWeight = type.titleMedium.fontWeight, fontSize = labelSize, lineHeight = 1.2.em, color = Color(palette.textPrimary)),
            label,
        ).let { if (it.lineHeight.isSp) it.copy(lineHeight = (it.lineHeight.value / labelSize.value).em) else it }
        BasicText(
            text = label,
            style = style,
            maxLines = LabelFit.maxLines(label),
            overflow = TextOverflow.Ellipsis,
            autoSize = if (big) BIG_LABEL_FIT else LABEL_FIT,
            modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

/** A mood tile's name at 16 sp (20 in a big tile), shrunk a step at a time down to 12 sp until it fits on whole words. */
private val LABEL_FIT = FitLabel(16f, BlockMetrics.MOOD_LABEL_MIN_SP, BlockMetrics.MOOD_LABEL_STEP_SP, wholeWords = true)
private val BIG_LABEL_FIT = FitLabel(20f, BlockMetrics.MOOD_LABEL_MIN_SP, BlockMetrics.MOOD_LABEL_STEP_SP, wholeWords = true)
