package com.cosmotune.video.local

import kotlin.math.max
import kotlin.math.min

/**
 * How a local file's picture is drawn in its space: [FIT] shows all of it, [FILL] covers the space and crops, [STRETCH] fills the
 * space whatever the shape, [R16_9], [R4_3] and [R21_9] force that shape (the picture is stretched to it and fitted in the space),
 * and [ORIGINAL] draws one video pixel on one screen pixel when that fits.
 */
enum class VideoAspect(val ratio: Float?) {
    FIT(null),
    FILL(null),
    STRETCH(null),
    R16_9(16f / 9f),
    R4_3(4f / 3f),
    R21_9(21f / 9f),
    ORIGINAL(null),
}

/** The size, in pixels, a picture is drawn at. It is larger than the space in one direction for [VideoAspect.FILL] (the excess is cropped). */
data class DrawnSize(val width: Float, val height: Float)

/** The arithmetic of [VideoAspect] (pure, shared by both engines). */
object AspectMath {
    /**
     * The size the picture of [videoWidth] x [videoHeight] is drawn at in a [boxWidth] x [boxHeight] space under [aspect]. When the
     * video's size is not known yet the space itself is returned.
     */
    fun drawnSize(aspect: VideoAspect, boxWidth: Float, boxHeight: Float, videoWidth: Float, videoHeight: Float): DrawnSize {
        if (boxWidth <= 0f || boxHeight <= 0f) return DrawnSize(0f, 0f)
        val whole = DrawnSize(boxWidth, boxHeight)
        if (aspect == VideoAspect.STRETCH) return whole
        val forced = aspect.ratio
        if (forced != null) return fitRatio(forced, boxWidth, boxHeight)
        if (videoWidth <= 0f || videoHeight <= 0f) return whole
        val ratio = videoWidth / videoHeight
        return when (aspect) {
            VideoAspect.FIT -> fitRatio(ratio, boxWidth, boxHeight)
            VideoAspect.FILL -> {
                val boxRatio = boxWidth / boxHeight
                if (ratio > boxRatio) DrawnSize(boxHeight * ratio, boxHeight) else DrawnSize(boxWidth, boxWidth / ratio)
            }
            VideoAspect.ORIGINAL ->
                if (videoWidth <= boxWidth && videoHeight <= boxHeight) DrawnSize(videoWidth, videoHeight) else fitRatio(ratio, boxWidth, boxHeight)
            else -> whole
        }
    }

    /** The horizontal and vertical factors that take a picture of [videoWidth] x [videoHeight] to [drawnSize] (1 when unknown). */
    fun scaleFactors(drawn: DrawnSize, videoWidth: Float, videoHeight: Float): DrawnSize =
        if (videoWidth <= 0f || videoHeight <= 0f) DrawnSize(1f, 1f) else DrawnSize(drawn.width / videoWidth, drawn.height / videoHeight)

    /** The largest [ratio]-shaped rectangle that fits the box. */
    fun fitRatio(ratio: Float, boxWidth: Float, boxHeight: Float): DrawnSize {
        if (ratio <= 0f) return DrawnSize(boxWidth, boxHeight)
        val height = min(boxHeight, boxWidth / ratio)
        return DrawnSize(max(0f, height * ratio), max(0f, height))
    }
}
