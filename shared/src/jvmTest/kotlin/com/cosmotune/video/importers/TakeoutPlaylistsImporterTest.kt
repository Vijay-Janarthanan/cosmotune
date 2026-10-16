package com.cosmotune.video.importers

import com.cosmotune.video.importers.ImportFixtures.NOW
import com.cosmotune.video.importers.ImportFixtures.V0
import com.cosmotune.video.importers.ImportFixtures.V1
import com.cosmotune.video.importers.ImportFixtures.V2
import com.cosmotune.video.importers.ImportFixtures.V3
import com.cosmotune.video.importers.ImportFixtures.V4
import com.cosmotune.video.importers.ImportFixtures.V5
import com.cosmotune.video.importers.ImportFixtures.V6
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TakeoutPlaylistsImporterTest {
    @Test
    fun readsTheCurrentLayoutAndNamesThePlaylistFromTheFileName() {
        val preview = TakeoutPlaylistsImporter.parse("Road trip-videos.csv", ImportFixtures.text("playlist-current.csv"), NOW)
        val playlist = preview.playlists.single()
        assertEquals("Road trip", playlist.name)
        assertEquals(listOf(V0, V1, V2, V3), playlist.items.map { it.id }, "in file order, the repeated video once")
        assertEquals(1, preview.skipped, "the repeated video")
        assertNull(preview.reason)
        assertEquals(ImportFixtures.ms("2024-05-01T10:00:00Z"), playlist.createdMs, "the oldest time added")
        assertEquals(ImportFixtures.ms("2025-01-20T18:45:00Z"), playlist.updatedMs, "the newest time added")
    }

    @Test
    fun itemsAreIdsUntilTheVideoIsOpened() {
        val item = TakeoutPlaylistsImporter.parse("Road trip-videos.csv", ImportFixtures.text("playlist-current.csv"), NOW).playlists.single().items.first()
        assertEquals(V0, item.title, "the title is the id until the first view")
        assertNull(item.channelId)
        assertNull(item.channelName)
        assertNull(item.durationSec)
        assertEquals("https://i.ytimg.com/vi/$V0/mqdefault.jpg", item.thumbnailUrl)
    }

    @Test
    fun readsTheOldLayoutWithItsMetadataBlock() {
        val preview = TakeoutPlaylistsImporter.parse("anything-videos.csv", ImportFixtures.text("playlist-old.csv"), NOW)
        val playlist = preview.playlists.single()
        assertEquals("Fixture, the old way", playlist.name, "the block's title, not the file name")
        assertEquals("takeout-PLfixtureoldplaylist0000000000000", playlist.id)
        assertEquals(listOf(V4, V5, V6), playlist.items.map { it.id })
        assertEquals(ImportFixtures.ms("2018-07-06T20:16:24Z"), playlist.createdMs)
        assertEquals(ImportFixtures.ms("2018-07-08T09:10:11Z"), playlist.updatedMs)
        assertEquals(0, preview.skipped, "the Video Id header line is a header, not a lost entry")
    }

    @Test
    fun theOldLayoutIsRecognisedBySecondLineColumnCountWhateverTheLanguage() {
        val text = "ID de lista,ID del canal,Hora de creaci\u00f3n,Hora de actualizaci\u00f3n,T\u00edtulo,Descripci\u00f3n,Visibilidad\r\n" +
            "PLspanishplaylist0000000000000000,UCDVWqyfkrFAZd6_6X-yZl_D,2020-01-02 03:04:05 UTC,2020-01-03 03:04:05 UTC,Mi lista,,Private\r\n" +
            "\r\nID de v\u00eddeo,Hora a\u00f1adida\r\n$V0,2020-01-02 03:04:05 UTC\r\n"
        val playlist = TakeoutPlaylistsImporter.parse("x.csv", text, NOW).playlists.single()
        assertEquals("Mi lista", playlist.name)
        assertEquals(listOf(V0), playlist.items.map { it.id })
    }

    @Test
    fun theOldLayoutWithoutATitleUsesTheFileName() {
        val text = "Playlist Id,Channel Id,Time Created,Time Updated,Title,Description,Visibility\r\nPLx,UCDVWqyfkrFAZd6_6X-yZl_D,,,,,Public\r\n\r\nVideo Id,Time Added\r\n$V0,2020-01-02 03:04:05 UTC\r\n"
        val playlist = TakeoutPlaylistsImporter.parse("BJJ - Butterfly Guard-videos.csv", text, NOW).playlists.single()
        assertEquals("BJJ - Butterfly Guard", playlist.name)
        assertEquals(ImportFixtures.ms("2020-01-02T03:04:05Z"), playlist.createdMs, "no time in the block: the oldest time added")
    }

    @Test
    fun theIndexGivesTheIdAndTheTimesOfAPlaylistWithTheSameTitle() {
        val index = TakeoutPlaylistsImporter.parseIndex(ImportFixtures.text("playlists-index.csv"))
        val playlist = TakeoutPlaylistsImporter.parse("Road trip-videos.csv", ImportFixtures.text("playlist-current.csv"), NOW, index).playlists.single()
        assertEquals("takeout-PLfixturecurrentplaylist0000000000", playlist.id)
        assertEquals(ImportFixtures.ms("2024-04-30T08:00:00Z"), playlist.createdMs)
        assertEquals(ImportFixtures.ms("2025-01-20T18:45:00Z"), playlist.updatedMs)
    }

    @Test
    fun theIndexIsReadByColumnName() {
        val index = TakeoutPlaylistsImporter.parseIndex(ImportFixtures.text("playlists-index.csv"))
        assertEquals(
            listOf(
                PlaylistIndexEntry("PLfixturecurrentplaylist0000000000", "Road trip", ImportFixtures.ms("2024-04-30T08:00:00Z"), ImportFixtures.ms("2025-01-20T18:45:00Z")),
                PlaylistIndexEntry("PLfixtureotherplaylist00000000000", "Workshop tips", ImportFixtures.ms("2023-01-02T03:04:05Z"), ImportFixtures.ms("2023-02-03T04:05:06Z")),
            ),
            index,
        )
        // another column order, and a header without the columns this reads
        val shuffled = "Playlist Title (Original),Playlist ID\r\nMix,PLmix\r\n"
        assertEquals(listOf(PlaylistIndexEntry("PLmix", "Mix", null, null)), TakeoutPlaylistsImporter.parseIndex(shuffled))
        assertTrue(TakeoutPlaylistsImporter.parseIndex(ImportFixtures.text("playlist-current.csv")).isEmpty())
        assertTrue(TakeoutPlaylistsImporter.parseIndex("").isEmpty())
    }

    @Test
    fun theNameIsThePartBeforeTheLastDashAndTheExtension() {
        fun name(file: String) = TakeoutPlaylistsImporter.nameFromFileName(file)
        assertEquals("BJJ - Butterfly Guard", name("BJJ - Butterfly Guard-videos.csv"))
        assertEquals("Road trip", name("Road trip-videos.csv"))
        assertEquals("Road trip", name("Takeout/YouTube and YouTube Music/playlists/Road trip-videos.csv"))
        assertEquals("Road trip", name("C:\\Users\\me\\Downloads\\Road trip-videos.csv"))
        assertEquals("Liked videos", name("Liked videos.csv"), "no dash: the whole name")
        assertEquals("Rock - Pop", name("Rock - Pop.csv"), "a dash with spaces around it is part of the name")
        assertEquals("-videos", name("-videos.csv"), "nothing before the dash: the whole name")
        assertEquals("Mix", name("Mix-V\u00eddeos.CSV"))
        assertEquals("Playlist", name(".csv"))
    }

    @Test
    fun aFileThatIsNotAPlaylistIsRefused() {
        val subscriptions = TakeoutPlaylistsImporter.parse("subscriptions.csv", ImportFixtures.text("takeout-subscriptions.csv"), NOW)
        assertEquals(ImportReason.UNREADABLE, subscriptions.reason)
        assertTrue(subscriptions.playlists.isEmpty())
        assertEquals(ImportReason.UNREADABLE, TakeoutPlaylistsImporter.parse("a.csv", "", NOW).reason)
        assertEquals(ImportReason.UNREADABLE, TakeoutPlaylistsImporter.parse("a.csv", "hello,world\r\nfoo,bar\r\n", NOW).reason)
    }

    @Test
    fun anEmptyPlaylistIsStillAPlaylistAndALostRowIsCounted() {
        val empty = TakeoutPlaylistsImporter.parse("Nothing-videos.csv", "Video ID,Playlist Video Creation Timestamp\r\n", NOW)
        assertEquals("Nothing", empty.playlists.single().name)
        assertTrue(empty.playlists.single().items.isEmpty())
        assertEquals(NOW, empty.playlists.single().createdMs)

        val lost = TakeoutPlaylistsImporter.parse("L-videos.csv", "Video ID,Playlist Video Creation Timestamp\r\n$V0,2024-05-01T10:00:00+00:00\r\nnot-a-video-id,2024-05-01T10:00:00+00:00\r\n\r\n", NOW)
        assertEquals(listOf(V0), lost.playlists.single().items.map { it.id })
        assertEquals(1, lost.skipped)
    }

    @Test
    fun theSameFileGivesTheSamePlaylistIdTwice() {
        val a = TakeoutPlaylistsImporter.parse("Road trip-videos.csv", ImportFixtures.text("playlist-current.csv"), NOW).playlists.single().id
        val b = TakeoutPlaylistsImporter.parse("Road trip-videos.csv", ImportFixtures.text("playlist-current.csv"), NOW + 1).playlists.single().id
        assertEquals(a, b)
        assertTrue(a.startsWith("takeout-"))
    }

    @Test
    fun theLayoutDecisions() {
        fun layout(text: String) = TakeoutPlaylistsImporter.layoutOf(Csv.parse(text))
        assertEquals(TakeoutPlaylistsImporter.Layout.CURRENT, layout(ImportFixtures.text("playlist-current.csv")))
        assertEquals(TakeoutPlaylistsImporter.Layout.OLD, layout(ImportFixtures.text("playlist-old.csv")))
        assertEquals(TakeoutPlaylistsImporter.Layout.INDEX, layout(ImportFixtures.text("playlists-index.csv")))
        assertEquals(TakeoutPlaylistsImporter.Layout.OTHER, layout(ImportFixtures.text("takeout-subscriptions.csv")))
        assertEquals(TakeoutPlaylistsImporter.Layout.OTHER, layout("${ImportFixtures.C0},http://x,Name\r\n"), "a subscriptions file without a header")
        assertEquals(TakeoutPlaylistsImporter.Layout.CURRENT, layout("$V0,2024-05-01T10:00:00+00:00\r\n"), "a file without a header")
        assertEquals(TakeoutPlaylistsImporter.Layout.OTHER, layout(""))
    }
}
