package com.cosmotune.video.ui.channel

/**
 * The addresses that Share hands out for a channel and for a playlist of the source (spec 5.12 shares a video as its short address;
 * these two have no short form). Only an address: nothing the user reads. The one file of the channel and playlist pages that names the site.
 */
internal object ShareLinks {
    private const val SITE = "https://www.youtube.com"

    /** A channel's page. */
    fun channel(channelId: String): String = "$SITE/channel/$channelId"

    /** A playlist's page. */
    fun playlist(playlistId: String): String = "$SITE/playlist?list=$playlistId"
}
