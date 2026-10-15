package com.cosmotune.lyrics

/**
 * What the romanisation or translation under each line does when it is asked for, switched or put away: the old panel's
 * `rememberSubLyricsReveal` (`PlayerLyrics.kt`) as a plain decision. The words are held through the closing, so they fold away
 * instead of vanishing, and switching straight from one script to the other closes the first before opening the second, so two
 * scripts never share the gap.
 */
object SubReveal {
    enum class Plan {
        /** Reduced motion: it is there, with no movement. */
        SNAP_OPEN,

        /** Nothing is asked for and nothing is open: shut, forget the words. */
        SNAP_CLOSED,

        /** Show the asked-for words and open. */
        OPEN,

        /** Fold the words that are open, then show the asked-for ones and open. */
        CLOSE_THEN_OPEN,

        /** Fold the words that are open, then forget them. */
        CLOSE_THEN_CLEAR,
    }

    fun plan(reduced: Boolean, hasTarget: Boolean, hasShown: Boolean, sameAsShown: Boolean, progress: Float): Plan = when {
        reduced -> if (hasTarget) Plan.SNAP_OPEN else Plan.SNAP_CLOSED
        hasShown && !sameAsShown && progress > 0f -> if (hasTarget) Plan.CLOSE_THEN_OPEN else Plan.CLOSE_THEN_CLEAR
        hasTarget -> Plan.OPEN
        else -> Plan.SNAP_CLOSED
    }
}
