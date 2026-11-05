package com.cosmotune.video.segments

import com.cosmotune.video.settings.SegmentBehaviour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure clean-up of a video's SponsorBlock segments (spec 7.1: "Policy (pure)"). */
class SegmentMathTest {

    private fun seg(
        uuid: String,
        startMs: Long,
        endMs: Long,
        category: SegmentCategory = SegmentCategory.SPONSOR,
        action: SegmentAction = SegmentAction.SKIP,
        videoDurationMs: Long? = null,
        votes: Int = 0,
    ) = SkipSegment(uuid, category, action, startMs, endMs, videoDurationMs, votes, false, null)

    private fun ids(list: List<SkipSegment>) = list.map { it.uuid }

    // ---- the length rule ----

    @Test
    fun aSegmentShorterThanOneSecondIsDropped() {
        val out = SegmentMath.prepare(
            listOf(seg("short", 10_000, 10_999), seg("exact", 20_000, 21_000), seg("zero", 30_000, 30_000), seg("backwards", 50_000, 40_000)),
            null,
        )
        assertEquals(listOf("exact"), ids(out))
    }

    @Test
    fun theLengthRuleAppliesToMutesToo() {
        val out = SegmentMath.prepare(listOf(seg("m", 10_000, 10_500, action = SegmentAction.MUTE), seg("m2", 20_000, 22_000, action = SegmentAction.MUTE)), null)
        assertEquals(listOf("m2"), ids(out))
    }

    @Test
    fun aHighlightAndAnExclusiveAccessMarkAreMarksNotSkipsAndSurvive() {
        val highlight = seg("poi", 150_250, 150_250, SegmentCategory.POI_HIGHLIGHT, SegmentAction.POI)
        val exclusive = seg("full", 0, 0, SegmentCategory.EXCLUSIVE_ACCESS, SegmentAction.FULL)
        val out = SegmentMath.prepare(listOf(highlight, exclusive), 212_000)
        assertEquals(listOf("full", "poi"), ids(out), "sorted by start, both kept")
    }

    // ---- the duration rule (a re-upload) ----

    @Test
    fun aSegmentFromAVideoOfAnotherLengthIsDropped() {
        val out = SegmentMath.prepare(
            listOf(
                seg("same", 10_000, 20_000, videoDurationMs = 212_000),
                seg("near", 30_000, 40_000, videoDurationMs = 213_999),
                seg("edge", 50_000, 60_000, videoDurationMs = 214_000),
                seg("far", 70_000, 80_000, videoDurationMs = 214_001),
                seg("below", 90_000, 100_000, videoDurationMs = 209_999),
            ),
            212_000,
        )
        assertEquals(listOf("same", "near", "edge"), ids(out), "2 s apart still counts as the same video")
    }

    @Test
    fun theDurationRuleNeedsBothDurations() {
        val withoutVideo = SegmentMath.prepare(listOf(seg("a", 0, 5_000, videoDurationMs = 999_000)), null)
        assertEquals(listOf("a"), ids(withoutVideo), "the video's duration is unknown")
        val unknownVideo = SegmentMath.prepare(listOf(seg("a", 0, 5_000, videoDurationMs = 999_000)), 0)
        assertEquals(listOf("a"), ids(unknownVideo), "0 means unknown")
        val withoutSegment = SegmentMath.prepare(listOf(seg("a", 0, 5_000, videoDurationMs = null), seg("b", 6_000, 9_000, videoDurationMs = 0)), 212_000)
        assertEquals(listOf("a", "b"), ids(withoutSegment), "the segment's duration is unknown")
    }

    @Test
    fun theDurationRuleAppliesToMarksToo() {
        val out = SegmentMath.prepare(
            listOf(seg("poi", 5_000, 5_000, SegmentCategory.POI_HIGHLIGHT, SegmentAction.POI, videoDurationMs = 500_000)),
            212_000,
        )
        assertTrue(out.isEmpty())
    }

    // ---- merging ----

    @Test
    fun overlappingSegmentsOfTheSameBehaviourBecomeOneWithTheFirstOnesUuidAndCategory() {
        val out = SegmentMath.prepare(
            listOf(
                seg("second", 40_000, 50_000, votes = 2),
                seg("first", 12_500, 45_200, votes = 12),
            ),
            null,
        )
        assertEquals(1, out.size)
        val merged = out.single()
        assertEquals("first", merged.uuid, "the first by start keeps its identity")
        assertEquals(SegmentCategory.SPONSOR, merged.category)
        assertEquals(12_500, merged.startMs)
        assertEquals(50_000, merged.endMs)
        assertEquals(12, merged.votes)
    }

    @Test
    fun aSegmentInsideAnotherDoesNotShortenIt() {
        val out = SegmentMath.prepare(listOf(seg("big", 0, 100_000), seg("inner", 20_000, 30_000)), null)
        assertEquals(listOf("big"), ids(out))
        assertEquals(100_000, out.single().endMs)
    }

    @Test
    fun aChainOfOverlapsBecomesOne() {
        val out = SegmentMath.prepare(listOf(seg("a", 0, 10_000), seg("b", 9_000, 20_000), seg("c", 19_000, 30_000), seg("d", 29_999, 31_000)), null)
        assertEquals(listOf("a"), ids(out))
        assertEquals(31_000, out.single().endMs)
    }

