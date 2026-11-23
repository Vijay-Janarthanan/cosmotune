package com.cosmotune.manga.data

/** Series chosen by holding a cover (Komikku's bulk favourite): which to add to the library and which to take out. */
object MangaPicks {
    data class Split(val toAdd: List<MangaBook>, val toRemove: List<MangaBook>)

    fun toggle(chosen: List<MangaBook>, book: MangaBook): List<MangaBook> =
        if (chosen.any { it.id == book.id }) chosen.filterNot { it.id == book.id } else chosen + book

    fun split(chosen: List<MangaBook>, libraryIds: Set<String>): Split =
        chosen.partition { it.id !in libraryIds }.let { (add, remove) -> Split(add, remove) }
}
