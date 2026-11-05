package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MusicPlaylistImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/playlists/$name")).readText(Charsets.UTF_8)
    private fun song(id: String, title: String = "Evening light", artist: String = "The Coast") = Song(id, title, artist, null)

    @Test fun m3uResolvesOnlyKnownLibraryLocationsAndPreservesOrderAndRepeatedTracks() {
        val folder = temporary.newFolder()
        val local = File(folder, "music/song.flac").apply { parentFile.mkdirs(); writeText("audio") }
        val recording = song("local").copy(localPath = local.path)
        val text = fixture("music-import.m3u8").replace("\${FILE_URI}", local.toURI().toASCIIString())
        val opened = mutableListOf<String>()
        val preview = MusicPlaylistImport.preview(MusicPlaylistFile("list.m3u8", text, folder.path), listOf(recording)) {
            opened += it.videoId; true
        }
        assertEquals("Road trip", preview.name)
        assertEquals(listOf("local", "local", "dQw4w9WgXcQ"), preview.songs.map { it.videoId })
        assertEquals(2, preview.skipped)
        assertEquals(0, preview.pending, "a missing EXTINF file must not silently search for a substitute")
        assertEquals(3, opened.size, "only matched library entries and supported catalogue IDs reach availability checks")
    }

    @Test fun grantedContentUrisMatchExactlyWithoutInventingNewAndroidGrants() {
        val recording = song("granted").copy(localUri = "content://media/external/audio/media/7")
        val text = "content://media/external/audio/media/7\ncontent://media/external/audio/media/8\n\\\\untrusted-server\\private\\song.flac"
        val preview = MusicPlaylistImport.preview(MusicPlaylistFile("list.m3u", text), listOf(recording)) { it.videoId == "granted" }
        assertEquals(listOf(recording), preview.songs)
        assertEquals(2, preview.skipped)
    }

    @Test fun csvQuotesUnicodeAndExplicitCatalogueMatchingKeepDuplicateOccurrences() = runBlocking<Unit> {
        val known = song("known", "Evening, light", "The \"Coast\"").copy(albumName = "Night")
        val initial = MusicPlaylistImport.preview(MusicPlaylistFile("tracks.csv", "\uFEFF" + fixture("music-import.csv")), listOf(known))
        assertEquals(listOf(known), initial.songs)
        assertEquals(2, initial.pending)
        assertEquals(1, initial.skipped)
        var calls = 0
        val matched = MusicPlaylistImport.match(initial, { row ->
            calls++
            assertEquals("New song", row.title)
            song("new", row.title, row.artist).copy(albumName = row.album)
        })
        assertEquals(1, calls, "the same recording is looked up once")
        assertEquals(listOf("known", "new", "new"), matched.songs.map { it.videoId })
        assertEquals(0, matched.pending)
    }

    @Test fun importedLinksAreParsedAsIdsInsteadOfFetchingRemotePlaylistDocuments() = runBlocking<Unit> {
        val preview = MusicPlaylistImport.preview(MusicPlaylistFile("links.txt", fixture("music-links.txt")), emptyList())
        assertEquals(listOf("dQw4w9WgXcQ", "dQw4w9WgXcQ"), preview.songs.map { it.videoId })
        assertEquals(3, preview.skipped)
        var calls = 0
        MusicPlaylistImport.match(preview, { calls++; null })
        assertEquals(0, calls, "plain links do not start catalogue lookups")
    }

    @Test fun matchingIsBoundedAndAFailedOrWrongSingerLookupCannotReplaceAnotherRow() = runBlocking<Unit> {
        val input = "title,artist\n" + (1..10).joinToString("\n") { "Song $it,Singer" }
        val preview = MusicPlaylistImport.preview(MusicPlaylistFile("songs.csv", input), emptyList())
        val active = AtomicInteger(); val peak = AtomicInteger()
        val matched = MusicPlaylistImport.match(preview, { row ->
            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                delay(10)
                when (row.number) { 1 -> error("network failure"); 2 -> song("cover", row.title, "Another Performer")
                    else -> song("ok${row.number}", row.title, row.artist) }
            } finally { active.decrementAndGet() }
        }, limit = 5)
        assertTrue(peak.get() <= 3)
        assertEquals(listOf("ok3", "ok4", "ok5"), matched.songs.map { it.videoId })
        assertEquals(5, matched.pending)
        assertEquals(2, matched.skipped)
    }

    @Test fun malformedEncodingsStreamingManifestsAndExcessRowsAreRefusedBeforeLookup() {
        assertFailsWith<IllegalStateException> { MusicPlaylistFile.decode("x.m3u", byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFailsWith<IllegalArgumentException> { MusicPlaylistImport.preview(MusicPlaylistFile("stream.m3u8", "#EXTM3U\n#EXT-X-VERSION:3\nsegment.aac"), emptyList()) }
        assertFailsWith<IllegalArgumentException> { MusicPlaylistImport.preview(MusicPlaylistFile("huge.txt", (1..10_001).joinToString("\n") { "dQw4w9WgXcQ" }), emptyList()) }
        assertFailsWith<IllegalArgumentException> { MusicPlaylistImport.preview(MusicPlaylistFile("empty.csv", "artist,album\nSinger,Album"), emptyList()) }
    }
}
