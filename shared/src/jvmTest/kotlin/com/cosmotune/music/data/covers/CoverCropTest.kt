package com.cosmotune.music.data.covers

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Placing a photo in the circle with drag and pinch, and the square of the photo that is saved. */
class CoverCropTest {
    private val viewport = 300f

    @Test fun atRestTheMiddleSquareOfTheShortSideIsSaved() {
        // A landscape photo, 400 x 200: the circle shows the middle 200 x 200.
        assertEquals(CropSquare(100, 0, 200), CoverCrop.square(CoverPlacement(), 400, 200, viewport))
        // A portrait one, 200 x 400.
        assertEquals(CropSquare(0, 100, 200), CoverCrop.square(CoverPlacement(), 200, 400, viewport))
        // Already square: all of it.
        assertEquals(CropSquare(0, 0, 500), CoverCrop.square(CoverPlacement(), 500, 500, viewport))
    }

    @Test fun draggingRightShowsMoreOfTheLeftOfThePhoto() {
        // 400 x 200 shown at 1.5 viewport px per photo px: 600 x 300 on screen, 150 px to spare on each side.
        val moved = CoverCrop.gesture(CoverPlacement(), 400, 200, viewport, panX = 90f, panY = 0f, zoomBy = 1f, centroidX = 150f, centroidY = 150f)
        assertEquals(90f, moved.offsetX)
        // 90 viewport px is 60 photo px.
        assertEquals(CropSquare(40, 0, 200), CoverCrop.square(moved, 400, 200, viewport))
    }

    @Test fun thePhotoNeverLeavesAGapInTheCircle() {
        val far = CoverCrop.gesture(CoverPlacement(), 400, 200, viewport, panX = 10_000f, panY = -10_000f, zoomBy = 1f, centroidX = 0f, centroidY = 0f)
        assertEquals(150f, far.offsetX, "no further than the photo's own edge")
        assertEquals(0f, far.offsetY, "a photo exactly as tall as the circle cannot move up or down")
        assertEquals(CropSquare(0, 0, 200), CoverCrop.square(far, 400, 200, viewport))
    }

    @Test fun pinchingZoomsInAboutTheFingersAndStaysWithinLimits() {
        val square = 600
        val zoomed = CoverCrop.gesture(CoverPlacement(), square, square, viewport, panX = 0f, panY = 0f, zoomBy = 2f, centroidX = 150f, centroidY = 150f)
        assertEquals(2f, zoomed.zoom)
        assertEquals(CropSquare(150, 150, 300), CoverCrop.square(zoomed, square, square, viewport))

        // About the top-left corner instead: that corner stays where it is.
        val corner = CoverCrop.gesture(CoverPlacement(), square, square, viewport, panX = 0f, panY = 0f, zoomBy = 2f, centroidX = 0f, centroidY = 0f)
        assertEquals(CropSquare(0, 0, 300), CoverCrop.square(corner, square, square, viewport))

        val tooFar = CoverCrop.gesture(zoomed, square, square, viewport, panX = 0f, panY = 0f, zoomBy = 100f, centroidX = 150f, centroidY = 150f)
        assertEquals(CoverCrop.MAX_ZOOM, tooFar.zoom)
        val tooSmall = CoverCrop.gesture(zoomed, square, square, viewport, panX = 0f, panY = 0f, zoomBy = 0.01f, centroidX = 150f, centroidY = 150f)
        assertEquals(1f, tooSmall.zoom)
        assertEquals(CropSquare(0, 0, 600), CoverCrop.square(tooSmall, square, square, viewport))
    }

    @Test fun zoomingOutPullsAnOffsetBackInside() {
        val zoomed = CoverCrop.gesture(CoverPlacement(), 600, 600, viewport, panX = 0f, panY = 0f, zoomBy = 3f, centroidX = 150f, centroidY = 150f)
        val moved = CoverCrop.gesture(zoomed, 600, 600, viewport, panX = 400f, panY = 400f, zoomBy = 1f, centroidX = 150f, centroidY = 150f)
        val back = CoverCrop.gesture(moved, 600, 600, viewport, panX = 0f, panY = 0f, zoomBy = 1f / 3f, centroidX = 150f, centroidY = 150f)
        assertEquals(1f, back.zoom, 0.0001f)
        assertEquals(0f, back.offsetX, 0.001f)
        assertEquals(0f, back.offsetY, 0.001f)
    }

    @Test fun theSavedSquareIsAlwaysInsideThePhoto() {
        var placement = CoverPlacement()
        val moves = listOf(
            floatArrayOf(37f, -12f, 1.7f, 20f, 280f), floatArrayOf(-500f, 90f, 1.1f, 299f, 0f),
            floatArrayOf(13f, 700f, 0.6f, 150f, 150f), floatArrayOf(-3f, -3f, 4f, 10f, 10f),
        )
        for (m in moves) {
            placement = CoverCrop.gesture(placement, 1234, 789, viewport, m[0], m[1], m[2], m[3], m[4])
            val s = CoverCrop.square(placement, 1234, 789, viewport)
            assertTrue(s.left >= 0 && s.top >= 0 && s.side > 0, "$s")
            assertTrue(s.left + s.side <= 1234 && s.top + s.side <= 789, "$s")
        }
    }

    @Test fun aChangeOfViewportKeepsThePlacement() {
        // The same placement read in a circle twice the size frames the same part of the photo.
        val placed = CoverCrop.gesture(CoverPlacement(), 400, 200, viewport, panX = 90f, panY = 0f, zoomBy = 1f, centroidX = 150f, centroidY = 150f)
        val scaled = CoverCrop.rescale(placed, from = viewport, to = viewport * 2)
        assertEquals(CoverCrop.square(placed, 400, 200, viewport), CoverCrop.square(scaled, 400, 200, viewport * 2))
    }
}
