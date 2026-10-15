package com.cosmotune.ui.bridge

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoShell

/**
 * Material's indeterminate `CircularProgressIndicator` (material3 1.3.x: the overload with `trackColor` and `strokeCap`), with
 * the same names, order and defaults. With the new look off it is Material's own. With it on it is the B loading blob
 * ([CosmoLoadingBlob]): it fills the size [modifier] gives it (40 dp when none), in [color] unless that is Material's default
 * spinner colour, then in the cover's `accent`. [strokeWidth], [trackColor] and [strokeCap] describe a ring and are ignored.
 */
@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    trackColor: Color = ProgressIndicatorDefaults.circularIndeterminateTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.CircularIndeterminateStrokeCap,
) {
    if (LocalCosmoShell.current == null) {
        androidx.compose.material3.CircularProgressIndicator(
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
            trackColor = trackColor,
            strokeCap = strokeCap,
        )
        return
    }
    CosmoLoadingBlob(modifier, if (color == ProgressIndicatorDefaults.circularColor) Color(LocalCosmoColors.current.palette.accent) else color)
}

/**
 * Material's determinate `CircularProgressIndicator` (the overload with `gapSize`), same names, order and defaults. With the
 * new look on it is Material's ring with the palette's colours: [color] unless that is Material's default ring colour, then
 * the cover's `accent`; [trackColor] unless that is Material's default track colour, then the cover's `track`.
 */
@OptIn(ExperimentalMaterial3Api::class) // only Material's default gap size is marked experimental; the call it forwards to is not
@Composable
fun CircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    trackColor: Color = ProgressIndicatorDefaults.circularDeterminateTrackColor,
    strokeCap: StrokeCap = ProgressIndicatorDefaults.CircularDeterminateStrokeCap,
    gapSize: Dp = ProgressIndicatorDefaults.CircularIndicatorTrackGapSize,
) {
    if (LocalCosmoShell.current == null) {
        androidx.compose.material3.CircularProgressIndicator(
            progress = progress,
            modifier = modifier,
            color = color,
            strokeWidth = strokeWidth,
            trackColor = trackColor,
            strokeCap = strokeCap,
            gapSize = gapSize,
        )
        return
    }
    val palette = LocalCosmoColors.current.palette
    androidx.compose.material3.CircularProgressIndicator(
        progress = progress,
        modifier = modifier,
        color = if (color == ProgressIndicatorDefaults.circularColor) Color(palette.accent) else color,
        strokeWidth = strokeWidth,
        trackColor = if (trackColor == ProgressIndicatorDefaults.circularDeterminateTrackColor) Color(palette.track) else trackColor,
        strokeCap = strokeCap,
        gapSize = gapSize,
    )
}
