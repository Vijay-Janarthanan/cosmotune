package com.cosmotune.watch

import com.cosmotune.watch.model.EpgGenre
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EpgGenreTest {
    @Test fun guideCategoriesFallIntoAFewGenres() {
        assertEquals(EpgGenre.FILM, EpgGenre.of(listOf("Movie / Drama")))
        assertEquals(EpgGenre.NEWS, EpgGenre.of(listOf("News / Current affairs")))
        assertEquals(EpgGenre.SPORT, EpgGenre.of(listOf("Sports", "Football")))
        assertEquals(EpgGenre.KIDS, EpgGenre.of(listOf("Children's / Youth programmes")))
        assertEquals(EpgGenre.MUSIC, EpgGenre.of(listOf("Music / Ballet / Dance")))
        assertEquals(EpgGenre.FACTUAL, EpgGenre.of(listOf("Documentary")))
        assertEquals(EpgGenre.SHOW, EpgGenre.of(listOf("Show / Game show")))
    }

    @Test fun theFirstCategoryThatNamesAGenreWins() {
        assertEquals(EpgGenre.SPORT, EpgGenre.of(listOf("Live", "Cricket", "News")))
    }

    @Test fun unknownCategoriesHaveNoGenre() {
        assertNull(EpgGenre.of(listOf("Other")))
        assertNull(EpgGenre.of(emptyList()))
    }
}
