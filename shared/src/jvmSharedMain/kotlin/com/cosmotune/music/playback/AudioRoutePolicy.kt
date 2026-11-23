package com.cosmotune.music.playback

/** What happened to where the sound goes. */
enum class RouteChange {
    /** Headphones, a Bluetooth device or a speaker became available. */
    OUTPUT_ADDED,

    /** An output went away (unplugged, switched off, out of range). */
    OUTPUT_REMOVED,

    /** On a computer: the output the listener picked is no longer there. */
    CHOSEN_OUTPUT_GONE,
}

enum class RouteAction { NONE, PAUSE }

/** Where the sound goes after a route change, and whether playback had to stop for it. */
data class RouteDecision(val action: RouteAction, val fallBackToDefault: Boolean = false)

/**
 * The one rule for audio route changes in Music, Video, Watch and Podio: what was paused stays paused, whatever comes or
 * goes, and what was playing pauses when its output goes away (so the room does not suddenly hear it). A newly
 * connected output never starts anything. A listener who turned on "resume after Bluetooth reconnects" has asked for
 * that separately; it is not part of this rule.
 */
object AudioRoutePolicy {
    fun decide(change: RouteChange, playing: Boolean): RouteDecision = when (change) {
        RouteChange.OUTPUT_ADDED -> RouteDecision(RouteAction.NONE)
        RouteChange.OUTPUT_REMOVED -> RouteDecision(if (playing) RouteAction.PAUSE else RouteAction.NONE)
        RouteChange.CHOSEN_OUTPUT_GONE ->
            RouteDecision(if (playing) RouteAction.PAUSE else RouteAction.NONE, fallBackToDefault = true)
    }
}

/**
 * Where the computer says that an output went away (the desktop watches its sound devices and reports here), so that Video's
 * player, which does not own those devices, can follow [AudioRoutePolicy] too.
 */
object AudioRouteEvents {
    private val flow = kotlinx.coroutines.flow.MutableSharedFlow<RouteChange>(extraBufferCapacity = 4)
    val changes: kotlinx.coroutines.flow.SharedFlow<RouteChange> = flow

    fun report(change: RouteChange) { flow.tryEmit(change) }

    /** The ids of the outputs before and after; an output that is gone is [RouteChange.OUTPUT_REMOVED], otherwise nothing is reported. */
    fun compare(before: Set<String>, after: Set<String>): RouteChange? = when {
        (before - after).isNotEmpty() -> RouteChange.OUTPUT_REMOVED
        (after - before).isNotEmpty() -> RouteChange.OUTPUT_ADDED
        else -> null
    }
}
