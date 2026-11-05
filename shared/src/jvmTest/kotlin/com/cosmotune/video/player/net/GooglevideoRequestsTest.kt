package com.cosmotune.video.player.net

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The request rules both engines and the downloads share (spec 6.2, 6.3). The addresses are made up: no real `ip=`,
 * signature or `n` value appears here.
 */
class GooglevideoRequestsTest {

    private val base = "https://rr3---sn-fake0000.googlevideo.com/videoplayback?expire=1792170000&id=o-FAKE&itag=137&c=WEB&mime=video%2Fmp4"

    @Test
    fun aVideoplaybackAddressIsOneOnAGooglevideoHost() {
        assertTrue(GooglevideoRequests.isVideoplayback(base))
        assertTrue(GooglevideoRequests.isVideoplayback("http://RR1---SN-FAKE.GOOGLEVIDEO.COM/videoplayback?itag=18"))
        assertTrue(GooglevideoRequests.isVideoplayback("https://rr1---sn-fake.googlevideo.com/videoplayback/id/abc/itag/18"))
        assertTrue(GooglevideoRequests.isVideoplayback("https://rr1---sn-fake.googlevideo.com:443/videoplayback"))
        assertFalse(GooglevideoRequests.isVideoplayback("https://example.com/videoplayback?itag=18"))
        assertFalse(GooglevideoRequests.isVideoplayback("https://googlevideo.com.example.net/videoplayback"))
        assertFalse(GooglevideoRequests.isVideoplayback("https://notgooglevideo.com/videoplayback"))
        assertFalse(GooglevideoRequests.isVideoplayback("https://rr1---sn-fake.googlevideo.com/generate_204"))
        assertFalse(GooglevideoRequests.isVideoplayback("https://rr1---sn-fake.googlevideo.com/videoplaybackx"))
        assertFalse(GooglevideoRequests.isVideoplayback("ftp://rr1---sn-fake.googlevideo.com/videoplayback"))
        assertFalse(GooglevideoRequests.isVideoplayback("not a url"))
        assertFalse(GooglevideoRequests.isVideoplayback(""))
    }

    @Test
    fun theRangeGoesIntoTheAddressAndReplacesAnOldOne() {
        assertEquals("$base&range=0-2097151", GooglevideoRequests.withRange(base, 0, 2_097_151))
        val withOld = "https://rr3---sn-fake0000.googlevideo.com/videoplayback?itag=137&range=10-20&c=WEB"
        assertEquals(
            "https://rr3---sn-fake0000.googlevideo.com/videoplayback?itag=137&c=WEB&range=2097152-4194303",
            GooglevideoRequests.withRange(withOld, 2_097_152, 4_194_303),
        )
        assertEquals("https://h.googlevideo.com/videoplayback?range=5-9", GooglevideoRequests.withRange("https://h.googlevideo.com/videoplayback", 5, 9))
        assertEquals("https://h.googlevideo.com/videoplayback?range=5-9", GooglevideoRequests.withRange("https://h.googlevideo.com/videoplayback?range=1-2", 5, 9))
        // a parameter whose name only ends in "range" is kept
        assertEquals("https://h.googlevideo.com/videoplayback?xrange=1&range=0-1", GooglevideoRequests.withRange("https://h.googlevideo.com/videoplayback?xrange=1", 0, 1))
    }

    @Test
    fun theRequestNumberGoesIntoTheAddressAndReplacesAnOldOne() {
        assertEquals("$base&rn=1", GooglevideoRequests.withRequestNumber(base, 1))
        assertEquals(
            "https://h.googlevideo.com/videoplayback?itag=18&burn=3&rn=7",
            GooglevideoRequests.withRequestNumber("https://h.googlevideo.com/videoplayback?itag=18&rn=6&burn=3", 7),
        )
        val both = GooglevideoRequests.withRequestNumber(GooglevideoRequests.withRange(base, 0, 99), 2)
        assertEquals("$base&range=0-99&rn=2", both)
        assertEquals("$base&range=100-199&rn=3", GooglevideoRequests.withRequestNumber(GooglevideoRequests.withRange(both, 100, 199), 3))
    }

    @Test
    fun aFragmentStaysAtTheEnd() {
        assertEquals("https://h.googlevideo.com/videoplayback?a=1&rn=4#frag", GooglevideoRequests.withRequestNumber("https://h.googlevideo.com/videoplayback?a=1#frag", 4))
    }

    @Test
    fun theClientIsReadFromC() {
        assertEquals("WEB", GooglevideoRequests.clientOf(base))
        assertEquals("ANDROID_VR", GooglevideoRequests.clientOf("https://h.googlevideo.com/videoplayback?itag=18&c=ANDROID_VR&cver=1.60"))
        assertNull(GooglevideoRequests.clientOf("https://h.googlevideo.com/videoplayback?itag=18&cver=1"))
        assertNull(GooglevideoRequests.clientOf("https://h.googlevideo.com/videoplayback?c="))
        assertNull(GooglevideoRequests.clientOf("https://h.googlevideo.com/videoplayback?abc=WEB"))
    }

    @Test
    fun thePostBodyIsXAndAZeroAndTheChunkIsTwoMebibytes() {
        assertContentEquals(byteArrayOf(0x78, 0x00), GooglevideoRequests.POST_BODY)
        GooglevideoRequests.POST_BODY[0] = 0      // a caller cannot change what the next request sends
        assertContentEquals(byteArrayOf(0x78, 0x00), GooglevideoRequests.POST_BODY)
        assertEquals(2_097_152L, GooglevideoRequests.CHUNK_BYTES)
    }
}
