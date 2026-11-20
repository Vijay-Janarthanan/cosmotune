package com.cosmotune.music.playback.recovery

import com.cosmotune.music.data.innertube.StreamResolver
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** What a failed stream needs, as far as recovery is concerned. */
enum class PlaybackFailureKind {
    /** The network is gone or not answering: keep the song and wait for it. */
    NETWORK,

    /** The server refused a link that has expired (403 or 410): resolve the song again. */
    STALE_LINK,

    /** Anything else — unplayable, removed, malformed: the ordinary retry-then-skip rules. */
    OTHER,
}

/**
 * Sorts a playback failure into a [PlaybackFailureKind], for the phone and the desktop alike.
 *
 * Each platform reads what only it can see — the HTTP status out of its own exception types, its
 * player's error code, whether the system says it is offline — and passes it here, so the decision
 * itself is the same on both.
 */
object PlaybackFailures {
    /** Statuses that mean the link has expired rather than that the song is gone. */
    val STALE_LINK_STATUSES = setOf(403, 410)

    /**
     * @param httpStatus the response code the stream answered with, when it answered at all.
     * @param networkCode the platform's own error code says connection failure or timeout.
     * @param offline the system currently reports no usable network.
     */
    fun classify(
        error: Throwable?,
        httpStatus: Int? = null,
        networkCode: Boolean = false,
        offline: Boolean = false,
    ): PlaybackFailureKind {
        // A verdict is a statement about the song, which no reconnect can change.
        if (causes(error).any { it is StreamResolver.PermanentlyUnplayableException }) return PlaybackFailureKind.OTHER
        if (httpStatus != null) {
            // A server that answered is a network that works.
            return if (httpStatus in STALE_LINK_STATUSES) PlaybackFailureKind.STALE_LINK else PlaybackFailureKind.OTHER
        }
        if (networkCode) return PlaybackFailureKind.NETWORK
        if (causes(error).any(::isNetworkException)) return PlaybackFailureKind.NETWORK
        // With the system saying there is no network, a read that failed failed for that reason.
        if (offline && causes(error).any { it is IOException }) return PlaybackFailureKind.NETWORK
        return PlaybackFailureKind.OTHER
    }

    /** The exception types a socket throws when the connection, rather than the content, is at fault. */
    fun isNetworkException(error: Throwable): Boolean = when (error) {
        is UnknownHostException,
        is ConnectException,
        is NoRouteToHostException,
        is PortUnreachableException,
        is SocketTimeoutException,
        is SocketException,
        -> true
        // java.net.http's timeouts are not on Android, so they are matched by name.
        is InterruptedIOException -> true
        is EOFException -> false
        else -> error.javaClass.simpleName in NAMED_NETWORK_EXCEPTIONS
    }

    private val NAMED_NETWORK_EXCEPTIONS = setOf(
        "HttpTimeoutException",
        "HttpConnectTimeoutException",
        // OkHttp's, when a stream is cut mid-body.
        "StreamResetException",
        "ConnectionShutdownException",
    )

    private fun causes(error: Throwable?): Sequence<Throwable> = sequence {
        var cause = error
        var depth = 0
        while (cause != null && depth++ < CAUSE_DEPTH) {
            yield(cause)
            cause = cause.cause?.takeIf { it !== cause }
        }
    }

    /** Media3 and the coroutine machinery both wrap; nothing nests deeper than this. */
    private const val CAUSE_DEPTH = 8
}
