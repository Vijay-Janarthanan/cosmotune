package com.cosmotune.design

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What Home draws as its hero and its mix tiles, and where the feed's shelves start in the list. */
class HomePlanTest {

    private fun item(title: String) = ShelfItem(title = title, subtitle = "sub $title", thumbnailUrl = "https://x/$title", videoId = "v-$title", browseId = null)

    private fun shelf(title: String, count: Int, subtitle: String = ""): HomeShelf =
        HomeShelf(title = title, items = List(count) { item("$title-$it") }, subtitle = subtitle)

    // ---- the hero -----------------------------------------------------------------------------------------------------------

    @Test
    fun theHeroIsTheFirstItemOfTheFirstShelf() {
        val plan = HomePlan.of(listOf(shelf("Quick picks", 5), shelf("Mixed for you", 4)))
        assertEquals(0, plan.heroShelfIndex)
        assertEquals("Quick picks-0", plan.hero?.title)
    }

    @Test
    fun whenRecentsLeadsTheFeedTheHeroContinuesTheLastSong() {
        val plan = HomePlan.of(listOf(shelf("Recents", 8), shelf("Quick picks", 5), shelf("Mixed for you", 4)))
        assertEquals("Recents-0", plan.hero?.title)
        assertEquals(listOf(1, 2), plan.mixes.map { it.shelfIndex })
    }

    @Test
    fun anEmptyFeedHasNoHeroAndNoMixes() {
        val plan = HomePlan.of(emptyList())
        assertNull(plan.hero)
        assertTrue(plan.mixes.isEmpty())
    }

    @Test
    fun aFirstShelfWithNoItemsIsPassedOverForTheHero() {
        val plan = HomePlan.of(listOf(shelf("Empty", 0), shelf("Quick picks", 5), shelf("Mixed for you", 4)))
        assertEquals(1, plan.heroShelfIndex)
        assertEquals("Quick picks-0", plan.hero?.title)
        assertEquals(listOf(2), plan.mixes.map { it.shelfIndex })
    }

    // ---- the mixes ------------------------------------------------------------------------------------------------------------

    @Test
    fun theMixesAreTheNextTwoShelvesWithAtLeastThreeItems() {
        val plan = HomePlan.of(listOf(shelf("A", 6), shelf("B", 3), shelf("C", 9), shelf("D", 9)))
        assertEquals(listOf(1, 2), plan.mixes.map { it.shelfIndex })
        assertEquals(listOf("B", "C"), plan.mixes.map { it.shelf.title })
    }

    @Test
    fun aShelfWithFewerThanThreeItemsIsSkipped() {
        val plan = HomePlan.of(listOf(shelf("A", 6), shelf("B", 2), shelf("C", 1), shelf("D", 3), shelf("E", 4), shelf("F", 4)))
        assertEquals(listOf(3, 4), plan.mixes.map { it.shelfIndex })
    }

    @Test
    fun thereAreNeverMoreThanTwoMixes() {
        val plan = HomePlan.of(List(8) { shelf("S$it", 5) })
        assertEquals(2, plan.mixes.size)
        assertEquals(HomePlan.MIX_MAX, plan.mixes.size)
    }

    @Test
    fun withFewerCandidatesThereAreFewerMixes() {
        assertEquals(1, HomePlan.of(listOf(shelf("A", 6), shelf("B", 5), shelf("C", 2))).mixes.size)
        assertEquals(0, HomePlan.of(listOf(shelf("A", 6), shelf("B", 2))).mixes.size)
        assertEquals(0, HomePlan.of(listOf(shelf("A", 6))).mixes.size)
    }

    @Test
    fun theHeroShelfIsNeverAlsoAMix() {
        val plan = HomePlan.of(listOf(shelf("A", 6), shelf("B", 6), shelf("C", 6)))
        assertFalse(0 in plan.mixes.map { it.shelfIndex })
    }

    // ---- Recents ----------------------------------------------------------------------------------------------------------------

    @Test
    fun onlyAFirstShelfTitledRecentsIsTheRecentsShelf() {
        val shelves = listOf(shelf("Recents", 4), shelf("recents", 4), shelf("Quick picks", 4))
        assertTrue(HomePlan.isRecents(shelves, 0))
        assertFalse(HomePlan.isRecents(shelves, 1), "a later shelf of that name is an ordinary shelf, as the old screen drew it")
        assertFalse(HomePlan.isRecents(shelves, 2))
        assertTrue(HomePlan.isRecents(listOf(shelf("RECENTS", 4)), 0), "the title is compared without regard to case")
        assertFalse(HomePlan.isRecents(emptyList(), 0))
    }

    // ---- the line under a mix title -----------------------------------------------------------------------------------------------

    @Test
    fun aMixWithNoStraplineIsDescribedByItsFirstTwoItems() {
        assertEquals("X-0, X-1", HomePlan.fallbackSubtitle(shelf("X", 5)))
        assertEquals("X-0", HomePlan.fallbackSubtitle(shelf("X", 1)))
        assertEquals("", HomePlan.fallbackSubtitle(shelf("X", 0)))
    }

    // ---- where the shelves start in the list --------------------------------------------------------------------------------------

    @Test
    fun theShelvesStartAfterTheHeaderAndWhateverIsShownBetweenTheHeaderAndThem() {
        // header only
        assertEquals(1, HomePlan.firstShelfItemIndex(banner = false, moods = false, bento = false, recentsSkeleton = false))
        // header, banner, moods, bento, recents skeleton
        assertEquals(5, HomePlan.firstShelfItemIndex(banner = true, moods = true, bento = true, recentsSkeleton = true))
        assertEquals(3, HomePlan.firstShelfItemIndex(banner = false, moods = true, bento = true, recentsSkeleton = false))
        assertEquals(2, HomePlan.firstShelfItemIndex(banner = true, moods = false, bento = false, recentsSkeleton = false))
    }
}
