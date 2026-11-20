package com.cosmotune.music.playback.recovery

import com.cosmotune.music.data.innertube.StreamResolver
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackFailuresTest {
    /** Media3 and the coroutine machinery wrap twice before the error reaches the player. */
    private fun wrapped(cause: Throwable): Throwable =
        RuntimeException("playback failed", IllegalStateException("loader", cause))

    @Test
    fun aConnectionThatIsGoneIsANetworkFailure() {
        listOf(
            UnknownHostException("rr3---sn.googlevideo.com"),
            ConnectException("Connection refused"),
            SocketTimeoutException("timeout"),
            SocketException("Connection reset"),
        ).forEach { cause ->
            assertEquals(PlaybackFailureKind.NETWORK, PlaybackFailures.classify(wrapped(cause)), cause.toString())
        }
    }

    @Test
    fun thePlatformsOwnNetworkCodeIsEnough() {
        assertEquals(PlaybackFailureKind.NETWORK, PlaybackFailures.classify(IOException("opaque"), networkCode = true))
    }

    @Test
    fun anExpiredLinkIsStaleNotNetwork() {
        assertEquals(PlaybackFailureKind.STALE_LINK, PlaybackFailures.classify(IOException("403"), httpStatus = 403))
        assertEquals(PlaybackFailureKind.STALE_LINK, PlaybackFailures.classify(IOException("410"), httpStatus = 410))
        // A server that answered is a network that works, whatever the platform's code says.
        assertEquals(PlaybackFailureKind.STALE_LINK, PlaybackFailures.classify(IOException("403"), httpStatus = 403, networkCode = true))
    }

    @Test
    fun otherStatusesAndContentFailuresKeepTheOrdinaryRules() {
        assertEquals(PlaybackFailureKind.OTHER, PlaybackFailures.classify(IOException("404"), httpStatus = 404))
        assertEquals(PlaybackFailureKind.OTHER, PlaybackFailures.classify(IOException("500"), httpStatus = 500))
        assertEquals(PlaybackFailureKind.OTHER, PlaybackFailures.classify(wrapped(IllegalArgumentException("malformed container"))))
        assertEquals(PlaybackFailureKind.OTHER, PlaybackFailures.classify(null))
    }

    @Test
    fun aVerdictIsNeverWaitedOut() {
        val verdict = StreamResolver.PermanentlyUnplayableException("removed by the uploader")
        assertEquals(PlaybackFailureKind.OTHER, PlaybackFailures.classify(wrapped(verdict), networkCode = true, offline = true))
    }

    @Test
    fun offlineAnyReadFailureIsTheNetwork() {
        val opaque = wrapped(IOException("no client could resolve this track"))
        assertEquals(PlaybackFailureKind.OTHER, PlaybackFailures.classify(opaque))
        assertEquals(PlaybackFailureKind.NETWORK, PlaybackFailures.classify(opaque, offline = true))
        // Offline does not make a non-I/O failure the network's fault.
        assertEquals(PlaybackFailureKind.OTHER, PlaybackFailures.classify(wrapped(IllegalStateException("decoder")), offline = true))
    }

    @Test
    fun javaNetHttpTimeoutsAreMatchedByName() {
        assertEquals(
            PlaybackFailureKind.NETWORK,
            PlaybackFailures.classify(wrapped(java.net.http.HttpConnectTimeoutException("connect timed out"))),
        )
    }
}
