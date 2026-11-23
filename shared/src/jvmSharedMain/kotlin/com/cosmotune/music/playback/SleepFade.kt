package com.cosmotune.music.playback

/**
 * The sleep timer's fade-out (upstream 1.10, GPL-3.0): over its last [FADE_MS] the volume goes down in a straight line to silence at
 * the deadline, then the music pauses and the volume is put back for the next play. Both apps' timers read this curve; the setting
 * is [com.cosmotune.settings.ExtrasSettings.sleepFadeOut].
 */
object SleepFade {
    const val FADE_MS = 30_000L

    /** The volume multiplier [remainingMs] before the deadline: 1 outside the fade, with the fade off, or with no timer (null). */
    fun gain(remainingMs: Long?, enabled: Boolean, fadeMs: Long = FADE_MS): Float {
        if (remainingMs == null || !enabled || fadeMs <= 0L || remainingMs >= fadeMs) return 1f
        if (remainingMs <= 0L) return 0f
        return (remainingMs.toFloat() / fadeMs).coerceIn(0f, 1f)
    }

    /** When, on the deadline's own clock, the fade begins. */
    fun startsAt(deadline: Long, enabled: Boolean, fadeMs: Long = FADE_MS): Long = if (enabled) deadline - fadeMs else deadline
}