    @Test
    fun segmentsThatOnlyTouchStayApart() {
        val out = SegmentMath.prepare(listOf(seg("a", 10_000, 20_000), seg("b", 20_000, 30_000)), null)
        assertEquals(listOf("a", "b"), ids(out), "adjacent is not overlapping")
        assertEquals(20_000, out[0].endMs)
        assertEquals(20_000, out[1].startMs)
    }

    @Test
    fun differentDefaultBehavioursAreNotMerged() {
        // sponsor skips, self-promotion asks (spec 7.1 defaults)
        val out = SegmentMath.prepare(listOf(seg("s", 10_000, 30_000), seg("p", 20_000, 40_000, SegmentCategory.SELF_PROMO)), null)
        assertEquals(listOf("s", "p"), ids(out))
        assertEquals(30_000, out[0].endMs)
    }

    @Test
    fun differentCategoriesWithTheSameBehaviourAreMergedKeepingTheFirstsCategory() {
        // self-promotion and interaction both ask by default
        val out = SegmentMath.prepare(
            listOf(seg("late", 20_000, 40_000, SegmentCategory.INTERACTION), seg("early", 10_000, 30_000, SegmentCategory.SELF_PROMO)),
            null,
        )
        assertEquals(1, out.size)
        assertEquals("early", out.single().uuid)
        assertEquals(SegmentCategory.SELF_PROMO, out.single().category)
        assertEquals(10_000, out.single().startMs)
        assertEquals(40_000, out.single().endMs)
    }

    @Test
    fun theBehavioursOfTheSettingsDecideWhatIsTheSame() {
        val input = listOf(seg("s", 10_000, 30_000), seg("p", 20_000, 40_000, SegmentCategory.SELF_PROMO))
        val bothSkip = SegmentCategory.defaults() + (SegmentCategory.SELF_PROMO to SegmentBehaviour.SKIP)
        assertEquals(listOf("s"), ids(SegmentMath.prepare(input, null, bothSkip)))
        val skipAndMute = SegmentCategory.defaults() + (SegmentCategory.SELF_PROMO to SegmentBehaviour.MUTE)
        assertEquals(listOf("s", "p"), ids(SegmentMath.prepare(input, null, skipAndMute)))
    }

    @Test
    fun aCategoryMissingFromTheBehavioursCountsAsItsDefault() {
        val input = listOf(seg("s", 10_000, 30_000), seg("p", 20_000, 40_000, SegmentCategory.SELF_PROMO))
        assertEquals(listOf("s", "p"), ids(SegmentMath.prepare(input, null, emptyMap())))
    }

    @Test
    fun aSkipAndAMuteOfTheSameCategoryAreNotMerged() {
        val out = SegmentMath.prepare(listOf(seg("skip", 10_000, 30_000), seg("mute", 20_000, 40_000, action = SegmentAction.MUTE)), null)
        assertEquals(listOf("skip", "mute"), ids(out), "one skips and one mutes: merging would change what the submitter asked for")
    }

    @Test
    fun marksAreNeverMergedIntoSkips() {
        val out = SegmentMath.prepare(
            listOf(seg("s", 10_000, 30_000), seg("poi", 20_000, 20_000, SegmentCategory.POI_HIGHLIGHT, SegmentAction.POI)),
            null,
        )
        assertEquals(listOf("s", "poi"), ids(out))
        assertEquals(30_000, out[0].endMs)
    }

    @Test
    fun interleavedCategoriesMergeWithTheirOwnKind() {
        val out = SegmentMath.prepare(
            listOf(
                seg("sponsor-a", 0, 100_000),
                seg("promo", 10_000, 20_000, SegmentCategory.SELF_PROMO),
                seg("sponsor-b", 50_000, 150_000),
            ),
            null,
        )
        assertEquals(listOf("sponsor-a", "promo"), ids(out))
        assertEquals(150_000, out[0].endMs)
    }

    @Test
    fun theSameCategoryRuleMergesOnlyWithinACategory() {
        val input = listOf(
            seg("a", 10_000, 30_000, SegmentCategory.SELF_PROMO),
            seg("b", 20_000, 40_000, SegmentCategory.INTERACTION),
            seg("c", 25_000, 45_000, SegmentCategory.SELF_PROMO),
        )
        val out = SegmentMath.prepare(input, null, SegmentMath::sameCategory)
        assertEquals(listOf("a", "b"), ids(out))
        assertEquals(45_000, out[0].endMs)
        assertEquals(40_000, out[1].endMs)
    }

    // ---- order ----

    @Test
    fun theResultIsSortedByStartThenEnd() {
        val out = SegmentMath.prepare(
            listOf(
                seg("c", 30_000, 35_000, SegmentCategory.INTRO),
                seg("a1", 10_000, 15_000, SegmentCategory.SPONSOR),
                seg("a2", 10_000, 12_000, SegmentCategory.SELF_PROMO),
                seg("b", 20_000, 25_000, SegmentCategory.HOOK),
            ),
            null,
        )
        assertEquals(listOf("a2", "a1", "b", "c"), ids(out), "by start, then by end")
    }

    @Test
    fun anEmptyListStaysEmptyAndTheInputIsNotChanged() {
        assertTrue(SegmentMath.prepare(emptyList(), 1_000).isEmpty())
        val input = listOf(seg("b", 20_000, 30_000), seg("a", 0, 10_000))
        val copy = input.toList()
        SegmentMath.prepare(input, null)
        assertEquals(copy, input)
    }
}
