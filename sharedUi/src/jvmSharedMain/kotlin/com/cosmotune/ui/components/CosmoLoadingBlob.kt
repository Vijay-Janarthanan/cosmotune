package com.cosmotune.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion

/** Material's `CircularIndicatorDiameter`: the size of a spinner whose caller gave it none. */
private val DEFAULT_DIAMETER = 40.dp

/**
 * The B loading blob as a spinner: a blob that becomes a circle and back, turning once, every 1.1 s (`@keyframes blob` in
 * `components-v3bc.css`, drawn by [LoadingBlobShape]). It is what `CircularProgressIndicator` becomes in the new look.
 *
 * It fills the size [modifier] gives it, 40 dp when that carries none (the same rule as Material's spinner: the caller's size
 * comes first and wins). [color] is the blob's colour, the cover's `accent` while it is [Color.Unspecified]. Reduced motion
 * shows a still circle. For a screen reader it is an indeterminate progress bar, as Material's spinner is.
 */
@Composable
fun CosmoLoadingBlob(modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val tint = if (color.isSpecified) color else Color(LocalCosmoColors.current.palette.accent)
    val reduced = LocalReducedMotion.current
    BoxWithConstraints(
        modifier
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
            .size(DEFAULT_DIAMETER),
        contentAlignment = Alignment.Center,
    ) {
        // the constraints are exact here (size() above), so this is the side the blob fills
        LoadingBlobShape(min(maxWidth, maxHeight).coerceAtLeast(0.dp), tint, reduced)
    }
}
