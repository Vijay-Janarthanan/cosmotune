package com.cosmotune.listening

import com.cosmotune.music.data.model.QueueTier
import com.cosmotune.music.data.model.Song
import java.nio.file.Files
import kotlin.test.*

class ListeningStoreTest {
    @Test fun independentWindowsKeepEachOthersUpdatesAndFailedWritesPreserveTheSnapshot() {
        val file = Files.createTempDirectory("cosmotune-listening-windows-").resolve("listening.json").toFile()
        val first = ListeningStore(file)
        val second = ListeningStore(file)
        first.saveSession("First window", listOf(song("one")), 0, 0, 0, false)
        second.saveSession("Second window", listOf(song("two")), 0, 0, 0, false)
        assertEquals(setOf("First window", "Second window"), ListeningStore(file).state.value.sessions.map { it.name }.toSet())
        assertEquals(second.export(), first.export())
        val before = file.readBytes()
        assertFailsWith<IllegalStateException> { first.update { error("Interrupted edit") } }
        assertContentEquals(before, file.readBytes())
        assertTrue(file.parentFile.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    private fun song(id: String, title: String = id) = Song(id, title, "Artist", null)
    private fun store() = ListeningStore(Files.createTempDirectory("cosmotune-listening-test-").resolve("listening.json").toFile())

    @Test fun queueSurvivesRestartWithDuplicateEntriesAndExactPosition() {
        val file = Files.createTempDirectory("cosmotune-session-").resolve("listening.json").toFile()
        val songs = listOf(song("one", "தமிழ் பாடல்"), song("one").copy(queueTier = QueueTier.USER_QUEUE), song("two"))
        ListeningStore(file).saveSession("  Evening / மாலை  ", songs, 1, 81342, 2, true)
        val saved = ListeningStore(file).state.value.sessions.single()
        assertEquals("Evening / மாலை", saved.name)
        assertEquals(listOf("one", "one", "two"), saved.tracks.map { it.id })
        assertEquals(QueueTier.USER_QUEUE, saved.tracks[1].song().queueTier)
        assertEquals(1, saved.index); assertEquals(81342L, saved.positionMs)
        assertEquals(2, saved.repeat); assertTrue(saved.shuffle)
    }

    @Test fun editsAndDeletionPersistWithoutChangingOtherSessions() {
        val store = store()
        store.saveSession("First", listOf(song("one")), 0, 0, 0, false)
        store.saveSession("Second", listOf(song("two")), 0, 0, 0, false)
        val first = store.state.value.sessions.last().id
        store.renameSession(first, "Renamed")
        assertEquals("Renamed", store.state.value.sessions.last().name)
        store.deleteSession(first)
        assertEquals("Second", store.state.value.sessions.single().name)
    }

    @Test fun unavailableCurrentTrackMovesToNextWithoutReusingItsPlayhead() {
        val q = ListeningSession(name = "Queue", tracks = listOf("a", "b", "c").map { ListeningTrack.of(song(it)) }, index = 1, positionMs = 12345)
        val restored = assertNotNull(q.playable { it.id != "b" })
        assertEquals(listOf("a", "c"), restored.tracks.map { it.id })
        assertEquals(1, restored.index); assertEquals(0L, restored.positionMs)
        val keptCurrent = assertNotNull(q.playable { it.id != "a" })
        assertEquals(0, keptCurrent.index); assertEquals(12345L, keptCurrent.positionMs)
        assertNull(q.playable { false })
        assertEquals(3, q.tracks.size)
    }

    @Test fun smartRulesIntersectAndUseCurrentLibraryAfterRestart() {
        val store = store()
        val rule = SmartPlaylist(name = "Offline discoveries", downloadedOnly = true, excludeRecent = true)
        store.saveSmart(rule)
        val library = ListeningLibrary(listOf(song("z"), song("a"), song("z"), song("b")),
            likedIds = setOf("a", "z", "b"), downloadedIds = setOf("a", "z"), recentIds = setOf("z"))
        assertEquals(listOf("a"), store.state.value.smartPlaylists.single().songs(library).map { it.videoId })
        assertEquals(listOf("a", "z"), rule.songs(library.copy(recentIds = emptySet())).map { it.videoId })
        assertTrue(rule.songs(ListeningLibrary()).isEmpty())
    }

    @Test fun inboxBaselinesThenDeduplicatesAndKeepsReadState() {
        val store = store()
        store.watchArtist("artist", "Artist")
        val old = ReleaseNotice("old", "artist", "Artist", "Old album")
        val new = ReleaseNotice("new", "artist", "Artist", "New album")
        store.acceptReleases("artist", listOf(old), 100)
        assertTrue(store.state.value.inbox.isEmpty())
        store.acceptReleases("artist", listOf(new, new, old), 200)
        assertEquals("new", store.state.value.inbox.single().id)
        store.markRead("new")
        store.acceptReleases("artist", listOf(old, new), 300)
        assertTrue(store.state.value.inbox.single().read)
        assertEquals(200L, store.state.value.inbox.single().discoveredAt)
    }

    @Test fun importedBackupsMergeIdempotentlyAndLocalEditsWin() {
        val original = store()
        original.saveSession("Original", listOf(song("a")), 0, 250, 1, false)
        val raw = original.export()
        val destination = store()
        destination.import(raw)
        val id = destination.state.value.sessions.single().id
        destination.renameSession(id, "Local edit")
        destination.import(raw); destination.import(raw)
        assertEquals("Local edit", destination.state.value.sessions.single().name)
        assertEquals(250L, destination.state.value.sessions.single().positionMs)
    }

    @Test fun failedImportsLeaveSavedDataUntouched() {
        val store = store()
        store.saveSession("Keep me", listOf(song("a")), 0, 0, 0, false)
        val before = store.export()
        assertFails { store.import("{not json}") }
        assertFails { store.import("""{"version":99}""") }
        assertEquals(before, store.export())
        assertFails { store.saveSession(" ", listOf(song("b")), 0, 0, 0, false) }
        assertEquals(before, store.export())
    }

    @Test fun corruptFilesAreKeptForRecovery() {
        val file = Files.createTempDirectory("cosmotune-corrupt-").resolve("listening.json").toFile()
        file.writeText("original broken bytes")
        assertFails { ListeningStore(file) }
        assertEquals("original broken bytes", file.readText())
    }

    @Test fun restoredTracksKeepRecordingOriginAndQueueIdentity() {
        val original = song("one").copy(
            artists = listOf(com.cosmotune.music.data.model.ArtistRef("Artist", "artist-id")),
            albumName = "Album", albumId = "album-id", localUri = "file:///music/song.flac", localPath = "/music/song.flac",
            downloadFormat = "FLAC", sourceQuality = "LOSSLESS", localDateAddedSeconds = 100,
            localDateModifiedSeconds = 200, isExplicit = true, isVideo = false, isVideoOrigin = true,
            playbackSource = "My playlist", playbackSourceType = com.cosmotune.music.data.model.PlaybackSourceType.BROWSE,
            playbackSourceId = "playlist-id", queueEntryId = "entry-id", setVideoId = "playlist-entry", radioName = "Station",
        )
        val first = store()
        first.saveSession("Complete metadata", listOf(original), 0, 1200, 0, false)
        val second = store()
        second.import(first.export())
        assertEquals(original, second.state.value.sessions.single().tracks.single().song())
    }

    @Test fun latestDiagnosticsSurviveRestartAndInvalidRuleIdsAreRejected() {
        val file = Files.createTempDirectory("cosmotune-diagnostics-").resolve("listening.json").toFile()
        val store = ListeningStore(file)
        val report = ListeningDiagnostics(12345, listOf(DiagnosticCheck("Catalogue", true, "Connected")))
        store.recordDiagnostics(report)
        assertEquals(report, ListeningStore(file).state.value.diagnostics)
        val before = store.export()
        assertFails { store.import("""{"smartPlaylists":[{"id":"same","name":"A"},{"id":"same","name":"B"}]}""") }
        assertEquals(before, store.export())
    }
}
