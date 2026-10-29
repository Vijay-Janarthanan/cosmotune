package com.cosmotune.video.local

import kotlin.test.Test
import kotlin.test.assertEquals

/** The picture's size under each aspect choice. */
class VideoAspectTest {

    private fun size(aspect: VideoAspect, boxW: Float, boxH: Float, videoW: Float = 1920f, videoH: Float = 1080f) =
        AspectMath.drawnSize(aspect, boxW, boxH, videoW, videoH)

    private fun assertSize(expected: DrawnSize, actual: DrawnSize) {
        assertEquals(expected.width, actual.width, 0.5f, "width")
        assertEquals(expected.height, actual.height, 0.5f, "height")
    }

    @Test
    fun fitShowsAllOfThePictureWithBarsOnTwoSides() {
        assertSize(DrawnSize(800f, 450f), size(VideoAspect.FIT, 800f, 600f))
        assertSize(DrawnSize(400f, 225f), size(VideoAspect.FIT, 1000f, 225f))
    }

    @Test
    fun fillCoversTheSpaceAndOverflowsOneDirection() {
        assertSize(DrawnSize(1066.7f, 600f), size(VideoAspect.FILL, 800f, 600f))
        assertSize(DrawnSize(1000f, 562.5f), size(VideoAspect.FILL, 1000f, 500f))
    }

    @Test
    fun stretchIsTheWholeSpace() {
        assertSize(DrawnSize(800f, 600f), size(VideoAspect.STRETCH, 800f, 600f))
    }

    @Test
    fun aForcedShapeFitsTheSpaceWhateverTheVideosShape() {
        assertSize(DrawnSize(800f, 600f), size(VideoAspect.R4_3, 800f, 600f))
        assertSize(DrawnSize(800f, 342.9f), size(VideoAspect.R21_9, 800f, 600f))
        assertSize(DrawnSize(800f, 450f), size(VideoAspect.R16_9, 800f, 600f, videoW = 640f, videoH = 480f))
        assertSize(DrawnSize(300f, 225f), size(VideoAspect.R4_3, 1000f, 225f))
    }

    @Test
    fun originalIsOnePixelToOnePixelWhenItFitsAndFitOtherwise() {
        assertSize(DrawnSize(640f, 360f), size(VideoAspect.ORIGINAL, 800f, 600f, videoW = 640f, videoH = 360f))
        assertSize(DrawnSize(800f, 450f), size(VideoAspect.ORIGINAL, 800f, 600f))
    }

    @Test
    fun anUnknownVideoSizeDrawsTheWholeSpaceExceptForAForcedShape() {
        assertSize(DrawnSize(800f, 600f), size(VideoAspect.FIT, 800f, 600f, 0f, 0f))
        assertSize(DrawnSize(800f, 600f), size(VideoAspect.FILL, 800f, 600f, 0f, 0f))
        assertSize(DrawnSize(800f, 450f), size(VideoAspect.R16_9, 800f, 600f, 0f, 0f))
    }

    @Test
    fun anEmptySpaceDrawsNothing() {
        assertSize(DrawnSize(0f, 0f), size(VideoAspect.FIT, 0f, 600f))
    }

    @Test
    fun theFactorsTakeTheVideoToTheDrawnSize() {
        val factors = AspectMath.scaleFactors(DrawnSize(960f, 540f), 1920f, 1080f)
        assertEquals(0.5f, factors.width)
        assertEquals(0.5f, factors.height)
        assertEquals(1f, AspectMath.scaleFactors(DrawnSize(10f, 10f), 0f, 0f).width)
    }
}
