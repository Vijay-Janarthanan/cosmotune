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
    @Test fun inAnotherLanguageTheEnglishNamesSortTheCardsAndTheListenersTitlesStay() {
        val cards = listOf(mix("Mi Supermix"), mix("Mezcla Feel Good 1"), mix("Supermix Feel Good"), mix("Mezcla de novedades"), mix("Ritmos punjabi"))
        val english = mapOf(
            "VLRDMiSupermix" to "My Supermix",
            "VLRDMezclaFeelGood1" to "Feel Good Mix 1",
            "VLRDSupermixFeelGood" to "Feel Good Supermix",
            "VLRDMezcladenovedades" to "New Release Mix",
            "VLRDRitmospunjabi" to "Modern Punjabi Beats",
        )
        val groups = MixGroups.of(cards, english)
        assertEquals(listOf("Mi Supermix", "Mezcla de novedades"), groups.stations.map { it.title })
        assertEquals(listOf("Ritmos punjabi"), groups.yours.map { it.title })
        assertEquals(listOf("Supermix Feel Good", "Mezcla Feel Good 1"), groups.moods.single().items.map { it.title })
        assertEquals("Feel Good", groups.moods.single().name, "the mood keeps its name when the listener's titles carry it")
    }

    @Test fun aMoodWhoseNameIsTranslatedIsHeadedByItsFirstCard() {
        val cards = listOf(mix("Supermix Bonne humeur"), mix("Mix Bonne humeur 1"))
        val english = mapOf("VLRDSupermixBonnehumeur" to "Feel Good Supermix", "VLRDMixBonnehumeur1" to "Feel Good Mix 1")
        assertEquals("Supermix Bonne humeur", MixGroups.of(cards, english).moods.single().name)
    }

    @Test fun aCardTheEnglishPageLacksIsSortedByItsOwnTitle() {
        val groups = MixGroups.of(listOf(mix("Replay Mix"), mix("Ma mezcla")), mapOf("VLRDOther" to "Discover Mix"))
        assertEquals(listOf("Replay Mix"), groups.stations.map { it.title })
        assertEquals(listOf("Ma mezcla"), groups.yours.map { it.title })
    }
}
