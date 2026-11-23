package com.cosmotune.music.data.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeCaptionLyricsTest {
    private val srv3 = """<?xml version="1.0" encoding="utf-8" ?><timedtext format="3"><body>
        <p t="1200" d="2000">[Music]</p>
        <p t="3500" d="2100">♪ First line of the &amp; words ♪</p>
        <p t="6000" d="1800">second <s>line</s>
        here</p>
        <p t="8000">third&#39;s line</p>
        <p t="9500" d="900">fourth line</p>
        <p t="11000" d="900">(applause)</p>
        </body></timedtext>"""

    @Test fun captionsBecomeTimedLinesWithoutStageDirections() {
        val lines = YouTubeCaptionLyrics.parse(srv3)!!
        assertEquals(listOf("First line of the & words", "second line here", "third's line", "fourth line"), lines.map { it.text })
        assertEquals(listOf(3500L, 6000L, 8000L, 9500L), lines.map { it.timeMs })
        assertEquals(5600L, lines[0].sungUntilMs)
        assertNull(lines[2].sungUntilMs)
    }

    @Test fun aFewLinesAreNotLyrics() {
        assertNull(YouTubeCaptionLyrics.parse("""<p t="1" d="2">hello</p><p t="5" d="2">there</p>"""))
    }

    @Test fun onlyTheUploadersOwnTrackIsUsed() {
        val auto = CaptionTrack("https://www.youtube.com/api/timedtext?a", "en", automatic = true)
        val own = CaptionTrack("https://www.youtube.com/api/timedtext?b", "ta", automatic = false)
        assertEquals(own, YouTubeCaptionLyrics.pick(listOf(auto, own)))
        assertNull(YouTubeCaptionLyrics.pick(listOf(auto)))
    }

    @Test fun thePlayerAnswerListsItsTracks() {
        val body = """{"captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
            {"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=en","languageCode":"en","kind":"asr"},
            {"baseUrl":"https://www.youtube.com/api/timedtext?v=x&lang=hi","languageCode":"hi"},
            {"baseUrl":"https://elsewhere.example/timedtext","languageCode":"fr"}]}}}"""
        val tracks = YouTubeCaptionLyrics.tracks(body)
        assertEquals(listOf("en" to true, "hi" to false), tracks.map { it.language to it.automatic })
    }
}
