package com.opencanvas.core.filter

import kotlin.math.*

data class VideoPlacement(val left: Float, val top: Float, val width: Float, val height: Float)

/** Shared drawing/texture geometry; no platform may assume a 16:9 source or a 9:16 container. */
fun videoViewport(sourceWidth: Float, sourceHeight: Float, viewWidth: Float, viewHeight: Float,
                  frame: FramingDecision, fitPortrait: Boolean = false, alignPortraitTop: Boolean = false): VideoPlacement {
    if (listOf(sourceWidth, sourceHeight, viewWidth, viewHeight).any { !it.isFinite() || it <= 0 }) return VideoPlacement(0f, 0f, 0f, 0f)
    val portrait = sourceWidth < sourceHeight
    val contain = frame.fitFullFrame || (fitPortrait && portrait)
    val scale = if (contain) min(viewWidth / sourceWidth, viewHeight / sourceHeight) else max(viewWidth / sourceWidth, viewHeight / sourceHeight)
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    val left = if (width <= viewWidth) (viewWidth - width) / 2 else (viewWidth / 2 - frame.cx.coerceIn(0f, 1f) * width).coerceIn(viewWidth - width, 0f)
    val top = if (height <= viewHeight) {
        if (contain && portrait && alignPortraitTop) 0f else (viewHeight - height) / 2
    } else (viewHeight / 2 - frame.cy.coerceIn(0f, 1f) * height).coerceIn(viewHeight - height, 0f)
    return VideoPlacement(left, top, width, height)
}
