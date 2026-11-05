package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The desktop page of an album or a playlist (`.d2-tracks li`) has a column for the album of each row. An album's rows are all on it
 * and a playlist whose feed names no album had a dash on every row: the column is there only when there is something to put in it.
 */
class CollectionColumnsTest {

    @Test
    fun aPlaylistWhereNoRowHasAnAlbumHasNoAlbumColumn() {
        assertFalse(CollectionColumns.showsAlbum(isAlbumPage = false, albumNames = listOf(null, null, null)))
    }

    @Test
    fun aPlaylistWhereOneRowHasAnAlbumKeepsTheColumn() {
        assertTrue(CollectionColumns.showsAlbum(isAlbumPage = false, albumNames = listOf(null, "Aashiqui 2", null)))
        assertTrue(CollectionColumns.showsAlbum(isAlbumPage = false, albumNames = listOf("Aashiqui 2")))
    }

    @Test
    fun anAlbumNameThatIsEmptyOrBlankIsNoAlbum() {
        assertFalse(CollectionColumns.showsAlbum(isAlbumPage = false, albumNames = listOf("", "   ", null)))
        assertTrue(CollectionColumns.showsAlbum(isAlbumPage = false, albumNames = listOf("", " x ")))
    }

    @Test
    fun anEmptyPlaylistHasNoColumnToShow() {
        assertFalse(CollectionColumns.showsAlbum(isAlbumPage = false, albumNames = emptyList()))
    }

    @Test
    fun anAlbumPageNeverHasTheColumnWhatEveryRowSays() {
        // every row of a release is on the release: the column would say the page's own title on every row
        assertFalse(CollectionColumns.showsAlbum(isAlbumPage = true, albumNames = listOf("Aashiqui 2", "Aashiqui 2")))
        assertFalse(CollectionColumns.showsAlbum(isAlbumPage = true, albumNames = emptyList()))
    }

    @Test
    fun theDecisionReadsEveryRowNotTheFirstOne() {
        val names = List(100) { if (it == 99) "Last album" else null }
        assertEquals(true, CollectionColumns.showsAlbum(isAlbumPage = false, albumNames = names))
    }
}
