package com.cosmotune.audio

/** Shared phone/desktop contract; preloads obey the same policy as an explicit play. */
enum class PlaybackStartPolicy(val label: String, val description: String, val allowsAutomaticUpgrade: Boolean) {
    FAST_START("Fast start", "Start the first usable copy, then look for a better recording match.", true),
    BEST_QUALITY_FIRST("Best quality first", "Wait for the best available copy before playback starts.", false),
    NO_UPGRADE("No automatic upgrades", "Start quickly and keep the chosen copy for this play.", false);

    companion object {
        const val PREFERENCE_KEY = "playback_start_policy"
        fun fromStored(value: String?): PlaybackStartPolicy = entries.firstOrNull { it.name == value } ?: FAST_START
    }
}
