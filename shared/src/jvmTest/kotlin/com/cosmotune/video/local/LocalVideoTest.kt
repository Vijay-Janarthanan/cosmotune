package com.cosmotune.video.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules for video files on the device: ids, names, titles, extensions, the item the player gets and where a file resumes. */
class LocalVideoTest {

    @Test
    fun anIdIsThePrefixAndSixteenHexDigitsAndStaysTheSameForTheSamePath() {
        val id = LocalVideo.idFor("C:\\Videos\\Holiday.mp4")
        assertTrue(id.startsWith(LocalVideo.ID_PREFIX))
        assertEquals(LocalVideo.ID_PREFIX.length + 16, id.length)
        assertTrue(id.removePrefix(LocalVideo.ID_PREFIX).all { it in "0123456789abcdef" })
        assertEquals(id, LocalVideo.idFor("C:\\Videos\\Holiday.mp4"))
        assertEquals(id, LocalVideo.idFor("C:/Videos/Holiday.mp4"), "the two separators count the same")
        assertNotEquals(id, LocalVideo.idFor("C:/Videos/Holiday2.mp4"))
    }

    @Test
    fun theHashOfAnEmptyAddressIsTheOffsetBasis() {
        assertEquals("local:cbf29ce484222325", LocalVideo.idFor(""))
    }

    @Test
    fun onlyIdsWithThePrefixAreLocal() {
        assertTrue(LocalVideo.isLocal(LocalVideo.idFor("a.mp4")))
        assertFalse(LocalVideo.isLocal("dQw4w9WgXcQ"))
        assertFalse(LocalVideo.isLocal(null))
    }

    @Test
    fun theFileNameIsTheLastPartOfAPathAnAddressOrAnEscapedAddress() {
        assertEquals("clip.mp4", LocalVideo.fileName("/home/me/Videos/clip.mp4"))
        assertEquals("clip.mp4", LocalVideo.fileName("C:\\Users\\me\\Videos\\clip.mp4"))
        assertEquals("clip.mp4", LocalVideo.fileName("file:///sdcard/Movies/clip.mp4?x=1#t"))
        assertEquals("clip.mp4", LocalVideo.fileName("content://docs/document/primary%3AMovies%2Fclip.mp4").substringAfterLast(':'))
        assertEquals("My clip.mp4", LocalVideo.fileName("file:///v/My%20clip.mp4"))
        assertEquals("", LocalVideo.fileName(""))
    }

    @Test
    fun aTitleIsTheNameWithoutItsExtensionAndUnderscoresAsSpaces() {
        assertEquals("Holiday in Goa", LocalVideo.titleOf("/v/Holiday_in_Goa.mkv"))
        assertEquals("Holiday", LocalVideo.titleOf("Holiday.MP4"))
        assertEquals("archive.tar", LocalVideo.titleOf("archive.tar.mp4"))
        assertEquals("v1.2 final", LocalVideo.titleOf("v1.2 final"), "a dot that starts no extension stays")
        assertEquals("\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD \u0BAA\u0B9F\u0BAE\u0BCD", LocalVideo.titleOf("/v/\u0BA4\u0BAE\u0BBF\u0BB4\u0BCD \u0BAA\u0B9F\u0BAE\u0BCD.mp4"))
        assertEquals(LocalVideo.FALLBACK_TITLE, LocalVideo.titleOf("/v/.mp4"))
        assertEquals(LocalVideo.FALLBACK_TITLE, LocalVideo.titleOf(""))
    }

    @Test
    fun extensionsDecideWhatIsAVideoAndWhatIsASubtitle() {
        assertTrue(LocalVideo.isVideoName("a.MKV"))
        assertTrue(LocalVideo.isVideoName("a.webm"))
        assertFalse(LocalVideo.isVideoName("a.srt"))
        assertFalse(LocalVideo.isVideoName("a"))
        assertTrue(LocalVideo.isSubtitleName("a.en.SRT"))
        assertTrue(LocalVideo.isSubtitleName("a.ass"))
        assertFalse(LocalVideo.isSubtitleName("a.txt"))
        assertEquals("mp4", LocalVideo.extension("x.MP4"))
        assertEquals("", LocalVideo.extension("x."))
    }

    @Test
    fun theItemCarriesTheTitleAndTheLengthButNoChannelOrPicture() {
        val entry = LocalRecent("local:1", "/v/a.mp4", "A", durationMs = 125_900)
        val item = LocalVideo.itemFor(entry)
        assertEquals("local:1", item.id)
        assertEquals("A", item.title)
        assertEquals(125L, item.durationSec)
        assertNull(item.channelName)
        assertNull(item.thumbnailUrl)
        assertNull(LocalVideo.itemFor(entry.copy(durationMs = 0)).durationSec)
    }

    @Test
    fun aFileResumesWhereItWasLeftUnlessItWasLeftAtTheStartOrNearTheEndOrRememberingIsOff() {
        assertEquals(60_000, LocalVideo.resumeAt(60_000, 600_000, remember = true))
        assertEquals(0, LocalVideo.resumeAt(60_000, 600_000, remember = false))
        assertEquals(0, LocalVideo.resumeAt(2_000, 600_000, remember = true))
        assertEquals(0, LocalVideo.resumeAt(595_000, 600_000, remember = true))
        assertEquals(60_000, LocalVideo.resumeAt(60_000, 0, remember = true), "an unknown length still resumes")
    }
}
