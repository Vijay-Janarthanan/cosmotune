package com.cosmotune.music

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.download.DownloadedCollection
import com.cosmotune.music.ui.screens.albumEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BC-20: the Local Music and Downloads folder that Cosmotune keeps (its own screen, with WebDAV upload) carries the upstream
 * folder fixes: a tag-derived album plays and numbers in release order, a downloaded release keeps its own order and leads, only
 * the played row lights, and the file operations stay where they belong. The grouping rules are pure; the screen wiring is
 * pinned by reading the source.
 */
class LocalFolderAuditTest {
    private fun song(id: String, title: String, album: String? = "Record", track: Int? = null, artist: String = "Artist") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null, albumName = album, trackNumber = track)

    @Test fun aTaggedAlbumIsListedInReleaseOrderAndADownloadedReleaseKeepsItsOwn() {
        val files = listOf(
            song("c", "Cee", track = 3), song("a", "Ay", track = 1), song("b", "Bee", track = 2),
            song("z", "Zed", album = "Later", track = 2), song("y", "Why", album = "Later", track = 1),
            song("n", "No album", album = null),
        )
        val downloaded = DownloadedCollection(
            id = "MPREb_later", title = "later", subtitle = "", thumbnailUrl = "art", playlist = false,
            songs = listOf(files[3], files[4]),
        )
        val entries = albumEntries(files, listOf(downloaded))
        assertEquals("the release asked for leads and claims its tag grouping", listOf("later", "Record"), entries.map { it.title })
        assertEquals("kept in the order it was downloaded in", listOf("z", "y"), entries[0].songs.map { it.videoId })
        assertEquals("a tag grouping is sorted by its own track numbers", listOf("a", "b", "c"), entries[1].songs.map { it.videoId })
        assertEquals("asked:MPREb_later", entries[0].key)
        assertEquals("tagged:Record", entries[1].key)
        assertTrue("untagged rows belong to the Songs tab only", entries.none { e -> e.songs.any { it.videoId == "n" } })
    }

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private fun read(path: String) = File(root, path).readText().replace("\r\n", "\n").replace(Regex("\\s+"), " ")

    @Test fun theScreenLightsOnlyThePlayedRowAndNumbersAnAlbumByItsTags() {
        val screen = read("app/src/main/java/com/cosmotune/music/ui/screens/LocalMusicScreen.kt")
        assertTrue("isSameTrackAs(" !in screen)
        assertEquals(2, Regex("""remember\(songs, currentSong\) \{ playingRowMatcher\(songs, currentSong\) \}""").findAll(screen).count())
        assertTrue("val numberedByTag = remember(songs) { songs.useTrackTags() }" in screen)
        assertTrue("trackNumber = if (numberedByTag) song.trackNumber ?: (index + 1) else (index + 1)," in screen)
        assertTrue("songs = group.sortedForAlbum()," in screen)
    }

    @Test fun deletingAndUploadingStayOnDownloadsSelections() {
        val screen = read("app/src/main/java/com/cosmotune/music/ui/screens/LocalMusicScreen.kt")
        assertTrue("selection mode exists only in Downloads", "val selectingDownloads = isDownloads && selectedDownloadIds.isNotEmpty()" in screen)
        assertTrue("albums are selected by key, not inferred from shared track ids", "fun toggleAlbumSelection(entry: AlbumEntry)" in screen)
        val host = read("app/src/main/java/com/cosmotune/music/MainActivity.kt")
        assertTrue("isDownloads = page.browseId == \"local:downloads\"," in host)
        assertTrue("delete goes through the download registry, never a raw file delete",
            "selected.forEach { song -> Downloads.delete(context, song.videoId) }" in host)
        assertTrue("WebDAV upload is offered only with a configured server",
            "if (com.cosmotune.music.data.webdav.WebDavConfig.isConfigured(webdavUrl)) { { selected -> uploadToWebDav(selected) } } else { null }" in host)
    }
}
