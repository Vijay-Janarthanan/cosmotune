package com.cosmotune.video.player.controller

import com.cosmotune.video.player.PlayerErrorKind
import com.cosmotune.video.player.VideoUiError
import com.cosmotune.video.source.VideoSourceError

/**
 * What the player says when something fails (spec 5.4, 6.1): the source's errors and the engine's failures become the
 * [VideoUiError] whose `video_error_*` string the watch page shows. [VideoUiError.STOPPED] ("This video stopped loading",
 * with "Try again") is for everything that happens while playing and has no better message of its own.
 */
object ErrorMapping {
    /** The message of a source failure: one for each kind of [VideoSourceError]. */
    fun map(error: VideoSourceError): VideoUiError = when (error) {
        is VideoSourceError.NotAvailable -> VideoUiError.UNAVAILABLE
        is VideoSourceError.AgeRestricted -> VideoUiError.AGE
        is VideoSourceError.MadeForKids -> VideoUiError.KIDS
        is VideoSourceError.Private -> VideoUiError.PRIVATE
        is VideoSourceError.Paid -> VideoUiError.PAID
        is VideoSourceError.GeoBlocked -> VideoUiError.GEO
        is VideoSourceError.BotCheck -> VideoUiError.BOT
        is VideoSourceError.Network -> VideoUiError.NETWORK
        is VideoSourceError.Parsing -> VideoUiError.PARSING
    }

    /**
     * The message of an engine failure: the network is the network; an expired address that is reported here is one the
     * refresh budget could not mend, and a decoder or format failure or anything else has the general message.
     */
    fun map(kind: PlayerErrorKind): VideoUiError = when (kind) {
        PlayerErrorKind.NETWORK -> VideoUiError.NETWORK
        PlayerErrorKind.SOURCE_EXPIRED, PlayerErrorKind.DECODER, PlayerErrorKind.UNSUPPORTED, PlayerErrorKind.OTHER -> VideoUiError.STOPPED
    }

    /** The message of anything thrown by a source: a [VideoSourceError] as above, anything else as the source having changed. */
    fun map(error: Throwable): VideoUiError = if (error is VideoSourceError) map(error) else VideoUiError.PARSING
}
