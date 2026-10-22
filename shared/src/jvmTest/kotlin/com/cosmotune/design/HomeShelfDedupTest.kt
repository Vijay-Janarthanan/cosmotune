package com.cosmotune.design

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeShelfDedupTest {
    private fun shelf(title: String, count: Int) = HomeShelf(title = title,
        items = List(count) { ShelfItem("Item $it", "", null, "id-$it", null) })

    @Test fun browseShelfOmitsOnlyItsRepeatedHero() {
        val shelves = listOf(shelf("Picks", 3), shelf("More", 3))
        val plan = HomePlan.of(shelves)
        assertEquals(shelves[0].items.drop(1), HomePlan.shelfItems(shelves, plan, 0))
        assertEquals(shelves[1].items, HomePlan.shelfItems(shelves, plan, 1))
    }

    @Test fun recentsAndSingleItemShelfRemainComplete() {
        for (shelves in listOf(listOf(shelf("Recents", 3)), listOf(shelf("Picks", 1)))) {
            assertEquals(shelves[0].items, HomePlan.shelfItems(shelves, HomePlan.of(shelves), 0))
        }
    }
}
