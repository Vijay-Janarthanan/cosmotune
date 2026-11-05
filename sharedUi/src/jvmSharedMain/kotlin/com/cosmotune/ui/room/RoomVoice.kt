package com.cosmotune.ui.room

import kotlin.math.roundToInt

/*
 * The voice rules the room screen draws from, on a plain JVM so they are tested without a screen: who rings as speaking, what the mic
 * button says, and how a volume reads. The screen never decides these inline, so the mic can never claim to be live by accident.
 */

/** What the mic button is showing, in the order the rules are checked. */
enum class RoomMicMode {
    /** Voice calls are not offered yet ([RoomUiState.voiceAvailable] false): a tap explains, nothing goes live. */
    UNAVAILABLE,

    /** The permission was refused for good: a tap opens the system settings, never the mic. */
    BLOCKED,

    /** The default: everyone joins muted. */
    MUTED,

    /** The room hears this device. */
    LIVE,
}

/** The mic button's mode. Live only when voice exists, the permission is there and the person turned it on. */
fun roomMicMode(state: RoomUiState): RoomMicMode = when {
    !state.voiceAvailable -> RoomMicMode.UNAVAILABLE
    state.micBlocked -> RoomMicMode.BLOCKED
    state.micLive -> RoomMicMode.LIVE
    else -> RoomMicMode.MUTED
}

/**
 * Whether [uid]'s face shows the speaking ring: voice exists, they are heard speaking, and this device still hears them (not muted
 * here, and not deafened, though your own ring stays while deafened, since the room still hears you).
 */
fun RoomUiState.showsSpeaking(uid: String): Boolean =
    voiceAvailable && uid in speakingUids && uid !in mutedHere && (!deafened || uid == selfUid)

/** This device's volume for [uid], 0 to 1; full when none was set. */
fun RoomUiState.volumeOf(uid: String): Float = roomVolume(volumes[uid])

/** A stored volume made safe to draw: missing or not a number is full, anything else is held to 0 to 1. */
fun roomVolume(raw: Float?): Float = if (raw == null || raw.isNaN()) 1f else raw.coerceIn(0f, 1f)

/** A volume as a whole percent, 0 to 100. */
fun roomVolumePercent(volume: Float): Int = (roomVolume(volume) * 100f).roundToInt()

/** How many of the mic meter's three bars are lit for [level] (0 to 1): at least one, since the meter only shows while speaking. */
fun roomMicMeterBars(level: Float): Int = when {
    level.isNaN() || level < 0.2f -> 1
    level < 0.5f -> 2
    else -> 3
}
