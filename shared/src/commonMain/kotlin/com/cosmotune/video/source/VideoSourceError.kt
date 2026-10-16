package com.cosmotune.video.source

/**
 * Why the source could not answer (spec 4.2), each shown with its own message (plan task Y-1's `video_error_*` strings;
 * the mapping to `VideoUiError` is the controller's, plan task Y-10).
 */
sealed class VideoSourceError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The video was removed or never existed. */
    class NotAvailable(m: String) : VideoSourceError(m)

    /** Age-restricted: it cannot be played without an account. */
    class AgeRestricted(m: String) : VideoSourceError(m)

    /** Made for kids: not playable here. */
    class MadeForKids(m: String) : VideoSourceError(m)

    /** A private video. */
    class Private(m: String) : VideoSourceError(m)

    /** It needs a paid membership. */
    class Paid(m: String) : VideoSourceError(m)

    /** Not available in this country. */
    class GeoBlocked(m: String) : VideoSourceError(m)

    /** The source asked to confirm that the client is not a bot. */
    class BotCheck(m: String) : VideoSourceError(m)

    /** No connection, a timeout, or an HTTP failure. */
    class Network(m: String, c: Throwable?) : VideoSourceError(m, c)

    /** The source changed and its answer could not be read ("An update may fix this"). */
    class Parsing(m: String, c: Throwable?) : VideoSourceError(m, c)
}
