package com.cosmotune.watch

import com.cosmotune.watch.store.*
import com.cosmotune.watch.model.*
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import okhttp3.OkHttpClient

class WatchPlaylistRepositoryTest {
    @Test fun playlistEditsStayWithTheirViewerAndCloudRoundTripPreservesTheCollection() = runBlocking {
        val root = Files.createTempDirectory("watch-playlists").toFile()
        val store = WatchStore(root.resolve("watch.json"), UnsealedWatchFiles)
        val repository = DefaultWatchRepository(WatchConfig(OkHttpClient(), root, root.resolve("cache")), store, builtIns = { _, _ -> emptyList() })
        try {
            val id = assertNotNull(repository.createPlaylist("Weekend"))
            val movie = TitleSummary(WatchKey.tmdbFilm(603), TitleType.FILM, "The Matrix")
            repository.setPlaylistItem(id, movie, true)
            repository.setPlaylistItem(id, movie, true)
            assertEquals(1, repository.playlists.value.single().items.size)
            val viewer = assertNotNull(repository.addProfile("Vijay"))
            assertTrue(repository.selectProfile(viewer))
            assertTrue(repository.playlists.value.isEmpty())
            repository.setPreferences(WatchPreferences(WatchDensity.COMPACT))
            assertTrue(repository.selectProfile("default"))
            assertEquals(WatchDensity.BALANCED, repository.preferences.value.density)
            assertEquals(id, repository.playlists.value.single().id)
            repository.renamePlaylist(id, "Friday films")
            val restored = SyncWatchLibrary.apply(WatchState(), SyncWatchLibrary.snapshot(store.state.value), emptySet())
            assertEquals("Friday films", restored.allProfiles().getValue("default").playlists.single().name)
            assertEquals(movie.key, restored.allProfiles().getValue("default").playlists.single().items.single().key)
            repository.deletePlaylist(id)
            assertTrue(repository.playlists.value.isEmpty())
        } finally { repository.close(); root.deleteRecursively() }
    }

    @Test fun titlesAndPlaylistsKeepTheOrderSetAndTheirChosenCover() = runBlocking {
        val root = Files.createTempDirectory("watch-playlist-order").toFile()
        val store = WatchStore(root.resolve("watch.json"), UnsealedWatchFiles)
        val repository = DefaultWatchRepository(WatchConfig(OkHttpClient(), root, root.resolve("cache")), store, builtIns = { _, _ -> emptyList() })
        try {
            val id = assertNotNull(repository.createPlaylist("Heists"))
            val other = assertNotNull(repository.createPlaylist("Space"))
            listOf(603, 604, 605).forEach { repository.setPlaylistItem(id, TitleSummary(WatchKey.tmdbFilm(it), TitleType.FILM, "F$it", posterUrl = "https://img/$it.jpg"), true) }
            repository.movePlaylistItem(id, 2, 0)
            assertEquals(listOf("F605", "F603", "F604"), repository.playlists.value.first { it.id == id }.items.map { it.name })
            repository.movePlaylistItem(id, 0, 9)
            assertEquals(listOf("F603", "F604", "F605"), repository.playlists.value.first { it.id == id }.items.map { it.name }, "held to the list")
            repository.movePlaylist(other, 0)
            assertEquals(listOf(other, id), repository.playlists.value.map { it.id })
            assertEquals("https://img/603.jpg", repository.playlists.value.first { it.id == id }.cover)
            assertFalse(repository.setPlaylistPoster(id, "javascript:alert(1)"))
            assertTrue(repository.setPlaylistPoster(id, " https://example.com/cover.png "))
            assertEquals("https://example.com/cover.png", repository.playlists.value.first { it.id == id }.cover)
            assertTrue(repository.setPlaylistPoster(id, null))
            assertEquals("https://img/603.jpg", repository.playlists.value.first { it.id == id }.cover)
            // A note belongs to the profile that wrote it.
            val film = WatchKey.tmdbFilm(603)
            repository.setNote(film, "  Up to the lobby scene  ")
            assertEquals("Up to the lobby scene", repository.note(film))
            val kid = assertNotNull(repository.addProfile("Kid"))
            assertTrue(repository.selectProfile(kid))
            assertNull(repository.note(film))
            assertTrue(repository.selectProfile("default"))
            repository.setNote(film, " ")
            assertNull(repository.note(film))
        } finally { repository.close(); root.deleteRecursively() }
    }
}
