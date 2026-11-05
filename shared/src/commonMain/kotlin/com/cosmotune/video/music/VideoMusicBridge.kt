package com.cosmotune.video.music

import com.cosmotune.video.model.VideoItem

/**
 * Video asks Music to play a video as a song (spec 9: "Play as a song"; each host implements it: the phone through
 * Music's pending link request, the desktop through a "play this song" action).
 */
interface VideoMusicBridge {
    /** Switches to Music and plays [item]'s id as a song. */
    fun playAsSong(item: VideoItem)
}

/** No Music to hand over to (tests, previews): does nothing. */
object NoMusicBridge : VideoMusicBridge {
    override fun playAsSong(item: VideoItem) {}
}
