package com.cosmotune.music

import com.cosmotune.cloud.SyncPlaylists
import com.cosmotune.music.data.cloud.CloudPlaylists
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.spotify.LocalPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone's playlists as cloud entries, and cloud entries written back without losing what only this phone knows. */
class CloudPlaylistsTest {
    private val localFile = Song(
        videoId = "local:42", title = "Field recording", artist = "Me", thumbnailUrl = null,
        localUri = "content://media/external/audio/media/42", localPath = "/storage/emulated/0/Music/field.flac", localDateAddedSeconds = 1_700_000_000L,
    )
    private val streamed = Song(videoId = "abc", title = "Song", artist = "Band", thumbnailUrl = "https://i.ytimg.com/abc.jpg", albumName = "Album")

    @Test fun anEntryIsKeyedByTheIdEveryDeviceShares() {
        val entries = CloudPlaylists.snapshot(listOf(
            LocalPlaylist("sp_local_1", "Mine", listOf(streamed)),
            LocalPlaylist("synced:sp_local_2", "From the desktop", emptyList()),
        ))
        assertEquals(setOf("sp_local_1", "sp_local_2"), entries.keys)
        assertEquals("Mine", SyncPlaylists.title(entries.getValue("sp_local_1")))
    }

    @Test fun noDeviceFieldIsEverSent() {
        val data = CloudPlaylists.snapshot(listOf(LocalPlaylist("sp_local_1", "Mine", listOf(localFile)))).getValue("sp_local_1").toString()
        assertFalse(data, data.contains("content://") || data.contains("/storage/") || data.contains("1700000000"))
        val back = SyncPlaylists.songs(SyncPlaylists.data("Mine", listOf(localFile))).single()
        assertNull(back.localUri)
        assertNull(back.localPath)
    }

    @Test fun aReplacedPlaylistKeepsItsIdItsPlaceAndItsSongsFiles() {
        val current = listOf(
            LocalPlaylist("sp_local_0", "Other", emptyList()),
            LocalPlaylist("synced:p1", "Old title", listOf(localFile, streamed)),
        )
        val cloud = SyncPlaylists.data("New title", listOf(streamed, localFile.copy(title = "Field recording (edit)")))
        val merged = CloudPlaylists.merge(current, mapOf("p1" to cloud), emptySet())
        assertEquals(listOf("sp_local_0", "synced:p1"), merged.map { it.id })
        val playlist = merged[1]
        assertEquals("New title", playlist.title)
        assertEquals(listOf("abc", "local:42"), playlist.songs.map { it.videoId })
        val file = playlist.songs[1]
        assertEquals("Field recording (edit)", file.title)
        assertEquals(localFile.localUri, file.localUri)
        assertEquals(localFile.localPath, file.localPath)
        assertEquals(localFile.localDateAddedSeconds, file.localDateAddedSeconds)
    }

    @Test fun aNewPlaylistComesFirstUnderTheSyncedPrefixAndADeleteRemovesEitherForm() {
        val current = listOf(LocalPlaylist("sp_local_1", "Gone", emptyList()), LocalPlaylist("synced:p2", "Also gone", emptyList()), LocalPlaylist("keep", "Kept", emptyList()))
        val merged = CloudPlaylists.merge(current, mapOf("p3" to SyncPlaylists.data("New", listOf(streamed))), setOf("sp_local_1", "p2"))
        assertEquals(listOf("synced:p3", "keep"), merged.map { it.id })
        assertEquals("New", merged.first().title)
        assertNull(merged.first().songs.single().localUri)
    }

    @Test fun aMergedPlaylistSnapshotsTheSameAsTheCloudEntry() {
        // Otherwise the journal would read the sync's own write as a local edit and send it straight back.
        val cloud = SyncPlaylists.data("Title", listOf(streamed, localFile))
        val merged = CloudPlaylists.merge(listOf(LocalPlaylist("p1", "Title", listOf(localFile))), mapOf("p1" to cloud), emptySet())
        assertEquals(cloud, CloudPlaylists.snapshot(merged).getValue("p1"))
    }

    @Test fun onlySongsTheDeviceHadKeepFiles() {
        val incoming = listOf(streamed, localFile.copy(localUri = null, localPath = null, localDateAddedSeconds = null))
        val kept = CloudPlaylists.keepDeviceFields(incoming, listOf(localFile))
        assertNull(kept[0].localUri)
        assertEquals(localFile.localPath, kept[1].localPath)
        assertEquals(incoming, CloudPlaylists.keepDeviceFields(incoming, emptyList()))
    }
}
