package com.cosmotune.desktop

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.desktop.DesktopPlaylist
import com.cosmotune.remote.*
import kotlin.test.*

class DesktopPlaylistSyncTest {
    @Test fun reviewedEditsKeepStableIdentityOrderAndNativeMetadata() {
        val before = DesktopPlaylist("native-id", "Before", listOf(Song("one", "One", "Artist", "local-cover"), Song("two", "Two", "Artist", null)))
        val incoming = SyncPlaylist("native-id", "Renamed", listOf(SyncTrack("two", "Two", "Artist"), SyncTrack("one", "New title", "Artist")))
        val updated = applyDesktopSyncedPlaylists(listOf(before), listOf(SyncRecord("playlist:native-id", SyncStamp(1, "peer"), playlist = incoming))) { null }.single()
        assertEquals("native-id", updated.id)
        assertEquals("Renamed", updated.title)
        assertEquals(listOf("two", "one"), updated.songs.map { it.videoId })
        assertEquals("local-cover", updated.songs.last().thumbnailUrl)
        assertEquals(incoming, desktopSyncPlaylists(listOf(updated)).single())
        assertTrue(applyDesktopSyncedPlaylists(listOf(updated), listOf(SyncRecord("playlist:native-id", SyncStamp(2, "peer"), deleted = true))) { null }.isEmpty())
    }

    @Test fun localPathsAreNotExportedOrPretendedAvailableOnTheOtherDevice() {
        val current = DesktopPlaylist("one", "Local", listOf(Song("local:one", "Local recording", "Artist", null, localPath = "C:/private/song.flac")))
        val portable = desktopSyncPlaylists(listOf(current)).single()
        assertNull(portable.tracks.single().fileHash)
        assertFailsWith<IllegalArgumentException> {
            applyDesktopSyncedPlaylists(emptyList(), listOf(SyncRecord("playlist:one", SyncStamp(1, "peer"), playlist = portable))) { null }
        }
        val fileIdentity = portable.copy(tracks = portable.tracks.map { it.copy(id = "file:" + "a".repeat(64)) })
        assertFailsWith<IllegalArgumentException> {
            applyDesktopSyncedPlaylists(emptyList(), listOf(SyncRecord("playlist:one", SyncStamp(1, "peer"), playlist = fileIdentity))) { null }
        }
    }
}
