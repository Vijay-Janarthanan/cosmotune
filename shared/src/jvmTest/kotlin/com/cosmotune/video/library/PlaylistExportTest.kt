package com.cosmotune.video.library

import com.cosmotune.video.importers.TakeoutPlaylistsImporter
import com.cosmotune.video.model.VideoItem
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistExportTest {
    private fun v(id: String) = VideoItem(id, "T $id", null, null, null, null, null, null, null, null)

    @Test fun linksAreOneAddressALine() {
        assertEquals(
            "https://www.youtube.com/watch?v=abcdefghijk\nhttps://www.youtube.com/watch?v=bcdefghijkl\n",
            PlaylistExport.text(listOf(v("abcdefghijk"), v("bcdefghijkl"), v("abcdefghijk")), PlaylistExport.Kind.LINKS, 0),
        )
    }

    @Test fun theCsvReadsBackAsATakeoutPlaylist() {
        val csv = PlaylistExport.text(listOf(v("abcdefghijk"), v("bcdefghijkl")), PlaylistExport.Kind.CSV, 1_700_000_000_000)
        assertEquals("Video ID,Playlist Video Creation Timestamp", csv.lineSequence().first())
        assertEquals("abcdefghijk,2023-11-14T22:13:20+00:00", csv.lines()[1])
        val back = TakeoutPlaylistsImporter.parse("Road trip.csv", csv, 0)
        assertEquals(listOf("abcdefghijk", "bcdefghijkl"), back.playlists.single().items.map { it.id })
    }

    @Test fun theFileIsNamedAfterThePlaylist() {
        assertEquals("Road trip.csv", PlaylistExport.fileName("Road trip", PlaylistExport.Kind.CSV))
        assertEquals("a b.txt", PlaylistExport.fileName("a/b", PlaylistExport.Kind.LINKS))
    }
}
