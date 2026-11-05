package com.cosmotune.music.download

import com.cosmotune.music.data.downloads.OfflineHlsManifest
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.*

class OfflineMusicPackageTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun failedRetryPreservesThePublishedGenerationAndRemovesOnlyItsOwnStagingFiles() {
        val root = temporary.newFolder()
        val first = OfflineMusicPackage.buildFiles(root, "recording") { folder ->
            File(folder, "segment-00000.m4s").writeText("original audio")
            OfflineMusicPackage.write(File(folder, "playlist.m3u8"), "original playlist".toByteArray())
        }
        assertThrows(IllegalStateException::class.java) {
            OfflineMusicPackage.buildFiles(root, "recording") { folder ->
                File(folder, "segment-00000.m4s").writeText("incomplete retry")
                error("network interrupted")
            }
        }
        assertEquals("original playlist", first.readText())
        assertEquals("original audio", File(first.parentFile, "segment-00000.m4s").readText())
        assertEquals(listOf(first.parentFile.name), root.listFiles().orEmpty().map { it.name })
    }
    @Test fun hashCollisionsAndAnotherQualityCannotReuseOrOverwriteAnExistingPackage() {
        // These strings collide under the old Java hashCode directory naming.
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val root = temporary.newFolder()
        fun create(id: String, value: String) = OfflineMusicPackage.buildFiles(root, id) { folder ->
            OfflineMusicPackage.write(File(folder, "playlist.m3u8"), value.toByteArray())
        }
        val one = create("Aa", "first"); val other = create("BB", "other recording"); val retry = create("Aa", "better quality")
        assertEquals(3, listOf(one.parentFile, other.parentFile, retry.parentFile).distinct().size)
        assertEquals("first", one.readText()); assertEquals("other recording", other.readText()); assertEquals("better quality", retry.readText())
    }
    @Test fun partialLiveEncryptedAndExternallyReferencedPlaylistsAreRejected() {
        assertEquals(listOf("audio.ts"), OfflineHlsManifest.parse("#EXTM3U\naudio.ts\n#EXT-X-ENDLIST", false).media)
        for (directive in listOf("#EXT-X-BYTERANGE:100@0", "#EXT-X-KEY:METHOD=NONE\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"",
            "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"elsewhere\"", "#EXT-X-PRELOAD-HINT:TYPE=PART,URI=\"unfinished\"")) assertThrows(Exception::class.java) {
            OfflineHlsManifest.parse("#EXTM3U\n$directive\naudio.ts\n#EXT-X-ENDLIST", false)
        }
        assertThrows(Exception::class.java) { OfflineHlsManifest.parse("#EXTM3U\naudio.ts", false) }
        assertThrows(Exception::class.java) { OfflineHlsManifest.parse("#EXTM3U\naudio.ts\n#EXT-X-ENDLIST", true) }
        for (repeat in listOf("-1", "2147483647", "9223372036854775807", "wrong")) assertThrows(Exception::class.java) {
            OfflineDash.parse("""<MPD><SegmentTemplate initialization="i" media="${'$'}Number${'$'}"><SegmentTimeline><S d="1" r="$repeat"/></SegmentTimeline></SegmentTemplate></MPD>""")
        }
    }
}
