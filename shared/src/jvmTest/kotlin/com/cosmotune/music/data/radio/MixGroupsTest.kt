package com.cosmotune.music.data.radio

import com.cosmotune.music.data.model.ShelfItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Radio's three kinds of "Mixed for you" cards, sorted by their names (upstream 1.10's rule, GPL-3.0). */
class MixGroupsTest {
    private fun mix(title: String) = ShelfItem(title, "", null, null, "VLRD" + title.filter(Char::isLetterOrDigit))

    @Test fun theNamedMixesAreStationsInAFixedOrder() {
        val groups = MixGroups.of(listOf(mix("Replay Mix"), mix("Chill Mix 1"), mix("My Supermix"), mix("Discover Mix")))
        assertEquals(listOf("My Supermix", "Discover Mix", "Replay Mix"), groups.stations.map { it.title })
    }

    @Test fun aMoodsSupermixLeadsItsNumberedMixes() {
        val groups = MixGroups.of(listOf(mix("Feel Good Mix 2"), mix("Chill Mix 1"), mix("Feel Good Supermix"), mix("Feel Good Mix 1")))
        assertEquals(listOf("Feel Good", "Chill"), groups.moods.map { it.name }, "in the order the page first names each mood")
        assertEquals(listOf("Feel Good Supermix", "Feel Good Mix 1", "Feel Good Mix 2"), groups.moods[0].items.map { it.title })
    }

    @Test fun everythingElseIsTheListenersOwn() {
        val groups = MixGroups.of(listOf(mix("Modern Punjabi Beats"), mix("My Mix 1"), mix("Tamil Melodies"), mix("Discover Mix")))
        assertEquals(listOf("Modern Punjabi Beats", "My Mix 1", "Tamil Melodies"), groups.yours.map { it.title })
        assertEquals(listOf("Discover Mix"), groups.stations.map { it.title })
    }

    @Test fun aRepeatedCardIsShownOnceAndNothingMeansEmpty() {
        assertEquals(1, MixGroups.of(listOf(mix("Replay Mix"), mix("Replay Mix"))).stations.size)
        assertTrue(MixGroups.of(emptyList()).isEmpty)
    }
}
