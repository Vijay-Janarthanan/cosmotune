package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import java.io.File
import java.net.URI
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class M3uPlaylistExportTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun unicodeOrderDuplicatesAndDurationSurviveWithoutInjectingPlaylistLines() {
        val file = File(temporary.newFolder(), "தமிழ் #1.mp3").apply { writeBytes(byteArrayOf(1)) }
        val one = Song("local-one", "தமிழ்\n#EXT-X-KEY:secret", "Singer\rOther", null, "1:02:03", localPath = file.path)
        val two = one.copy(videoId = "local-two", title = "Another song", durationText = "LIVE", localPath = null, localUri = file.toURI().toString())
        val export = exportLocalM3u8("Evening\n#BAD", listOf(one, two, one))
        val lines = export.text.lines().filter(String::isNotBlank)
        assertEquals(3, export.included); assertEquals(0, export.skipped)
        assertEquals(3, lines.count { it.startsWith("#EXTINF:") })
        assertTrue(lines[2].startsWith("#EXTINF:3723,Singer Other - தமிழ் #EXT-X-KEY:secret"))
        assertTrue(lines[4].startsWith("#EXTINF:-1,")); assertTrue(lines.none { it.startsWith("#BAD") || it.startsWith("#EXT-X-KEY") })
        val addresses = lines.filter { !it.startsWith('#') }
        assertEquals(listOf(file, file, file).map { it.absoluteFile }, addresses.map { File(URI(it)).absoluteFile })
        assertTrue(addresses.first().contains("%23")); assertTrue(export.text.contains("தமிழ்"))
    }
    @Test fun onlyReadableLocalFilesCanLeaveTheAppAndSkippedCountsAreAccurate() {
        val file = temporary.newFile("song.flac")
        val local = Song("local", "Local", "Artist", null, localPath = file.path)
        val tracks = listOf(local, local.copy(localPath = "relative.mp3"), local.copy(localPath = null, localUri = "content://private/song"),
            local.copy(localPath = null, localUri = "https://server/audio?private-token"), local.copy(localPath = "${file.path}.missing"))
        val export = exportLocalM3u8("Local", tracks)
        assertEquals(1, export.included); assertEquals(4, export.skipped)
        assertFalse(export.text.contains("private-token")); assertFalse(export.text.contains("content://"))
        assertFailsWith<IllegalArgumentException> { exportLocalM3u8("Empty", emptyList()) }
        assertFailsWith<IllegalArgumentException> { exportLocalM3u8("Too many", List(10_001) { local }) }
    }
}
