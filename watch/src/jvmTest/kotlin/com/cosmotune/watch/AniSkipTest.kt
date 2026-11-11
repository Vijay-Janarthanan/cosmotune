package com.cosmotune.watch

import com.cosmotune.watch.meta.AniSkip
import kotlinx.serialization.json.*
import kotlin.test.*

class AniSkipTest {
    @Test fun intervalsRequireTheSameEditAndCannotSeekOutsideTheEpisode() {
        val valid = Json.parseToJsonElement("""{"results":[{"skipType":"op","episodeLength":1440,"interval":{"startTime":25,"endTime":115}}]}""").jsonObject
        assertEquals(115_000L, AniSkip.parse(valid, 1_440_000).single().endMs)
        assertTrue(AniSkip.parse(valid, 1_500_000).isEmpty())
        val invalid = Json.parseToJsonElement("""{"results":[{"skipType":"op","episodeLength":100,"interval":{"startTime":-1,"endTime":200}}]}""").jsonObject
        assertTrue(AniSkip.parse(invalid, 100_000).isEmpty())
    }
}
