package com.cosmotune.video.segments

import com.cosmotune.video.player.SegmentDecider
import com.cosmotune.video.player.SegmentDecision
import com.cosmotune.video.settings.SegmentBehaviour
import com.cosmotune.video.settings.VideoSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/** The SponsorBlock policy (spec 6.1, 7.1): pure, one answer per position tick. */
class SegmentPolicyTest {

    private fun seg(
        uuid: String,
        startMs: Long,
        endMs: Long,
        category: SegmentCategory = SegmentCategory.SPONSOR,
        action: SegmentAction = SegmentAction.SKIP,
    ) = SkipSegment(uuid, category, action, startMs, endMs, null, 0, false, null)

    private fun settings(
        vararg behaviours: Pair<SegmentCategory, SegmentBehaviour>,
        sponsorBlock: Boolean = true,
    ) = VideoSettings(sponsorBlock = sponsorBlock, segmentBehaviours = SegmentCategory.defaults() + behaviours)

    private val skipSponsor = settings(SegmentCategory.SPONSOR to SegmentBehaviour.SKIP)

    private fun decide(
        positionMs: Long,
        segments: List<SkipSegment>,
        settings: VideoSettings = skipSponsor,
        handled: Set<String> = emptySet(),
        seekedInto: Set<String> = emptySet(),
    ) = SegmentPolicy.decide(positionMs, segments, settings, handled, seekedInto)

    // ---- the behaviours ----

    @Test
    fun skipSkipsToTheEndOfTheSegment() {
        val s = seg("s", 30_000, 60_000)
        val d = decide(40_000, listOf(s))
        assertEquals(SegmentDecision.SkipTo(60_000, s), d)
        assertSame(s, (d as SegmentDecision.SkipTo).segment)
    }

    @Test
    fun muteMutesUntilTheEndOfTheSegment() {
        val s = seg("m", 30_000, 60_000, SegmentCategory.INTERACTION, SegmentAction.MUTE)
        val d = decide(40_000, listOf(s), settings(SegmentCategory.INTERACTION to SegmentBehaviour.MUTE))
        assertEquals(SegmentDecision.Mute(60_000, s), d)
    }

    @Test
    fun askAsks() {
        val s = seg("a", 30_000, 60_000, SegmentCategory.SELF_PROMO)
        assertEquals(SegmentDecision.Ask(s), decide(40_000, listOf(s), settings(SegmentCategory.SELF_PROMO to SegmentBehaviour.ASK)))
    }

    @Test
    fun showAndOffDoNothing() {
        val s = seg("x", 30_000, 60_000, SegmentCategory.INTRO)
        assertEquals(SegmentDecision.None, decide(40_000, listOf(s), settings(SegmentCategory.INTRO to SegmentBehaviour.SHOW)))
        assertEquals(SegmentDecision.None, decide(40_000, listOf(s), settings(SegmentCategory.INTRO to SegmentBehaviour.OFF)))
    }

    @Test
    fun theBehaviourOfTheCategoryDecidesNotTheActionOfTheSegment() {
        val s = seg("s", 30_000, 60_000, SegmentCategory.SPONSOR)
        assertIs<SegmentDecision.SkipTo>(decide(40_000, listOf(s), settings(SegmentCategory.SPONSOR to SegmentBehaviour.SKIP)))
        assertIs<SegmentDecision.Mute>(decide(40_000, listOf(s), settings(SegmentCategory.SPONSOR to SegmentBehaviour.MUTE)))
        assertIs<SegmentDecision.Ask>(decide(40_000, listOf(s), settings(SegmentCategory.SPONSOR to SegmentBehaviour.ASK)))
        assertEquals(SegmentDecision.None, decide(40_000, listOf(s), settings(SegmentCategory.SPONSOR to SegmentBehaviour.SHOW)))
        assertEquals(SegmentDecision.None, decide(40_000, listOf(s), settings(SegmentCategory.SPONSOR to SegmentBehaviour.OFF)))
    }

    @Test
    fun aCategoryMissingFromTheSettingsUsesItsDefault() {
        val bare = VideoSettings(segmentBehaviours = emptyMap())
        val sponsor = seg("s", 0, 5_000)
        assertEquals(SegmentDecision.SkipTo(5_000, sponsor), decide(1_000, listOf(sponsor), bare), "sponsor skips by default")
        val promo = seg("p", 0, 5_000, SegmentCategory.SELF_PROMO)
        assertEquals(SegmentDecision.Ask(promo), decide(1_000, listOf(promo), bare), "self-promotion asks by default")
        val intro = seg("i", 0, 5_000, SegmentCategory.INTRO)
        assertEquals(SegmentDecision.None, decide(1_000, listOf(intro), bare), "an intro is only shown by default")
    }

