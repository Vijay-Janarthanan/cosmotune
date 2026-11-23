package com.cosmotune.video.importers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FreeTube's and LibreTube's backups, written the way those apps write them (hand-made samples, no real listener's data). */
class ForkBackupImporterTest {
    private val now = 1_700_000_000_000L
    private val ch = "UC" + "a".repeat(22)

    private val freeTubeProfiles = """
        {"name":"All Channels","bgColor":"#000000","textColor":"#FFFFFF","subscriptions":[{"id":"$ch","name":"Desk Lab","thumbnail":"https://yt3/a.jpg"},{"id":"not-a-channel","name":"x"}],"_id":"allChannels"}
    """.trimIndent()

    private val freeTubeHistory = """
        {"videoId":"abcdefghijk","title":"Desk tour","author":"Desk Lab","authorId":"$ch","published":1690000000000,"lengthSeconds":600,"watchProgress":125.5,"timeWatched":1695000000000,"isLive":false,"type":"video","_id":"abcdefghijk"}
        {"videoId":"bad","title":"Broken","timeWatched":1}
    """.trimIndent()

    private val freeTubePlaylists = """
        {"playlistName":"Favorites","protected":true,"description":"","videos":[{"videoId":"abcdefghijk","title":"Desk tour","author":"Desk Lab","authorId":"$ch","lengthSeconds":600,"timeAdded":1695000000000,"playlistItemId":"p1"}],"_id":"favorites","createdAt":1690000000000,"lastUpdatedAt":1695000000000}
    """.trimIndent()

    private val libreTube = """
        {"watchHistory":[{"videoId":"abcdefghijk","title":"Desk tour","uploadDate":"2023-07-22","uploader":"Desk Lab","uploaderUrl":"/channel/$ch","uploaderAvatar":null,"thumbnailUrl":"https://i/x.jpg","duration":600,"accessDate":1695000000000}],
         "watchPositions":[{"videoId":"abcdefghijk","position":125000}],
         "searchHistory":[{"query":"desk setup"},{"query":"desk setup"}],
         "subscriptions":[{"channelId":"$ch","url":"https://www.youtube.com/channel/$ch","name":"Desk Lab","avatar":"https://yt3/a.jpg","verified":false}],
         "localPlaylists":[{"playlist":{"id":3,"name":"Later","thumbnailUrl":""},"videos":[{"id":1,"playlistId":3,"videoId":"abcdefghijk","title":"Desk tour","uploader":"Desk Lab","thumbnailUrl":"","duration":600}]}],
         "playlistBookmarks":[{"playlistId":"PL123","playlistName":"Mix","thumbnailUrl":null,"uploader":"Someone","uploaderUrl":"/channel/$ch","videos":12}],
         "groups":[],"preferences":[]}
    """.trimIndent()

    @Test fun freeTubeFilesAreRecognised() {
        assertTrue(ForkBackupImporter.isFreeTube(freeTubeProfiles))
        assertTrue(ForkBackupImporter.isFreeTube(freeTubeHistory))
        assertTrue(ForkBackupImporter.isFreeTube(freeTubePlaylists))
        assertFalse(ForkBackupImporter.isFreeTube(libreTube))
        assertEquals(ImportFormat.FREETUBE, ImportDetector.detect("freetube-subscriptions-2026.db", freeTubeProfiles.encodeToByteArray()))
        assertEquals(ImportFormat.LIBRETUBE, ImportDetector.detect("libretube-backup.json", libreTube.encodeToByteArray()))
    }

    @Test fun freeTubeSubscriptionsHistoryAndPlaylists() {
        val subs = ForkBackupImporter.parseFreeTube(freeTubeProfiles, now)
        assertEquals(listOf(ch), subs.subscriptions.map { it.channelId })
        assertEquals(1, subs.skipped)
        val history = ForkBackupImporter.parseFreeTube(freeTubeHistory, now)
        val entry = history.history.single()
        assertEquals("abcdefghijk", entry.item.id)
        assertEquals(125_500, entry.positionMs)
        assertEquals(1_695_000_000_000, entry.lastWatchedMs)
        assertEquals(1, history.skipped)
        val lists = ForkBackupImporter.parseFreeTube(freeTubePlaylists, now)
        assertEquals("Favorites", lists.playlists.single().name)
        assertEquals(listOf("abcdefghijk"), lists.playlists.single().items.map { it.id })
    }

    @Test fun libreTubeBackup() {
        val p = ForkBackupImporter.parseLibreTube(libreTube, now)
        assertEquals(listOf(ch), p.subscriptions.map { it.channelId })
        assertEquals(125_000, p.history.single().positionMs)
        assertEquals(ch, p.history.single().item.channelId)
        assertEquals(listOf("desk setup"), p.searches)
        assertEquals("Later", p.playlists.single().name)
        assertEquals("PL123", p.savedPlaylists.single().id)
    }

    @Test fun nonsenseIsNotRead() {
        assertEquals(ImportReason.UNREADABLE, ForkBackupImporter.parseLibreTube("not json", now).reason)
        assertTrue(ForkBackupImporter.parseFreeTube("{}", now).isEmpty)
    }
}
