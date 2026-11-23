package com.cosmotune.music.playback.recovery

import com.cosmotune.music.data.innertube.StreamResolver

/**
 * Whether a song no source can play should be skipped past, as the phone does, rather than left on screen with an
 * error until the listener presses next.
 *
 * Counted per run, so it cannot spin: the same song coming round again (repeat-one, a queue of one) stops there, and
 * so does a run of [limit] dead songs in a row; a song that plays ends the run.
 */
class UnavailableSkips(private val limit: Int = DEFAULT_LIMIT) {
    private val run = LinkedHashSet<String>()

    @Synchronized fun shouldSkip(id: String): Boolean {
        if (id in run || run.size >= limit) {
            run.clear()
            return false
        }
        run += id
        return true
    }

    @Synchronized fun played() = run.clear()

    companion object {
        const val DEFAULT_LIMIT = 5

        /** A resolver verdict anywhere in [error]'s causes: the song cannot be played, not "not just now". */
        fun isUnavailable(error: Throwable?): Boolean =
            generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(8)
                .any { it is StreamResolver.PermanentlyUnplayableException }
    }
}
