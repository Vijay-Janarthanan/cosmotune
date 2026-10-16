package com.cosmotune.video.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The desktop's EDL address (spec 6.3): one video and one audio stream played together, each address in EDL's length-prefixed
 * form so its commas and semicolons cannot end the entry. The length counts UTF-8 bytes, not characters. The addresses are
 * made up (loopback and example hosts).
 */
class EdlTest {

    @Test
    fun aPlainAddressIsPrefixedWithItsLength() {
        val url = "http://127.0.0.1:50123/0123456789abcdef0123456789abcdef/0"
        assertEquals(57, url.length)
        assertEquals("%57%$url", Edl.quote(url))
    }

    @Test
    fun commasAndSemicolonsStayInsideTheQuotedAddress() {
        val url = "https://media.example.com/v?a=1,2;b=3&c=x;y,z"
        assertEquals("%${url.length}%$url", Edl.quote(url))
        val joined = Edl.join(url, "https://media.example.com/a?k=;,")
        // Every separator outside the quoted parts belongs to the EDL grammar: strip the quoted parts and count.
        val bare = joined.replace(Edl.quote(url), "V").replace(Edl.quote("https://media.example.com/a?k=;,"), "A")
        assertEquals("edl://!new_stream;!no_clip;V;!new_stream;!no_clip;A", bare)
    }

    @Test
    fun theLengthIsInBytesForMultiByteCharacters() {
        val url = "C:\\Videos\\caf\u00e9 \u2615 \u65e5\u672c.mp4" // e acute (2 bytes), a hot drink sign (3), two CJK characters (3 each)
        val bytes = url.encodeToByteArray().size
        assertEquals(23, url.length)
        assertEquals(30, bytes)
        assertTrue(bytes > url.length, "multi-byte characters make the byte count larger than the character count")
        assertEquals("%30%$url", Edl.quote(url))
        val emoji = "/tmp/clip-\uD83C\uDFAC.webm"
        assertEquals("%${emoji.encodeToByteArray().size}%$emoji", Edl.quote(emoji))
        assertEquals(19, emoji.encodeToByteArray().size)
    }

    @Test
    fun joinPutsTheVideoFirstAndTheAudioSecondEachAsANewStreamWithoutClipping() {
        val video = "http://127.0.0.1:4000/token/0"
        val audio = "http://127.0.0.1:4000/token/1"
        assertEquals(
            "edl://!new_stream;!no_clip;%29%http://127.0.0.1:4000/token/0;!new_stream;!no_clip;%29%http://127.0.0.1:4000/token/1",
            Edl.join(video, audio),
        )
    }

    @Test
    fun anEmptyAddressIsQuotedAsZeroBytes() {
        assertEquals("%0%", Edl.quote(""))
    }
}
