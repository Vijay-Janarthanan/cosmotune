package com.cosmotune.design

/** Which columns the desktop page of an album or a playlist has. Pure, like [BlockMetrics]. */
object CollectionColumns {
    /**
     * Whether the rows have a column for their album. An album page never does: every row is on the album the page is about. A
     * playlist does when at least one of its [albumNames] says an album, and then the rows that have none show a dash; when none of
     * them does the column would be a dash on every row, and the title and the artist take its room.
     */
    fun showsAlbum(isAlbumPage: Boolean, albumNames: Iterable<String?>): Boolean =
        !isAlbumPage && albumNames.any { !it.isNullOrBlank() }
}
