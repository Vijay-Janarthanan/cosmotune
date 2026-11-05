package com.cosmotune.music.desktop

import com.cosmotune.cloud.CloudStatus
import com.cosmotune.cloud.SyncPlaylists
import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The playlist <-> cloud entry mapping and the status line, on plain lists: nothing here touches DesktopPersistence. */
class DesktopCloudPlaylistsTest {
    private val local = Song("local:one", "Recording", "Me", null, localPath = "C:/music/one.flac", localUri = "file:/C:/music/one.flac")
    private val remote = Song("abc", "Song", "Artist", "https://img/abc.jpg", durationText = "3:01")

    @Test fun entriesDropTheSyncedPrefixAndIncludeEmptyPlaylists() {
        val snapshot = cloudPlaylistSnapshot(listOf(DesktopPlaylist("synced:p1", "Mix", listOf(remote)), DesktopPlaylist("p2", "Empty")))
        assertEquals(listOf("p1", "p2"), snapshot.keys.toList())
        assertEquals("Mix", SyncPlaylists.title(snapshot.getValue("p1")))
        assertTrue(SyncPlaylists.songs(snapshot.getValue("p2")).isEmpty())
    }

    @Test fun anUpsertKeepsTheLocalIdPlaceAndDeviceOnlySongFields() {
        val current = listOf(DesktopPlaylist("first", "First"), DesktopPlaylist("synced:p1", "Old", listOf(local, remote)), DesktopPlaylist("last", "Last"))
        val incoming = SyncPlaylists.data("Renamed", listOf(remote.copy(title = "Song (edit)"), local.copy(localPath = null, localUri = null)))
        val next = applyCloudPlaylists(current, mapOf("p1" to incoming), emptySet())
        assertEquals(listOf("first", "synced:p1", "last"), next.map { it.id })
        val updated = next[1]
        assertEquals("Renamed", updated.title)
        assertEquals(listOf("abc", "local:one"), updated.songs.map { it.videoId })
        assertEquals("Song (edit)", updated.songs[0].title)
        assertEquals("C:/music/one.flac", updated.songs[1].localPath)
        // What was written reads back as exactly what came in, so the journal does not mistake it for a local edit.
        assertEquals(incoming, cloudPlaylistSnapshot(next).getValue("p1"))
    }

    @Test fun aNewPlaylistTakesTheEntryIdAndADeleteRemovesEitherSpelling() {
        val current = listOf(DesktopPlaylist("synced:gone", "Gone"), DesktopPlaylist("kept", "Kept"))
        val next = applyCloudPlaylists(current, mapOf("new" to SyncPlaylists.data("New", listOf(remote))), setOf("gone", "unknown"))
        assertEquals(listOf("kept", "new"), next.map { it.id })
        assertEquals(listOf(remote.videoId), next.last().songs.map { it.videoId })
        assertEquals(current, applyCloudPlaylists(current, emptyMap(), emptySet()))
    }

    @Test fun theStatusLineSaysWhoAndWhatTheSyncIsDoing() {
        val now = 10_000_000L
        val (guest, never) = cloudStatusText(CloudStatus(), DesktopCloud.Activity(), enabled = true, nowMs = now)
        assertEquals("Guest on this device", guest)
        assertEquals("Not synced yet", never)
        val google = CloudStatus(signedIn = true, isGuest = false, name = "Vijay", email = "v@example.com", lastSyncMs = now - 5 * 60_000L, lastError = "offline")
        val (who, detail) = cloudStatusText(google, DesktopCloud.Activity(), enabled = true, nowMs = now)
        assertEquals("Signed in as Vijay (v@example.com)", who)
        assertEquals("Last sync failed: offline · Last synced 5 min ago", detail)
        assertEquals("Waiting for your browser...", cloudStatusText(google, DesktopCloud.Activity(waitingForBrowser = true), true, now).second)
        assertEquals("Syncing... · Last synced 5 min ago", cloudStatusText(google.copy(syncing = true), DesktopCloud.Activity(), true, now).second)
    }

    @Test fun whileOffTheStatusLineSaysSoWhateverTheAccountLastSaid() {
        val google = CloudStatus(signedIn = true, isGuest = false, name = "Vijay", lastSyncMs = 1L, lastError = "offline")
        val (title, detail) = cloudStatusText(google, DesktopCloud.Activity(), enabled = false, nowMs = 10_000_000L)
        assertEquals("Cloud sync is off. Your library stays on this device.", title)
        assertEquals("Nothing is sent until you turn it on or sign in with Google", detail)
        val signedOut = DesktopCloud.Activity(message = "Signed out. Cloud sync is off on this device.")
        assertEquals(signedOut.message, cloudStatusText(CloudStatus(), signedOut, false, 0).second)
    }
}
