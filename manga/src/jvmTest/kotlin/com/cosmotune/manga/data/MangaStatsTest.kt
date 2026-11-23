package com.cosmotune.manga.data

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaStatsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readChaptersAndReadingTimeAddUp() = runBlocking {
        val store = MangaStore(temporary.newFolder())
        val chapters = (1..3).map { MangaChapter("c$it", "b", "s", "Ch $it", "/$it", number = it.toFloat()) }
        store.save(MangaBook("b", "s", "Series", chapters = chapters))
        store.markRead(chapters[0], true); store.markRead(chapters[1], true)
        store.addReadingTime(5 * 60_000L); store.addReadingTime(10 * 60_000L)
        val stats = mangaStats(store.state.value)
        assertEquals(2, stats.chapters); assertEquals(2, stats.chaptersWeek)
        assertEquals(15 * 60_000L, stats.timeMs); assertEquals(15 * 60_000L, stats.timeWeekMs); assertEquals(1, stats.daysRead)
        assertEquals("Series" to 2, stats.top.single().let { it.first.title to it.second })
        store.markRead(chapters[1], false)
        assertEquals(1, mangaStats(store.state.value).chaptersWeek)
        store.update { it.copy(incognito = true) }
        store.addReadingTime(60_000L)
        assertEquals(15 * 60_000L, mangaStats(store.state.value).timeMs, "incognito adds no time")
        assertEquals("3 h 5 min", mangaDuration(185 * 60_000L))
    }
}
