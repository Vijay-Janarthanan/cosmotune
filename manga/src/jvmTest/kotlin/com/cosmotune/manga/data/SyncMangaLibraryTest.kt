package com.cosmotune.manga.data

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class SyncMangaLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun chapter(n: Int) = MangaChapter("kotatsu:X:chapter:$n", "kotatsu:X:1", "kotatsu:X", "Chapter $n", "/$n", number = n.toFloat())
    private val book = MangaBook("kotatsu:X:1", "kotatsu:X", "Series", "/s", "https://img/c.jpg", chapters = (1..3).map(::chapter), addedAtMs = 10)
    private val local = MangaBook("local:abc", "local", "My scan", chapters = listOf(MangaChapter("local:c", "local:abc", "local", "Read", "/l")))

    private val phone = MangaLibraryState(books = listOf(book, local), readChapters = setOf(chapter(1).id, chapter(2).id, "local:c"),
        progress = mapOf(book.id to MangaProgress(book.id, chapter(3).id, 4, 20)),
        collections = listOf(MangaCollection("col", "Favourites", setOf(book.id, local.id))),
        trackerAccounts = mapOf("mangaupdates" to MangaTrackerAccount("mangaupdates", "me", "session-secret")))

    @Test fun aSnapshotCarriesSeriesReadMarksPlacesAndCollectionsButNoSecretsOrLocalComics() {
        val snapshot = SyncMangaLibrary.snapshot(phone)
        assertEquals(setOf("series:${book.id}", "read:${book.id}", "place:${book.id}", "collection:col"), snapshot.keys)
        assertFalse(snapshot.toString().contains("session-secret"))
        assertFalse(snapshot.toString().contains("local:abc"))
    }

    @Test fun anotherDeviceGetsTheSeriesAndItsReadMarksWaitUntilItsChaptersLoad() = runBlocking {
        val tablet = SyncMangaLibrary.apply(MangaLibraryState(), SyncMangaLibrary.snapshot(phone), emptySet())
        val arrived = tablet.books.single()
        assertEquals(book.id, arrived.id); assertTrue(arrived.chapters.isEmpty()); assertEquals(10, arrived.addedAtMs)
        assertEquals(setOf(chapter(1).id, chapter(2).id), tablet.syncedRead.getValue(book.id))
        assertEquals(chapter(3).id, tablet.progress.getValue(book.id).chapterId)
        assertEquals(setOf(book.id), tablet.collections.single().bookIds)
        // The tablet's own snapshot still holds the waiting marks, so it never sends them back as unread.
        assertEquals(SyncMangaLibrary.snapshot(phone)["read:${book.id}"], SyncMangaLibrary.snapshot(tablet)["read:${book.id}"])

        val store = MangaStore(temporary.newFolder())
        store.update { tablet }
        store.refreshed(book)
        assertTrue(store.state.value.readChapters.containsAll(setOf(chapter(1).id, chapter(2).id)))
        assertTrue(store.state.value.syncedRead.isEmpty())
        assertTrue(store.state.value.newChapters.isEmpty(), "a first chapter list is never new")
    }

    @Test fun theNewerReadListWinsAndDeletesTravel() {
        val unread = mapOf("read:${book.id}" to SyncMangaLibrary.snapshot(phone.copy(readChapters = setOf(chapter(1).id))).getValue("read:${book.id}"))
        val next = SyncMangaLibrary.apply(phone, unread, emptySet())
        assertEquals(setOf(chapter(1).id, "local:c"), next.readChapters)
        val removed = SyncMangaLibrary.apply(phone, emptyMap(), setOf("series:${book.id}", "series:${local.id}"))
        assertEquals(listOf(local.id), removed.books.map { it.id }, "local comics never leave because of another device")
        assertEquals(setOf(local.id), removed.collections.single().bookIds)
    }

    @Test fun pageTurnsDoNotSignalASyncButAFinishedChapterDoes() {
        val turned = phone.copy(progress = mapOf(book.id to MangaProgress(book.id, chapter(3).id, 9, 20, updatedAtMs = 99)))
        assertEquals(SyncMangaLibrary.changeSignal(phone), SyncMangaLibrary.changeSignal(turned))
        assertNotEquals(SyncMangaLibrary.changeSignal(phone), SyncMangaLibrary.changeSignal(phone.copy(readChapters = phone.readChapters + chapter(3).id)))
    }
}