    @Test
    fun aMuteSegmentIsMutedEvenWhereTheCategorySkips() {
        // the submitter asked for a mute (a spoken sponsor over music): skipping would cut the music
        val s = seg("m", 30_000, 60_000, SegmentCategory.SPONSOR, SegmentAction.MUTE)
        assertEquals(SegmentDecision.Mute(60_000, s), decide(40_000, listOf(s)))
    }

    // ---- where the segment is ----

    @Test
    fun aSegmentCoversItsStartButNotItsEnd() {
        val s = seg("s", 30_000, 60_000)
        assertEquals(SegmentDecision.None, decide(29_999, listOf(s)))
        assertEquals(SegmentDecision.SkipTo(60_000, s), decide(30_000, listOf(s)))
        assertEquals(SegmentDecision.SkipTo(60_000, s), decide(59_999, listOf(s)))
        assertEquals(SegmentDecision.None, decide(60_000, listOf(s)))
    }

    @Test
    fun outsideEverySegmentAndWithNoSegmentsThereIsNothingToDo() {
        val list = listOf(seg("a", 10_000, 20_000), seg("b", 40_000, 50_000))
        assertEquals(SegmentDecision.None, decide(0, list))
        assertEquals(SegmentDecision.None, decide(30_000, list))
        assertEquals(SegmentDecision.None, decide(90_000, list))
        assertEquals(SegmentDecision.None, decide(-5, list))
        assertEquals(SegmentDecision.None, decide(10_000, emptyList()))
    }

    @Test
    fun theListNeedsNoOrder() {
        val a = seg("a", 10_000, 20_000)
        val b = seg("b", 40_000, 50_000)
        assertEquals(SegmentDecision.SkipTo(20_000, a), decide(15_000, listOf(b, a)))
        assertEquals(SegmentDecision.SkipTo(50_000, b), decide(45_000, listOf(b, a)))
    }

    @Test
    fun aSegmentUnderOneSecondIsIgnored() {
        assertEquals(SegmentDecision.None, decide(10_200, listOf(seg("tiny", 10_000, 10_999))))
        assertEquals(SegmentDecision.SkipTo(11_000, seg("ok", 10_000, 11_000)), decide(10_200, listOf(seg("ok", 10_000, 11_000))))
    }

    // ---- handled and seeked into ----

    @Test
    fun aHandledSegmentIsNotActedOnAgain() {
        val s = seg("s", 30_000, 60_000)
        assertEquals(SegmentDecision.None, decide(40_000, listOf(s), handled = setOf("s")))
        val promo = seg("p", 30_000, 60_000, SegmentCategory.SELF_PROMO)
        assertEquals(SegmentDecision.None, decide(40_000, listOf(promo), settings(SegmentCategory.SELF_PROMO to SegmentBehaviour.ASK), handled = setOf("p")))
        val mute = seg("m", 30_000, 60_000, SegmentCategory.INTERACTION, SegmentAction.MUTE)
        assertEquals(SegmentDecision.None, decide(40_000, listOf(mute), settings(SegmentCategory.INTERACTION to SegmentBehaviour.MUTE), handled = setOf("m")))
    }

    @Test
    fun aSegmentTheUserSeekedIntoIsLeftAlone() {
        val s = seg("s", 30_000, 60_000)
        assertEquals(SegmentDecision.None, decide(40_000, listOf(s), seekedInto = setOf("s")))
        val promo = seg("p", 30_000, 60_000, SegmentCategory.SELF_PROMO)
        assertEquals(SegmentDecision.None, decide(40_000, listOf(promo), settings(SegmentCategory.SELF_PROMO to SegmentBehaviour.ASK), seekedInto = setOf("p")))
    }

    @Test
    fun aSeekIntoOneSegmentLeavesTheNextOneActive() {
        val a = seg("a", 30_000, 60_000)
        val b = seg("b", 120_000, 150_000)
        assertEquals(SegmentDecision.None, decide(45_000, listOf(a, b), seekedInto = setOf("a")))
        assertEquals(SegmentDecision.SkipTo(150_000, b), decide(125_000, listOf(a, b), seekedInto = setOf("a")))
    }

    @Test
    fun aHandledOrSeekedIntoSegmentDoesNotHideAnotherOverIt() {
        val a = seg("a", 30_000, 60_000)
        val b = seg("b", 40_000, 80_000)
        assertEquals(SegmentDecision.SkipTo(80_000, b), decide(45_000, listOf(a, b), handled = setOf("a")))
        assertEquals(SegmentDecision.SkipTo(80_000, b), decide(45_000, listOf(a, b), seekedInto = setOf("a")))
    }

