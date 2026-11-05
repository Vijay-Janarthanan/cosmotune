package com.opencanvas.core.filter

/**
 * Mathematical helper for calculating 9:16 vertical crop bounds inside widescreen video.
 */
object CropKinematics {

    /**
     * Calculates the normalized width [0.0f, 1.0f] of the 9:16 viewport inside a source video.
     * @param sourceAspect Video aspect ratio (e.g., 16f / 9f = 1.777f).
     * @param targetAspect Target Canvas aspect ratio (9f / 16f = 0.5625f).
     */
    fun normalizedCropWidth(sourceAspect: Float = 16f / 9f, targetAspect: Float = 9f / 16f): Float {
        // Crop width in normalized source space = targetAspect / sourceAspect
        return (targetAspect / sourceAspect).coerceIn(0.1f, 1.0f)
    }

    /**
     * Clamps the focal center X coordinate [0.0f, 1.0f] so the vertical crop never shows letterbox borders.
     */
    fun clampFocalCenter(
        rawCenterX: Float,
        sourceAspect: Float = 16f / 9f,
        targetAspect: Float = 9f / 16f,
    ): Float {
        val cropWidth = normalizedCropWidth(sourceAspect, targetAspect)
        val halfCrop = cropWidth / 2f
        return rawCenterX.coerceIn(halfCrop, 1.0f - halfCrop)
    }

    /**
     * Computes the exact pixel translation offset to center the focal subject within a container.
     */
    fun computeTranslationOffset(
        clampedCenterX: Float,
        containerWidth: Float,
        containerHeight: Float,
        videoWidth: Float,
        videoHeight: Float,
    ): Float {
        if (videoHeight <= 0f || containerHeight <= 0f) return 0f
        // Uniform fill height
        val scale = containerHeight / videoHeight
        val scaledVideoWidth = videoWidth * scale
        val focalPixelX = clampedCenterX * scaledVideoWidth
        val containerCenterX = containerWidth / 2f

        return -(focalPixelX - containerCenterX)
    }
}
