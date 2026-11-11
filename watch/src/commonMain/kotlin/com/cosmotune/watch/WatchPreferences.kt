package com.cosmotune.watch

enum class WatchDensity(val label: String, val scale: Float) {
    COMPACT("Compact", .86f), BALANCED("Balanced", 1f), FLAGSHIP("Spacious", 1.18f),
}

@kotlinx.serialization.Serializable
data class WatchPreferences(val density: WatchDensity = WatchDensity.BALANCED, val animeSkips: Boolean = true, val debrid: WatchDebrid? = null)
