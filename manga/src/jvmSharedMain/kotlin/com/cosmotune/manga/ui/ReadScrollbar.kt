@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.cosmotune.manga.ui

import androidx.compose.foundation.ScrollIndicatorState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.orbitLook

/** Use Compose's scroll metrics; visible item averages drift around full-span headers and variable chapter rows. */
@Composable
internal fun Modifier.readScrollbar(state: ScrollIndicatorState?, bottomInset: Dp = 0.dp, topInset: Dp = 0.dp): Modifier {
    val color = orbitLook().dim.copy(alpha = 0.40f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return drawWithContent {
        drawContent()
        val metrics = state ?: return@drawWithContent
        val track = (size.height - topInset.toPx() - bottomInset.toPx()).coerceAtLeast(0f)
        val thumb = readScrollbarThumb(metrics.contentSize, metrics.viewportSize, metrics.scrollOffset, track, 24.dp.toPx()) ?: return@drawWithContent
        val thickness = 3.dp.toPx()
        drawRoundRect(color, Offset(if (rtl) 3.dp.toPx() else size.width - 3.dp.toPx() - thickness, topInset.toPx() + thumb.first),
            Size(thickness, thumb.second), CornerRadius(thickness / 2))
    }
}

internal fun readScrollbarThumb(content: Int, viewport: Int, offset: Int, track: Float, minimum: Float): Pair<Float, Float>? {
    if (content <= viewport || viewport <= 0 || track <= 0 || !track.isFinite()) return null
    val length = (track * viewport.toDouble() / content).toFloat().coerceIn(minimum.coerceIn(0f, track), track)
    val fraction = offset.toDouble().coerceIn(0.0, (content - viewport).toDouble()) / (content - viewport)
    return (fraction * (track - length)).toFloat() to length
}
