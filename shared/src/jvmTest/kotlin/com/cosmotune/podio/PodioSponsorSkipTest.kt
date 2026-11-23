package com.cosmotune.podio

import com.cosmotune.media.segments.SegmentAction
import com.cosmotune.media.segments.SegmentCategory
import com.cosmotune.media.segments.SkipSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PodioSponsorSkipTest {
    private fun segment(start: Long, end: Long, action: SegmentAction = SegmentAction.SKIP) =
        SkipSegment("u$start", SegmentCategory.SPONSOR, action, start, end, null, 0, false, null)

    @Test fun aSponsorIsSkippedToItsEnd() {
        val segments = listOf(segment(60_000, 120_000), segment(300_000, 330_000, SegmentAction.MUTE))
        assertEquals(120_000, PodioSponsorSkip.target(segments, 60_500))
        assertNull(PodioSponsorSkip.target(segments, 59_000))
        assertNull(PodioSponsorSkip.target(segments, 119_500))
        assertNull(PodioSponsorSkip.target(segments, 310_000))
        assertEquals(setOf("sponsor", "selfpromo", "interaction"), PodioSponsorSkip.categories.map { it.apiName }.toSet())
    }
}
