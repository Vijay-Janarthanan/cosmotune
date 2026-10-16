package com.cosmotune.video.player.android

/**
 * The request headers of the stream set that is playing (spec 6.2: "the User-Agent the stream set was minted for"), which the
 * data source of Video's player puts on every request. [AndroidVideoEngine.load] replaces [current] before each load; the
 * data sources read it on the player's loader threads, so it is one volatile reference to an immutable map.
 *
 * Each load is its own [StreamSetRequests], which also remembers how that set's googlevideo requests have to be sent: a POST with
 * `&range=` (NewPipe's way, the default) or, after a 400 or 405 on the set's first response, a GET with a `Range` header
 * (Music's way; spec 6.2). A new set starts again with the POST.
 */
object VideoRequestHeaders {

    /**
     * The headers of one stream set ([values]: the User-Agent and whatever else the source said its addresses need; never a cookie,
     * never anything of an account) and whether its googlevideo requests follow the range rules ([rangeRules]: false for a live HLS
     * set, whose segment addresses take neither `&range=` nor `&rn=` nor the POST, as in NewPipe).
     */
    class StreamSetRequests(val values: Map<String, String>, val rangeRules: Boolean) {
        /** Set once a POST of this set was answered: from then on a 400 or 405 is an error, not a reason to switch. */
        @Volatile
        var postConfirmed: Boolean = false
            internal set

        /** Set when the set's first response refused the POST with a 400 or 405: every later request of the set is a GET with a `Range` header. */
        @Volatile
        var useGet: Boolean = false
            internal set
    }

    /** The set that is playing; an empty set with the range rules before the first load. */
    @Volatile
    var current: StreamSetRequests = StreamSetRequests(emptyMap(), rangeRules = true)
}
