package com.cosmotune.settings

/**
 * When the player keeps the screen from sleeping (Settings, Player, "Keep the screen on"; the idea from OuterTune's screen-on
 * policy and Echo's wakelock, own code). The lyrics, Cinema and a playing video always keep it on, as they did before; the other
 * two choices add to that. [label] is the menu's words, [short] the settings row's pill.
 */
enum class ScreenOnPolicy(val label: String, val short: String) {
    /** Only while the lyrics, Cinema or the song's video show. */
    LYRICS_OR_VIDEO("With lyrics or a video", "Lyrics, video"),

    /** Also while the player is open and a song plays. */
    PLAYER_OPEN("While the player is open", "Player open"),

    /** Also whenever a song plays, wherever you are in the app. */
    WHILE_PLAYING("Whenever music plays", "Always"),
    ;

    /**
     * Whether the screen stays on: [playerOpen] when the full player shows, [lyricsOrVideo] when the lyrics, Cinema or a video
     * show (which always holds it), [isPlaying] when a song plays.
     */
    fun keepsOn(playerOpen: Boolean, lyricsOrVideo: Boolean, isPlaying: Boolean): Boolean = when (this) {
        LYRICS_OR_VIDEO -> playerOpen && lyricsOrVideo
        PLAYER_OPEN -> playerOpen && (lyricsOrVideo || isPlaying)
        WHILE_PLAYING -> (playerOpen && lyricsOrVideo) || isPlaying
    }

    companion object {
        val DEFAULT = LYRICS_OR_VIDEO
        fun named(name: String?): ScreenOnPolicy = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
