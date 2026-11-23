package com.cosmotune.watch

import com.cosmotune.watch.model.Subtitle
import com.cosmotune.watch.model.SubtitleOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleSourceTest {
    @Test fun theMenusNameTheAddOnThatFoundASubtitle() {
        assertEquals("OpenSubtitles v3", Subtitle("1", "https://x/1.srt", "en", "English", origin = SubtitleOrigin.ADDON, provider = "OpenSubtitles v3").sourceLabel)
        assertEquals("In the video", Subtitle("2", language = "en", label = "English", origin = SubtitleOrigin.EMBEDDED).sourceLabel)
        assertEquals("Your server", Subtitle("3", language = "en", label = "English", origin = SubtitleOrigin.SERVER).sourceLabel)
    }
}
