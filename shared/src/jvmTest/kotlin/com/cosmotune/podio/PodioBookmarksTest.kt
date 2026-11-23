package com.cosmotune.podio

import kotlin.test.*

class PodioBookmarksTest {
    private fun mark(at: Long, id: String = "podio:episode:e", created: Long = at) = PodioBookmark(id, at, "Episode", "Show", "https://publisher.example/e.mp3#x", createdMs = created)

    @Test fun marksCloseTogetherAreOneAndStationsAreNeverMarked() {
        var saved = PodioBookmarks.add(PodioSaved(), mark(60_000))
        saved = PodioBookmarks.add(saved, mark(61_500).copy(note = "  the good bit  "))
        assertEquals(1, saved.bookmarks.size)
        assertEquals("the good bit", saved.bookmarks.single().note)
        saved = PodioBookmarks.add(saved, mark(10_000, created = 99_999))
        assertEquals(listOf(10_000L, 61_500L), PodioBookmarks.of(saved, "podio:episode:e").map { it.positionMs })
        assertEquals(10_000L, PodioBookmarks.newestFirst(saved).first().positionMs)
        assertSame(saved, PodioBookmarks.add(saved, mark(5, id = "podio:radio:x")))
    }

    @Test fun aBookmarkCanBeNotedRemovedAndShared() {
        var saved = PodioBookmarks.add(PodioSaved(), mark(754_900))
        val key = saved.bookmarks.single().key
        saved = PodioBookmarks.note(saved, key, "quote")
        assertEquals("quote", saved.bookmarks.single().note)
        assertEquals("https://publisher.example/e.mp3#t=754", PodioBookmarks.linkAt(saved.bookmarks.single().url, 754_900))
        assertEquals("podio:episode:e", saved.bookmarks.single().item().id)
        assertTrue(PodioBookmarks.remove(saved, key).bookmarks.isEmpty())
    }

    @Test fun theLibraryKeepsAtMostFiveHundred() {
        val saved = (1..PodioBookmarks.MAX + 20).fold(PodioSaved()) { acc, n -> PodioBookmarks.add(acc, mark(n * 10_000L)) }
        assertEquals(PodioBookmarks.MAX, saved.bookmarks.size)
    }
}
