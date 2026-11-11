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
}
