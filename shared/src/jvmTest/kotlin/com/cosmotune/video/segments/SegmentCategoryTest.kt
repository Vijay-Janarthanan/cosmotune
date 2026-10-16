package com.cosmotune.video.segments

import com.cosmotune.video.settings.SegmentBehaviour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** SponsorBlock's categories: the defaults of spec 7.1, their API names and the bar colours (the extension's `barTypes`). */
class SegmentCategoryTest {

    @Test
    fun theDefaultsAreThoseOfSpec71() {
        val expected = mapOf(
            SegmentCategory.SPONSOR to SegmentBehaviour.SKIP,
            SegmentCategory.SELF_PROMO to SegmentBehaviour.ASK,
            SegmentCategory.INTERACTION to SegmentBehaviour.ASK,
            SegmentCategory.INTRO to SegmentBehaviour.SHOW,
            SegmentCategory.OUTRO to SegmentBehaviour.SHOW,
            SegmentCategory.PREVIEW to SegmentBehaviour.SHOW,
            SegmentCategory.HOOK to SegmentBehaviour.SHOW,
            SegmentCategory.MUSIC_OFFTOPIC to SegmentBehaviour.SHOW,
            SegmentCategory.FILLER to SegmentBehaviour.OFF,
            SegmentCategory.POI_HIGHLIGHT to SegmentBehaviour.SHOW,
            SegmentCategory.EXCLUSIVE_ACCESS to SegmentBehaviour.SHOW,
        )
        assertEquals(expected, SegmentCategory.defaults())
        for (c in SegmentCategory.entries) assertEquals(c.defaultBehaviour, SegmentCategory.defaults()[c])
    }

    @Test
    fun theApiNamesRoundTrip() {
        assertEquals(
            listOf("sponsor", "selfpromo", "interaction", "intro", "outro", "preview", "hook", "music_offtopic", "filler",
                "poi_highlight", "exclusive_access"),
            SegmentCategory.entries.map { it.apiName },
        )
        for (c in SegmentCategory.entries) assertEquals(c, SegmentCategory.of(c.apiName))
        assertNull(SegmentCategory.of("chapter"))     // the video's own chapters are used
        assertNull(SegmentCategory.of("SPONSOR"))
        assertNull(SegmentCategory.of(""))
    }

    @Test
    fun theBarColoursAreTheExtensionDefaults() {
        assertEquals(0xFF00D400, SegmentCategory.SPONSOR.colorArgb)
        assertEquals(0xFFFFFF00, SegmentCategory.SELF_PROMO.colorArgb)
        assertEquals(0xFFCC00FF, SegmentCategory.INTERACTION.colorArgb)
        assertEquals(0xFF00FFFF, SegmentCategory.INTRO.colorArgb)
        assertEquals(0xFF0202ED, SegmentCategory.OUTRO.colorArgb)
        assertEquals(0xFF008FD6, SegmentCategory.PREVIEW.colorArgb)
        assertEquals(0xFF395699, SegmentCategory.HOOK.colorArgb)
        assertEquals(0xFFFF9900, SegmentCategory.MUSIC_OFFTOPIC.colorArgb)
        assertEquals(0xFF7300FF, SegmentCategory.FILLER.colorArgb)
        assertEquals(0xFFFF1684, SegmentCategory.POI_HIGHLIGHT.colorArgb)
        assertEquals(0xFF008A5C, SegmentCategory.EXCLUSIVE_ACCESS.colorArgb)
    }

    @Test
    fun eachCategoryAllowsItsActions() {
        val skipMuteFull = setOf(SegmentAction.SKIP, SegmentAction.MUTE, SegmentAction.FULL)
        val skipMute = setOf(SegmentAction.SKIP, SegmentAction.MUTE)
        assertEquals(skipMuteFull, SegmentCategory.SPONSOR.actions)
        assertEquals(skipMuteFull, SegmentCategory.SELF_PROMO.actions)
        for (c in listOf(SegmentCategory.INTERACTION, SegmentCategory.INTRO, SegmentCategory.OUTRO, SegmentCategory.PREVIEW,
            SegmentCategory.HOOK, SegmentCategory.FILLER)) {
            assertEquals(skipMute, c.actions, "$c")
        }
        assertEquals(setOf(SegmentAction.SKIP), SegmentCategory.MUSIC_OFFTOPIC.actions)
        assertEquals(setOf(SegmentAction.POI), SegmentCategory.POI_HIGHLIGHT.actions)
        assertEquals(setOf(SegmentAction.FULL), SegmentCategory.EXCLUSIVE_ACCESS.actions)
    }
}