    // ---- marks never skip ----

    @Test
    fun aHighlightAndAnExclusiveAccessMarkNeverSkipWhateverTheSetting() {
        val poi = seg("poi", 150_000, 160_000, SegmentCategory.POI_HIGHLIGHT, SegmentAction.POI)
        val full = seg("full", 0, 212_000, SegmentCategory.EXCLUSIVE_ACCESS, SegmentAction.FULL)
        for (b in SegmentBehaviour.entries) {
            val st = settings(SegmentCategory.POI_HIGHLIGHT to b, SegmentCategory.EXCLUSIVE_ACCESS to b)
            assertEquals(SegmentDecision.None, decide(155_000, listOf(poi, full), st), "behaviour $b")
        }
    }

    @Test
    fun aChapterNeverSkips() {
        val chapter = seg("c", 0, 60_000, SegmentCategory.SPONSOR, SegmentAction.CHAPTER)
        assertEquals(SegmentDecision.None, decide(10_000, listOf(chapter)))
    }

    // ---- the switch ----

    @Test
    fun withSponsorBlockOffTheAnswerIsAlwaysNone() {
        val off = settings(SegmentCategory.SPONSOR to SegmentBehaviour.SKIP, sponsorBlock = false)
        val s = seg("s", 30_000, 60_000)
        val m = seg("m", 100_000, 130_000, SegmentCategory.INTERACTION, SegmentAction.MUTE)
        assertEquals(SegmentDecision.None, decide(40_000, listOf(s), off))
        assertEquals(SegmentDecision.None, decide(110_000, listOf(m), off))
        assertEquals(SegmentDecision.None, decide(130_500, listOf(m), off), "no unmute either: nothing was muted")
    }

    // ---- leaving a muted segment ----

    private val muteInteraction = settings(SegmentCategory.INTERACTION to SegmentBehaviour.MUTE)
    private val muted = seg("m", 30_000, 60_000, SegmentCategory.INTERACTION, SegmentAction.MUTE)

    @Test
    fun leavingAMutedSegmentUnmutes() {
        assertEquals(SegmentDecision.Unmute, decide(60_000, listOf(muted), muteInteraction))
        assertEquals(SegmentDecision.Unmute, decide(60_500, listOf(muted), muteInteraction))
        assertEquals(SegmentDecision.Unmute, decide(60_000 + SegmentPolicy.UNMUTE_WINDOW_MS - 1, listOf(muted), muteInteraction))
    }

    @Test
    fun theUnmuteIsOnlyForTheMomentRightAfterTheSegment() {
        assertEquals(SegmentDecision.None, decide(60_000 + SegmentPolicy.UNMUTE_WINDOW_MS, listOf(muted), muteInteraction))
        assertEquals(SegmentDecision.None, decide(120_000, listOf(muted), muteInteraction))
        assertEquals(SegmentDecision.None, decide(29_999, listOf(muted), muteInteraction), "before the segment")
        assertEquals(SegmentDecision.None, decide(5_000, listOf(muted), muteInteraction))
    }

    @Test
    fun theWindowIsLongerThanTheControllersTick() {
        // the controller ticks every 250 ms; one tick has to fall inside the window
        assertEquals(true, SegmentPolicy.UNMUTE_WINDOW_MS > 250)
    }

    @Test
    fun skippingAndAskingSegmentsDoNotUnmute() {
        val skip = seg("s", 30_000, 60_000)
        assertEquals(SegmentDecision.None, decide(60_500, listOf(skip)))
        val ask = seg("a", 30_000, 60_000, SegmentCategory.SELF_PROMO)
        assertEquals(SegmentDecision.None, decide(60_500, listOf(ask), settings(SegmentCategory.SELF_PROMO to SegmentBehaviour.ASK)))
    }

    @Test
    fun aMuteThatIsFollowedByASegmentAnswersForTheNextSegment() {
        val next = seg("n", 60_000, 70_000)
        assertEquals(SegmentDecision.SkipTo(70_000, next), decide(60_000, listOf(muted, next), muteInteraction + (SegmentCategory.SPONSOR to SegmentBehaviour.SKIP)))
    }

    private operator fun VideoSettings.plus(b: Pair<SegmentCategory, SegmentBehaviour>) = copy(segmentBehaviours = segmentBehaviours + b)

    // ---- several segments ----

