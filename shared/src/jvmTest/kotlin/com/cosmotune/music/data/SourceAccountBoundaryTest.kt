package com.cosmotune.music.data

import com.cosmotune.music.data.model.LikeStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import kotlin.test.*

class SourceAccountBoundaryTest {
    @After fun reset() { LikeState.replaceSourceRatings(emptyMap()); LikeState.clear() }
    @Test fun sourceRatingCannotReachTheYouTubeAccountEndpoint() = runBlocking<Unit> {
        val result = YtMusicRepository.rate("src:sflx-fixture::recording", LikeStatus.LIKE)
        assertTrue(result.isFailure)
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }
    @Test fun accountChangesRetainDeviceRatingsAndRefreshingThemPreservesAccountOverrides() {
        LikeState.set("youtube-recording", LikeStatus.LIKE)
        LikeState.replaceSourceRatings(mapOf("src:fixture::one" to LikeStatus.DISLIKE))
        assertEquals(LikeStatus.LIKE, LikeState.overrides.value["youtube-recording"])
        LikeState.clear()
        assertEquals(mapOf("src:fixture::one" to LikeStatus.DISLIKE), LikeState.overrides.value)
        LikeState.replaceSourceRatings(emptyMap())
        assertTrue(LikeState.overrides.value.isEmpty())
    }
}
