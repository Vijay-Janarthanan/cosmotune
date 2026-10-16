package com.cosmotune.video.player.controller

import com.cosmotune.video.player.PlayerErrorKind
import com.cosmotune.video.player.VideoUiError
import com.cosmotune.video.source.VideoSourceError
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** What the source and the engine report, and the message each one gets (spec 5.4, 6.1; the `video_error_*` strings). */
class ErrorMappingTest {

    private val sourceErrors: List<Pair<VideoSourceError, VideoUiError>> = listOf(
        VideoSourceError.NotAvailable("gone") to VideoUiError.UNAVAILABLE,
        VideoSourceError.AgeRestricted("age") to VideoUiError.AGE,
        VideoSourceError.MadeForKids("kids") to VideoUiError.KIDS,
        VideoSourceError.Private("private") to VideoUiError.PRIVATE,
        VideoSourceError.Paid("paid") to VideoUiError.PAID,
        VideoSourceError.GeoBlocked("geo") to VideoUiError.GEO,
        VideoSourceError.BotCheck("bot") to VideoUiError.BOT,
        VideoSourceError.Network("net", null) to VideoUiError.NETWORK,
        VideoSourceError.Parsing("parse", null) to VideoUiError.PARSING,
    )

    @Test
    fun everySourceErrorGetsItsOwnMessage() {
        for ((error, expected) in sourceErrors) assertEquals(expected, ErrorMapping.map(error), error::class.simpleName)
        assertEquals(sourceErrors.size, sourceErrors.map { it.second }.toSet().size, "no two source errors share a message")
    }

    @Test
    fun aSourceErrorReachesTheMappingEvenWhenOnlyKnownAsAThrowable() {
        val thrown: Throwable = VideoSourceError.GeoBlocked("geo")
        assertEquals(VideoUiError.GEO, ErrorMapping.map(thrown))
    }

    @Test
    fun anythingElseThatWasThrownReadsAsTheSourceHavingChanged() {
        assertEquals(VideoUiError.PARSING, ErrorMapping.map(IllegalStateException("boom")))
    }

    @Test
    fun theEnginesFailuresMapToTheNetworkOrToStopped() {
        assertEquals(VideoUiError.STOPPED, ErrorMapping.map(PlayerErrorKind.SOURCE_EXPIRED))
        assertEquals(VideoUiError.NETWORK, ErrorMapping.map(PlayerErrorKind.NETWORK))
        assertEquals(VideoUiError.STOPPED, ErrorMapping.map(PlayerErrorKind.DECODER))
        assertEquals(VideoUiError.STOPPED, ErrorMapping.map(PlayerErrorKind.UNSUPPORTED))
        assertEquals(VideoUiError.STOPPED, ErrorMapping.map(PlayerErrorKind.OTHER))
    }

    @Test
    fun everyMessageIsReachableFromSomeFailure() {
        val reached = sourceErrors.map { it.second }.toSet() + PlayerErrorKind.entries.map { ErrorMapping.map(it) }
        assertEquals(VideoUiError.entries.toSet(), reached)
    }

    @Test
    fun everyUiErrorHasItsStringInTheVideoModule() {
        // The strings are Y-1's, in :video; the key is video_error_ and the entry's name in lower case.
        val file = listOf("../video/src/commonMain/composeResources/values/strings.xml", "video/src/commonMain/composeResources/values/strings.xml")
            .map { File(it) }.firstOrNull { it.isFile }
        val text = assertNotNull(file, "the video module's strings.xml was not found from ${File(".").absoluteFile}").readText()
        for (error in VideoUiError.entries) {
            val key = "video_error_" + error.name.lowercase()
            assertTrue("name=\"$key\"" in text, "$key is not declared in the video module's strings.xml")
        }
    }
}