    @Test
    fun twoAdjacentSkipsAreSkippedOneAfterTheOther() {
        val a = seg("a", 10_000, 20_000)
        val b = seg("b", 20_000, 30_000, SegmentCategory.OUTRO)
        val st = settings(SegmentCategory.SPONSOR to SegmentBehaviour.SKIP, SegmentCategory.OUTRO to SegmentBehaviour.SKIP)
        assertEquals(SegmentDecision.SkipTo(20_000, a), decide(10_000, listOf(a, b), st))
        // the controller marks a as handled and seeks to 20 s
        assertEquals(SegmentDecision.SkipTo(30_000, b), decide(20_000, listOf(a, b), st, handled = setOf("a")))
        // a seek that landed a hair early stays quiet
        assertEquals(SegmentDecision.None, decide(19_900, listOf(a, b), st, handled = setOf("a")))
        // both done
        assertEquals(SegmentDecision.None, decide(30_000, listOf(a, b), st, handled = setOf("a", "b")))
    }

    @Test
    fun skipBeatsMuteBeatsAskWhereSegmentsOverlap() {
        val ask = seg("ask", 10_000, 50_000, SegmentCategory.SELF_PROMO)
        val mute = seg("mute", 20_000, 50_000, SegmentCategory.INTERACTION, SegmentAction.MUTE)
        val skip = seg("skip", 30_000, 50_000)
        val st = settings(
            SegmentCategory.SELF_PROMO to SegmentBehaviour.ASK,
            SegmentCategory.INTERACTION to SegmentBehaviour.MUTE,
            SegmentCategory.SPONSOR to SegmentBehaviour.SKIP,
        )
        val all = listOf(ask, mute, skip)
        assertEquals(SegmentDecision.Ask(ask), decide(15_000, all, st))
        assertEquals(SegmentDecision.Mute(50_000, mute), decide(25_000, all, st))
        assertEquals(SegmentDecision.SkipTo(50_000, skip), decide(35_000, all, st))
        assertEquals(SegmentDecision.Mute(50_000, mute), decide(35_000, all, st, handled = setOf("skip")))
    }

    @Test
    fun ofTwoSegmentsOfTheSameBehaviourTheEarlierOneWins() {
        val first = seg("first", 10_000, 40_000)
        val second = seg("second", 20_000, 60_000)
        assertEquals(SegmentDecision.SkipTo(40_000, first), decide(25_000, listOf(second, first)))
    }

    @Test
    fun anOverlappingPairIsSkippedInTwoStepsWhenNothingMergedThem() {
        val first = seg("first", 10_000, 40_000)
        val second = seg("second", 20_000, 60_000)
        assertEquals(SegmentDecision.SkipTo(40_000, first), decide(10_000, listOf(first, second)))
        assertEquals(SegmentDecision.SkipTo(60_000, second), decide(40_000, listOf(first, second), handled = setOf("first")))
    }

    @Test
    fun afterPreparingAnOverlappingPairIsSkippedInOneStep() {
        val prepared = SegmentMath.prepare(listOf(seg("first", 10_000, 40_000), seg("second", 20_000, 60_000)), null)
        val d = decide(10_000, prepared)
        assertEquals(SegmentDecision.SkipTo(60_000, prepared.single()), d)
        assertEquals("first", (d as SegmentDecision.SkipTo).segment.uuid)
    }

    // ---- the end of the video ----

    @Test
    fun aSegmentAtTheVeryEndSkipsToTheEndAndThenThereIsNothingLeft() {
        val outro = seg("end", 590_000, 600_000, SegmentCategory.OUTRO)
        val st = settings(SegmentCategory.OUTRO to SegmentBehaviour.SKIP)
        assertEquals(SegmentDecision.SkipTo(600_000, outro), decide(595_000, listOf(outro), st))
        // the engine ends the video at 600 s; the position never lies inside the segment again
        assertEquals(SegmentDecision.None, decide(600_000, listOf(outro), st))
        assertEquals(SegmentDecision.None, decide(595_000, listOf(outro), st, handled = setOf("end")))
    }

    // ---- the contract ----

    @Test
    fun thePolicyIsTheSegmentDeciderTheControllerTakes() {
        val decider: SegmentDecider = SegmentPolicy
        val s = seg("s", 0, 5_000)
        assertEquals(SegmentDecision.SkipTo(5_000, s), decider.decide(1_000, listOf(s), skipSponsor, emptySet(), emptySet()))
    }

    @Test
    fun theInputsAreNotChanged() {
        val s = seg("s", 30_000, 60_000)
        val list = listOf(s)
        val handled = mutableSetOf<String>()
        val seeked = mutableSetOf<String>()
        decide(40_000, list, handled = handled, seekedInto = seeked)
        assertEquals(listOf(s), list)
        assertEquals(emptySet(), handled, "marking a segment handled is the controller's job")
        assertEquals(emptySet(), seeked)
    }
}
