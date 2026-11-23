package com.cosmotune.video.downloads

import com.cosmotune.video.model.SubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A download's subtitles in a language the video has no captions in: the site's translation of its own captions. */
class SubtitleTranslateTest {
    private val auto = SubtitleTrack("https://example.com/api/timedtext?v=x&lang=en&kind=asr&fmt=vtt", "text/vtt", "en", "English (auto-generated)", true)
    private val human = SubtitleTrack("https://example.com/api/timedtext?v=x&lang=en&fmt=vtt", "text/vtt", "en", "English", false)
    private val other = SubtitleTrack("https://example.com/subs.ttml", "application/ttml+xml", "en", "English", false)

    @Test fun aMissingLanguageIsTranslatedFromThePersonsTrack() {
        val picked = SubtitleFiles.pick(SubtitlePick(true, "ta"), listOf(auto, human), appLanguage = "ta")
        assertEquals("https://example.com/api/timedtext?v=x&lang=en&fmt=vtt&tlang=ta", picked.track?.url)
        assertEquals("ta", picked.track?.language)
        assertEquals("ta (auto-translated)", picked.label)
    }

    @Test fun aLanguageTheVideoHasIsNotTranslated() {
        assertEquals(human, SubtitleFiles.pick(SubtitlePick(true, "en"), listOf(auto, human), appLanguage = "ta").track)
    }

    @Test fun onlyTheSitesOwnCaptionsCanBeTranslated() {
        assertNull(SubtitleFiles.translated(listOf(other), "ta"))
        assertNull(SubtitleFiles.translated(listOf(auto.copy(url = auto.url + "&tlang=fr")), "ta"))
    }
}
