package com.cosmotune.music.desktop

/**
 * The two Automix options beside the main switch: blending on a manual skip, and a fixed phrase length in bars (8 or
 * 16, beat-aligned) instead of the adaptive planner's own choice. Both are off in a party, which only ever mixes with
 * the plain transition every member can reproduce; the stored choices are kept and apply again afterwards.
 */
internal object DesktopAutomixOptions {
    const val KEY_ON_SKIP = "automix_on_skip"
    const val KEY_PHRASE_BARS = "automix_phrase_bars"

    /** How long a blend started by a manual skip runs. */
    const val SKIP_MIX_SECONDS = 15

    /** Adaptive, then the two fixed lengths, in the order the settings offer them. */
    val PHRASE_CHOICES = listOf(0, 8, 16)

    /** A stored phrase length; anything but 8 or 16 (including an unreadable value) is the adaptive default. */
    fun phraseBars(stored: String): Int = stored.trim().toIntOrNull()?.takeIf { it == 8 || it == 16 } ?: 0

    /** What the engine plans with: a fixed length only while Automix itself is on and no party is running. */
    fun enginePhraseBars(automix: Boolean, inParty: Boolean, bars: Int): Int =
        if (automix && !inParty) phraseBars(bars.toString()) else 0

    /** Whether a manual skip may blend into the prepared next track. */
    fun skipMix(onSkip: Boolean, inParty: Boolean): Boolean = onSkip && !inParty
}
