package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals

class VideoViewTest {
    private fun assertFrame(expected: List<Float>, actual: FloatArray) =
        expected.zip(actual.toList()).forEach { (e, a) -> assertEquals(e, a, 0.01f) }

    @Test fun aWideVideoInAWideBoxFillsItsWidthAndIsCentredInItsHeight() {
        assertFrame(listOf(0f, 25f, 160f, 90f), VideoView.whole(160f, 140f, 16f / 9f))
    }

    @Test fun anUprightVideoIsNeverCutItKeepsTheHeightAndIsCentredAcross() {
        assertFrame(listOf(111.25f, 0f, 337.5f, 600f), VideoView.whole(560f, 600f, 9f / 16f))
    }

    @Test fun anUnknownShapeGivesTheWholeBox() {
        assertFrame(listOf(0f, 0f, 300f, 200f), VideoView.whole(300f, 200f, 0f))
        assertFrame(listOf(0f, 0f, 300f, 200f), VideoView.whole(300f, 200f, Float.NaN))
    }

    @Test fun onlyFillIsKeptAsFill() {
        assertEquals(VideoView.FILL, VideoView.kept(VideoView.FILL.ordinal))
        for (v in listOf(VideoView.WHOLE, VideoView.CINEMA, VideoView.MINI)) assertEquals(VideoView.WHOLE, VideoView.kept(v.ordinal))
        assertEquals(VideoView.WHOLE, VideoView.kept(42))
    }
}
