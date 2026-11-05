package com.cosmotune.music.data

import kotlin.test.*

class YouTubeLocaleRegionTest {
    @Test fun simplifiedChineseUsesASupportedMusicRegionWithoutChangingItsLanguage() {
        for (tag in listOf("zh", "zh-CN", "zh-Hans", "zh-SG")) {
            assertEquals("SG", LocaleTags.ytGl(tag))
            assertEquals("zh-CN", LocaleTags.ytHl(tag))
        }
        assertEquals("TW", LocaleTags.ytGl("zh-Hant"))
        assertEquals("zh-TW", LocaleTags.ytHl("zh-Hant"))
        assertEquals("US", LocaleTags.ytGl("en"))
    }
}
