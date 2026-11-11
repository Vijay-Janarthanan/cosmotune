package com.cosmotune.ui.watch

import com.cosmotune.design.VideoView
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The shape Watch's picture-in-picture is given and where the picture sits: the video's own display size (never the page's box),
 * the picture centred in its box, and a new surface whenever its frame really moves (a SurfaceView left where it was first laid
 * out showed a 16:9 live channel off to the right and too large in picture-in-picture and after it).
 */
class WatchPipAspectTest {
    @Test fun thePipShapeIsTheVideosOwnDisplaySize() {
        // A 960 x 540 stream whose pixels the engine reports at 32:27 (1138 x 540 shown): that is the shape asked for, as drawn.
        assertEquals(1138 to 540, watchPipAspect(1138, 540))
        assertEquals(1920 to 1080, watchPipAspect(1920, 1080))
        assertEquals(16 to 9, watchPipAspect(0, 0), "16:9 until the size is known")
        assertEquals(16 to 9, watchPipAspect(1280, 0))
    }

    @Test fun theWishCarriesThatShapeAndNotTheBoxItWasDrawnIn() {
        val wish = watchPipWishShape(videoWidth = 960, videoHeight = 720)
        assertEquals(960 to 720, wish, "a 4:3 video asks for a 4:3 window")
    }

    @Test fun aWholePictureIsCentredInItsBox() {
        // A 4:3 picture in the phone's 16:9 frame: as tall as the frame, with equal bars left and right.
        val frame = watchPictureFrame(VideoView.WHOLE, 360f, 202.5f, 4f / 3f)
        assertEquals(202.5f, frame[3], 0.01f)
        assertEquals(270f, frame[2], 0.01f)
        assertEquals(frame[0], 360f - frame[0] - frame[2], 0.01f, "the same bar on both sides")
        assertEquals(0f, frame[1], 0.01f)
        // the same picture in a picture-in-picture window of its own shape fills it
        assertContentEquals(floatArrayOf(0f, 0f, 240f, 180f), watchPictureFrame(VideoView.WHOLE, 240f, 180f, 4f / 3f))
        assertContentEquals(floatArrayOf(0f, 0f, 360f, 202.5f), watchPictureFrame(VideoView.FILL, 360f, 202.5f, 4f / 3f), "Fill: the whole box")
    }

    @Test fun theSurfaceIsMadeAnewOnlyWhenItsFrameReallyChanges() {
        val page = surfaceFrameKey(watchPictureFrame(VideoView.WHOLE, 360f, 202f, 16f / 9f))
        assertEquals(page, surfaceFrameKey(watchPictureFrame(VideoView.WHOLE, 360.2f, 202.2f, 16f / 9f)), "a sub-dp change keeps it")
        assertNotEquals(page, surfaceFrameKey(watchPictureFrame(VideoView.WHOLE, 360f, 202f, 1138f / 540f)), "the shape becoming known remakes it")
        assertNotEquals(page, surfaceFrameKey(watchPictureFrame(VideoView.WHOLE, 240f, 135f, 16f / 9f)), "the picture-in-picture window remakes it")
    }

    private fun watchPipWishShape(videoWidth: Int, videoHeight: Int): Pair<Int, Int> = watchPipAspect(videoWidth, videoHeight)
}
