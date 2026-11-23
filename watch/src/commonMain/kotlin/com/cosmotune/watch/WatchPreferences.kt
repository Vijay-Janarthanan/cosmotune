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
    /** Days a title stays in Continue watching after it was last played; 0 keeps everything. */
    val continueDays: Int = 0,
    /** Decode the picture in software only, for a device whose hardware decoder shows a broken picture. */
    val softwareDecoding: Boolean = false,
    /** On computers: send Dolby and DTS sound as it is to an amplifier. */
    val passthrough: Boolean = false,
    /** Rating sources ("Letterboxd", "Trakt") the listener chose not to see on title pages. */
    val hiddenRatings: Set<String> = emptySet(),
    /** More like this from Simkl's "people who liked this also liked" (with Simkl connected) instead of TMDB's. */
    val simklPicks: Boolean = false,
    /** Anime this profile adds to AniList is kept private there (only you see it on your list). */
    val animePrivate: Boolean = false,
    /** On computers: the player's own options, written as in an mpv.conf. */
    val mpvOptions: String = "",
    /** How the player draws the picture: as made, brighter, vivid, softer or sharper. */
    val picture: com.cosmotune.video.player.PictureFilter = com.cosmotune.video.player.PictureFilter.NORMAL)

/** A picture look the player's Picture menu offers, in its words. */
data class WatchPictureChoice(val filter: com.cosmotune.video.player.PictureFilter, val label: String, val detail: String?) {
    companion object {
        val all = listOf(
            WatchPictureChoice(com.cosmotune.video.player.PictureFilter.NORMAL, "Picture as made", null),
            WatchPictureChoice(com.cosmotune.video.player.PictureFilter.BRIGHTER, "Brighter", "Lifts dark scenes"),
            WatchPictureChoice(com.cosmotune.video.player.PictureFilter.VIVID, "Vivid", "Richer colour"),
            WatchPictureChoice(com.cosmotune.video.player.PictureFilter.SOFTER, "Softer", "Less grain and blocks in old or small copies"),
            WatchPictureChoice(com.cosmotune.video.player.PictureFilter.SHARPER, "Sharper", "Crisper edges"),
        )

        /** The looks [engine] can draw; none when it draws only the picture as made. */
        fun of(engine: com.cosmotune.video.player.VideoPlayerEngine): List<WatchPictureChoice> =
            engine.pictureFilters.takeIf { it.size > 1 }?.let { offered -> all.filter { it.filter in offered } }.orEmpty()
    }
}

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

/** How long a title stays in Continue watching (Jellyfin's "Next up" cut-off): left longer ago than that, it drops off the row. */
object WatchContinueCutoff {
    val choices = listOf(0, 30, 90, 365)

    fun label(days: Int): String = when (days) {
        0 -> "Everything"; 30 -> "Last 30 days"; 90 -> "Last 3 months"; 365 -> "Last year"; else -> "Last $days days"
    }

    fun keep(items: List<com.cosmotune.watch.model.WatchProgress>, nowMs: Long, days: Int): List<com.cosmotune.watch.model.WatchProgress> =
        if (days <= 0) items else items.filter { nowMs - it.updatedAtMs <= days * 86_400_000L }
}

/** The player's types under Watch's own names, so the screens name Watch and never Video mode. */
typealias WatchAudioMix = com.cosmotune.video.player.AudioMix
typealias WatchPictureFilter = com.cosmotune.video.player.PictureFilter
typealias WatchEngineSnapshot = com.cosmotune.video.player.PlayerSnapshot
typealias WatchEngine = com.cosmotune.video.player.VideoPlayerEngine
