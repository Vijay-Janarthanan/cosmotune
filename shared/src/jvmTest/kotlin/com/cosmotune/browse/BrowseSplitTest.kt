package com.cosmotune.browse

import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Explore lists "Moods & moments" first and the genres after it: the new Home takes the first section, the new Search the rest. */
class BrowseSplitTest {

    private fun item(title: String, id: String = "id-$title", params: String? = "p-$title") = MoodGenre(title, id, params)

    private val moods = MoodGenreSection("Moods & moments", listOf(item("Chill"), item("Focus"), item("Party")))
    private val genres = MoodGenreSection("Genres", listOf(item("Pop"), item("Rock"), item("Jazz")))
    private val more = MoodGenreSection("More genres", listOf(item("Blues"), item("Soul")))

    @Test fun moodsAreTheItemsOfTheFirstSection() {
        assertEquals(listOf("Chill", "Focus", "Party"), BrowseSplit.moods(listOf(moods, genres, more)).map { it.title })
    }

    @Test fun genresAreTheItemsOfEveryOtherSectionInOrder() {
        assertEquals(listOf("Pop", "Rock", "Jazz", "Blues", "Soul"), BrowseSplit.genres(listOf(moods, genres, more)).map { it.title })
    }

    @Test fun aSingleSectionIsAllMoodsAndNoGenres() {
        assertEquals(3, BrowseSplit.moods(listOf(moods)).size)
        assertTrue(BrowseSplit.genres(listOf(moods)).isEmpty())
    }

    @Test fun noSectionsGiveNothing() {
        assertTrue(BrowseSplit.moods(emptyList()).isEmpty())
        assertTrue(BrowseSplit.genres(emptyList()).isEmpty())
    }

    @Test fun anEmptyFirstSectionStillCountsAsTheMoodsSection() {
        val sections = listOf(MoodGenreSection("Moods & moments", emptyList()), genres)
        assertTrue(BrowseSplit.moods(sections).isEmpty())
        assertEquals(listOf("Pop", "Rock", "Jazz"), BrowseSplit.genres(sections).map { it.title })
    }

    @Test fun anItemRepeatedInASectionIsListedOnce() {
        val twice = MoodGenreSection("Moods & moments", listOf(item("Chill"), item("Focus"), item("Chill again", id = "id-Chill", params = "p-Chill")))
        assertEquals(listOf("Chill", "Focus"), BrowseSplit.moods(listOf(twice)).map { it.title })
        val dup = MoodGenreSection("Genres", listOf(item("Pop"), item("Pop")))
        val dup2 = MoodGenreSection("More", listOf(item("Pop"), item("Rock")))
        assertEquals(listOf("Pop", "Rock"), BrowseSplit.genres(listOf(moods, dup, dup2)).map { it.title })
    }

    @Test fun sameBrowseIdWithOtherParamsIsAnotherItem() {
        val s = MoodGenreSection("Moods & moments", listOf(item("A", "same", "x"), item("B", "same", "y"), item("C", "same", null), item("D", "same", null)))
        assertEquals(listOf("A", "B", "C"), BrowseSplit.moods(listOf(s)).map { it.title })
    }

    @Test fun aGenreThatIsAlsoAMoodIsLeftOutOfTheGenres() {
        val shared = MoodGenreSection("Genres", listOf(item("Chill mood", id = "id-Chill", params = "p-Chill"), item("Pop")))
        assertEquals(listOf("Pop"), BrowseSplit.genres(listOf(moods, shared)).map { it.title })
    }
}
