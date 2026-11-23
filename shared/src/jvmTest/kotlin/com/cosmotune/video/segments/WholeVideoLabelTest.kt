package com.cosmotune.video.segments

import com.cosmotune.media.segments.*

import com.cosmotune.video.settings.SegmentBehaviour
import com.cosmotune.video.settings.VideoSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WholeVideoLabelTest {
    private fun seg(category: SegmentCategory, action: SegmentAction) = SkipSegment("u-$category-$action", category, action, 0, 0, null, 0, false, null)

    @Test fun onlyAFullSegmentLabelsTheVideo() {
        val all = WholeVideoLabel.ORDER.toSet()
        assertNull(WholeVideoLabel.of(listOf(seg(SegmentCategory.SPONSOR, SegmentAction.SKIP)), all))
        assertEquals(SegmentCategory.SELF_PROMO, WholeVideoLabel.of(listOf(seg(SegmentCategory.SELF_PROMO, SegmentAction.FULL)), all))
    }

    @Test fun theStrongestLabelWins() {
        val both = listOf(seg(SegmentCategory.SELF_PROMO, SegmentAction.FULL), seg(SegmentCategory.SPONSOR, SegmentAction.FULL))
        assertEquals(SegmentCategory.SPONSOR, WholeVideoLabel.of(both, WholeVideoLabel.ORDER.toSet()))
        assertEquals(SegmentCategory.SELF_PROMO, WholeVideoLabel.of(both, setOf(SegmentCategory.SELF_PROMO)))
    }

    @Test fun theSettingsDecideWhatIsAskedFor() {
        assertEquals(WholeVideoLabel.ORDER.toSet(), WholeVideoLabel.categories(VideoSettings()))
        assertEquals(emptySet(), WholeVideoLabel.categories(VideoSettings(sponsorBlock = false)))
        assertEquals(emptySet(), WholeVideoLabel.categories(VideoSettings(wholeVideoLabels = false)))
        val noSponsor = VideoSettings(segmentBehaviours = SegmentCategory.defaults() + (SegmentCategory.SPONSOR to SegmentBehaviour.OFF))
        assertEquals(setOf(SegmentCategory.EXCLUSIVE_ACCESS, SegmentCategory.SELF_PROMO), WholeVideoLabel.categories(noSponsor))
    }
}
