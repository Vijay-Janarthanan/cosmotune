package com.cosmotune.music.desktop

import com.cosmotune.music.playback.recovery.PlaybackFailureKind
import com.cosmotune.music.playback.recovery.PlaybackFailures
import java.io.IOException
import java.net.NetworkInterface

/**
 * A stream that stopped before its end, with what is known about why.
 *
 * FFmpeg reports a dropped connection and a finished file the same way to anything that only asks
 * "is there more": both end the read. Before this, a song whose connection dropped mid-way simply
 * ended there and the queue moved on. The decoder now throws this instead, so the engine can tell
 * the two apart.
 */
internal class DesktopStreamException(
    message: String,
    /** The HTTP status the server answered with, when it answered. */
    val httpStatus: Int? = null,
    /** The read failed in a way only the connection causes (a timeout, a reset, no route). */
    val network: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

internal object DesktopStreamFailures {
    /** FFmpeg's words for a read error, as `av_strerror` gives them, turned into an exception. */
    fun fromFfmpeg(description: String): DesktopStreamException {
        val status = HTTP_STATUS.find(description)?.groupValues?.get(1)?.toIntOrNull()
        val network = status == null && NETWORK_WORDS.any { description.contains(it, ignoreCase = true) }
        return DesktopStreamException("stream read failed ($description)", status, network)
    }

    /** The shared decision ([PlaybackFailures]), fed what the desktop knows about [failure]. */
    fun classify(failure: Throwable?): PlaybackFailureKind {
        val stream = generateSequence(failure) { it.cause?.takeIf { cause -> cause !== it } }
            .take(8)
            .filterIsInstance<DesktopStreamException>()
            .firstOrNull()
        return PlaybackFailures.classify(
            error = failure,
            httpStatus = stream?.httpStatus,
            networkCode = stream?.network == true,
            offline = !DesktopNetwork.usable(),
        )
    }

    private val HTTP_STATUS = Regex("""Server returned (\d{3})""")

    /** What FFmpeg says (on Windows and elsewhere) when the connection, not the content, failed. */
    private val NETWORK_WORDS = listOf(
        "timed out",
        "connection reset",
        "connection refused",
        "connection aborted",
        "unreachable",
        "broken pipe",
        "input/output error",
        "i/o error",
        "network",
    )
}

/**
 * Whether this computer has a network to try, as the operating system sees it: an interface that
 * is up and not the loopback. The JVM has no event for a network coming back, so a reconnect that
 * is waiting polls this and goes early when it turns true. It can be fooled into true by a virtual
 * adapter, never into false, which is the safe way round: the backoff still runs either way.
 */
internal object DesktopNetwork {
    fun usable(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces()?.asSequence().orEmpty().any { nic ->
            nic.isUp && !nic.isLoopback && nic.inetAddresses.asSequence().any { !it.isLoopbackAddress && !it.isLinkLocalAddress }
        }
    }.getOrDefault(true)
}
