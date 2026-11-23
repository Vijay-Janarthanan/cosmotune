package com.cosmotune.watch

enum class WatchDensity(val label: String, val scale: Float) {
    COMPACT("Compact", .86f), BALANCED("Balanced", 1f), FLAGSHIP("Spacious", 1.18f),
}

@kotlinx.serialization.Serializable
data class WatchPreferences(val density: WatchDensity = WatchDensity.BALANCED, val animeSkips: Boolean = true, val debrid: WatchDebrid? = null,
    /** How Watch search orders results, kept per viewer. */
    val searchSort: WatchSort = WatchSort.BEST,
    /** How the player draws subtitles. */
    val subtitles: WatchSubtitleStyle = WatchSubtitleStyle(),
    /** Original languages (codes of [WatchLanguage]) whose Top 10, films, web series and daily soaps get their own rows on Home. */
    val homeLanguages: List<String> = listOf("ta", "hi"),
    /** How the player mixes the sound: as made, Night (loud and quiet evened out) or Clear voices. */
    val sound: com.cosmotune.video.player.AudioMix = com.cosmotune.video.player.AudioMix.NORMAL,
    /** How many episodes of a season download at once (1 to 3). */
    val parallelDownloads: Int = 1,
    /** Decode the picture in software only, for a device whose hardware decoder shows a broken picture. */
    val softwareDecoding: Boolean = false,
    /** On computers: send Dolby and DTS sound as it is to an amplifier. */
    val passthrough: Boolean = false)

/** A sound mix the player's Audio menu offers, in the words the menu shows. */
data class WatchSoundChoice(val mix: com.cosmotune.video.player.AudioMix, val label: String, val detail: String?) {
    companion object {
        val all = listOf(
            WatchSoundChoice(com.cosmotune.video.player.AudioMix.NORMAL, "Sound as made", null),
            WatchSoundChoice(com.cosmotune.video.player.AudioMix.NIGHT, "Night", "Loud parts quieter, quiet parts louder"),
            WatchSoundChoice(com.cosmotune.video.player.AudioMix.VOICES, "Clear voices", "Dialogue louder than music and effects"),
        )
        fun of(mix: com.cosmotune.video.player.AudioMix) = all.first { it.mix == mix }
    }
}