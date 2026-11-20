package com.cosmotune.cache

import com.cosmotune.music.data.model.MoodGenre
import com.cosmotune.music.data.model.MoodGenreSection
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Explore's tile covers are asked once, kept on the disk, and asked again at most once a day. */
class ExploreCoversTest {
    private val folder = Files.createTempDirectory("explore-covers").toFile()
    private val cache = PageCache(disk = { FileCacheDisk(folder) })
    private val now = longArrayOf(1_762_461_600_000L)

    @AfterTest fun clean() { folder.deleteRecursively() }

    private val sections = listOf(
        MoodGenreSection("Moods", listOf(MoodGenre("Chill", "FEmusic_moods", "chill"), MoodGenre("Focus", "FEmusic_moods", "focus"))),
        MoodGenreSection("Genres", listOf(MoodGenre("Tamil", "FEmusic_genres", "tamil"), MoodGenre("Own art", "FEmusic_genres", "art", thumbnailUrl = "https://own"))),
    )

    private fun store() = ExploreCoverStore(cache = { cache }, clock = { now[0] })

    /** A store's saved copy as the disk has it, once the background write lands. */
    private fun onDisk(): ExploreCoverStore.Saved = runBlocking {
        repeat(100) {
            val fresh = store().saved()
            if (fresh.covers.isNotEmpty()) return@runBlocking fresh
            Thread.sleep(20)
        }
        error("nothing was saved")
    }

    @Test fun eachCoverIsAskedOnceAndComesBackFromTheDiskAtTheNextStart() = runBlocking<Unit> {
        val asked = mutableListOf<String>()
        val shown = mutableMapOf<String, String>()
        val first = store()
        first.resolve(sections, fetch = { tile ->
            asked += tile.title
            when (tile.title) {
                "Chill" -> Result.success("https://chill")
                "Focus" -> Result.success(null) // a category with no cover: remembered as having none
                else -> Result.failure(RuntimeException("offline")) // not remembered: asked again next time
            }
        }) { tile, url -> shown[tile.title] = url }
        assertEquals(listOf("Chill", "Focus", "Tamil"), asked.sorted(), "every tile without art of its own, once; never one that has art")
        assertEquals(mapOf("Chill" to "https://chill"), shown)

        // The next start: the saved covers are applied at once, and only the tile that failed is asked.
        val saved = onDisk()
        assertTrue(now[0] - saved.refreshedAtMs < ExploreCoverStore.REFRESH_MS, "the full pass marked the day")
        val next = store()
        val applied = next.apply(sections)
        assertEquals("https://chill", applied[0].items[0].thumbnailUrl)
        assertNull(applied[0].items[1].thumbnailUrl)
        assertEquals("https://own", applied[1].items[1].thumbnailUrl, "a tile's own art is kept")
        asked.clear()
        now[0] += 60 * 60_000L
        next.resolve(applied, fetch = { tile -> asked += tile.title; Result.success("https://tamil") }) { _, _ -> }
        assertEquals(listOf("Tamil"), asked, "within the day, only the tile never answered")

        // Within the day again: nothing at all.
        asked.clear()
        next.resolve(next.apply(sections), fetch = { tile -> asked += tile.title; Result.success(null) }) { _, _ -> }
        assertEquals(emptyList(), asked, "a start within the day asks for no cover")
    }

    @Test fun theWholeSetIsAskedAgainOnceADayHasPassed() = runBlocking<Unit> {
        val first = store()
        first.resolve(sections, fetch = { Result.success("https://${it.params}") }) { _, _ -> }
        now[0] += ExploreCoverStore.REFRESH_MS + 1
        val asked = mutableListOf<String>()
        val shown = mutableMapOf<String, String>()
        val withCovers = first.apply(sections)
        first.resolve(withCovers, fetch = { tile -> asked += tile.title; Result.success("https://new-${tile.params}") }) { tile, url -> shown[tile.title] = url }
        assertEquals(listOf("Chill", "Focus", "Tamil"), asked.sorted(), "a day on, the kept covers are refreshed in the background")
        assertEquals("https://new-chill", shown["Chill"])
        assertEquals("https://new-chill", first.apply(sections)[0].items[0].thumbnailUrl, "the fresh cover replaces the kept one")
    }

    @Test fun aCoverLandsOnEveryTileOfTheSameRequest() {
        val twice = listOf(MoodGenreSection("A", listOf(MoodGenre("Chill", "b", "p"))), MoodGenreSection("B", listOf(MoodGenre("Chill again", "b", "p"))))
        val out = ExploreCoverStore.withCover(twice, twice[0].items[0], "https://x")
        assertEquals(listOf("https://x", "https://x"), out.flatMap { it.items }.map { it.thumbnailUrl })
    }
}
