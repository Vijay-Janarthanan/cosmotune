package com.cosmotune.manga.data

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaReadingHistoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun finalWebtoonPageIsUnreadUntilTheBottomWasReached() = runBlocking {
        val store = MangaStore(temporary.newFolder())
        val start = MangaProgress("book", "chapter", 0, 1, MangaReaderMode.WEBTOON, pageOffsetPx = 400)
        store.progress(start)
        assertFalse(start.finished)
        assertTrue(store.state.value.readChapters.isEmpty())
        val restored = MangaStore(store.root).state.value.progress.getValue("book")
        assertEquals(400, restored.pageOffsetPx); assertFalse(restored.finished)
        store.progress(start.copy(reachedEnd = true, pageOffsetPx = 1_500))
        assertEquals(setOf("chapter"), MangaStore(store.root).state.value.readChapters)
    }

    @Test fun finishingOtherChaptersDoesNotLoseReadHistoryAndUnreadSurvivesRestart() = runBlocking {
        val store = MangaStore(temporary.newFolder())
        val chapters = (1..3).map { MangaChapter("chapter-$it", "book", "source", "Chapter $it", "", it.toFloat()) }
        store.save(MangaBook("book", "source", "Story", chapters = chapters))
        chapters.take(2).forEach { store.progress(MangaProgress("book", it.id, 9, 10)) }
        store.navigate("book", chapters[1].id)
        val restored = MangaStore(store.root)
        assertEquals(setOf("chapter-1", "chapter-2"), restored.state.value.readChapters)
        assertEquals("chapter-2", restored.state.value.activeChapterId)
        restored.markRead(chapters[1], false)
        assertEquals(setOf("chapter-1"), MangaStore(store.root).state.value.readChapters)
        restored.remove("book")
        assertTrue(restored.state.value.readChapters.isEmpty())
        assertNull(restored.state.value.activeBookId)
        assertNull(restored.state.value.activeChapterId)
    }

    @Test fun nextChapterFollowsNumbersInTheSameEditionAndMissingMeansFinished() {
        val current = MangaChapter("one", "book", "source", "One", "", 1f, "en", scanlator = "Team A")
        val next = current.copy(id = "two", number = 2f)
        val book = MangaBook("book", "source", "Story", chapters = listOf(
            next, current.copy(id = "other-team", number = 1.5f, scanlator = "Team B"),
            current.copy(id = "other-language", number = 1.5f, language = "fr"), current))
        assertEquals(next, book.nextChapter(current))
        assertNull(book.nextChapter(next))
        assertNull(book.nextChapter(current.copy(id = "removed")))
    }

    @Test fun interruptedDownloadMetadataNeverBecomesAnOfflineChapter() = runBlocking {
        val store = MangaStore(temporary.newFolder())
        store.update { it.copy(downloads = mapOf("pending:chapter" to listOf(MangaPage("page", localFile = "missing")))) }
        assertTrue(MangaStore(store.root).state.value.downloads.isEmpty())
    }
}
