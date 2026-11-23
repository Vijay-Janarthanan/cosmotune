package com.cosmotune.video.transcript

import com.cosmotune.video.model.SubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptTest {
    @Test fun cuesBecomeLinesWithTheirStart() {
        val vtt = "WEBVTT\n\n00:00:01.500 --> 00:00:03.000\nHello <c>there</c>\nfriend\n\n01:02.000 --> 01:04.000\nTom &amp; Jerry\n"
        assertEquals(listOf(TranscriptLine(1_500, "Hello there friend"), TranscriptLine(62_000, "Tom & Jerry")), Transcript.parse(vtt))
    }

    @Test fun rollingRepeatsAreFolded() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nwe start\n\n00:00:02.000 --> 00:00:03.000\nwe start\n\n" +
            "00:00:03.000 --> 00:00:04.000\nwe start here today\n"
        assertEquals(listOf(TranscriptLine(1_000, "we start"), TranscriptLine(3_000, "here today")), Transcript.parse(vtt))
    }

    @Test fun theLinePlayingIsFound() {
        val lines = listOf(TranscriptLine(0, "a"), TranscriptLine(5_000, "b"))
        assertEquals(1, Transcript.indexAt(lines, 6_000))
        assertEquals(0, Transcript.indexAt(lines, 10))
    }

    @Test fun aPersonsTrackInTheWantedLanguageWins() {
        fun t(lang: String, auto: Boolean) = SubtitleTrack("u$lang$auto", "text/vtt", lang, lang, auto)
        val tracks = listOf(t("en", true), t("de", false), t("en-GB", false))
        assertEquals("en-GB", Transcript.pick(tracks, "en")?.language)
        assertEquals("de", Transcript.pick(tracks, null)?.language)
        assertEquals("en", Transcript.pick(listOf(t("en", true)), "fr")?.language)
    }
}
