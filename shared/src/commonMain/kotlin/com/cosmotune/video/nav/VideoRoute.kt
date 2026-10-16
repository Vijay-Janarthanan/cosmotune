package com.cosmotune.video.nav

import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.shorts.ShortsOrigin

/**
 * Where Video mode is (spec 4.4): the page the shell draws is the top route of the back stack. Plain data, so a route can be
 * compared, kept and turned into one line of text ([encode]) for the phone's `Saver`, which keeps the stack across a
 * configuration change and while the mode is Music.
 *
 * The three tab roots are [Home], [Subscriptions] and [Library]. [Watch] opens the watch page for [Watch.videoId] from
 * [Watch.startMs] (or from where it was left, when null); [Shorts] is the full-screen pager of spec 5.15 (its queue comes
 * from [Shorts.origin], it opens at [Shorts.startVideoId], and [Shorts.seedKey] names the list a card was opened from in
 * `ShortsSeedCache`).
 */
sealed interface VideoRoute {
    /** The first tab. */
    data object Home : VideoRoute

    /** The second tab. */
    data object Subscriptions : VideoRoute

    /** The third tab. */
    data object Library : VideoRoute

    /** Search results for [query]; an empty query is the idle page with the recent searches. */
    data class Search(val query: String = "") : VideoRoute

    /** The watch page. */
    data class Watch(val videoId: String, val startMs: Long? = null) : VideoRoute

    /** The Shorts pager. */
    data class Shorts(val origin: ShortsOrigin, val startVideoId: String? = null, val seedKey: String? = null) : VideoRoute

    /** A channel's page, on [tab]. */
    data class Channel(val channelId: String, val tab: ChannelTab = ChannelTab.VIDEOS) : VideoRoute

    /** A playlist of the source. */
    data class Playlist(val playlistId: String) : VideoRoute

    /** One of your own playlists. */
    data class LocalPlaylist(val id: String) : VideoRoute

    /** Watch history. */
    data object History : VideoRoute

    /** Watch later. */
    data object WatchLater : VideoRoute

    /** Liked videos. */
    data object Liked : VideoRoute

    /** Remote playlists you saved. */
    data object SavedPlaylists : VideoRoute

    /** The channels you follow. */
    data object Channels : VideoRoute

    /** Downloads. */
    data object Downloads : VideoRoute

    /** Video settings. */
    data object Settings : VideoRoute

    /** Import and export. */
    data object Import : VideoRoute

    /** This route as one line of text with no `;` and no `|` in it; [decode] reads it back. */
    fun encode(): String = VideoRouteCodec.encode(this)

    companion object {
        /** The route [text] was made from by [encode], or null when it is not one (never throws). */
        fun decode(text: String): VideoRoute? = VideoRouteCodec.decode(text)
    }
}

/**
 * The text form of a route: a name, then its fields, with `:` between them (`watch:fakevideo01:125000`). A missing value is a
 * lone `~`; in a text field the five characters that mean something here (`% : ; | ~`) are written as `%` and their two
 * hexadecimal digits, so any text, in any script, comes back as it was.
 */
internal object VideoRouteCodec {
    private const val NONE = "~"

    fun encode(route: VideoRoute): String = when (route) {
        VideoRoute.Home -> "home"
        VideoRoute.Subscriptions -> "subs"
        VideoRoute.Library -> "library"
        is VideoRoute.Search -> "search:${escape(route.query)}"
        is VideoRoute.Watch -> "watch:${escape(route.videoId)}:${route.startMs?.toString() ?: NONE}"
        is VideoRoute.Shorts -> "shorts:${escape(encodeOrigin(route.origin))}:${optional(route.startVideoId)}:${optional(route.seedKey)}"
        is VideoRoute.Channel -> "channel:${escape(route.channelId)}:${route.tab.name}"
        is VideoRoute.Playlist -> "playlist:${escape(route.playlistId)}"
        is VideoRoute.LocalPlaylist -> "localplaylist:${escape(route.id)}"
        VideoRoute.History -> "history"
        VideoRoute.WatchLater -> "watchlater"
        VideoRoute.Liked -> "liked"
        VideoRoute.SavedPlaylists -> "savedplaylists"
        VideoRoute.Channels -> "channels"
        VideoRoute.Downloads -> "downloads"
        VideoRoute.Settings -> "settings"
        VideoRoute.Import -> "import"
    }

    fun decode(text: String): VideoRoute? {
        val parts = text.split(':')
        val name = parts[0]
        val fields = parts.size - 1
        fun field(i: Int): String? = unescape(parts[i])
        return when (name) {
            "home" -> VideoRoute.Home.takeIf { fields == 0 }
            "subs" -> VideoRoute.Subscriptions.takeIf { fields == 0 }
            "library" -> VideoRoute.Library.takeIf { fields == 0 }
            "search" -> if (fields == 1) field(1)?.let { VideoRoute.Search(it) } else null
            "watch" -> {
                if (fields != 2) return null
                val id = field(1) ?: return null
                val start = if (parts[2] == NONE) null else parts[2].toLongOrNull() ?: return null
                VideoRoute.Watch(id, start)
            }
            "shorts" -> {
                if (fields != 3) return null
                val origin = field(1)?.let { decodeOrigin(it) } ?: return null
                val start = if (parts[2] == NONE) null else unescape(parts[2]) ?: return null
                val seed = if (parts[3] == NONE) null else unescape(parts[3]) ?: return null
                VideoRoute.Shorts(origin, start, seed)
            }
            "channel" -> {
                if (fields != 2) return null
                val id = field(1) ?: return null
                val tab = ChannelTab.entries.firstOrNull { it.name == parts[2] } ?: return null
                VideoRoute.Channel(id, tab)
            }
            "playlist" -> if (fields == 1) field(1)?.let { VideoRoute.Playlist(it) } else null
            "localplaylist" -> if (fields == 1) field(1)?.let { VideoRoute.LocalPlaylist(it) } else null
            "history" -> VideoRoute.History.takeIf { fields == 0 }
            "watchlater" -> VideoRoute.WatchLater.takeIf { fields == 0 }
            "liked" -> VideoRoute.Liked.takeIf { fields == 0 }
            "savedplaylists" -> VideoRoute.SavedPlaylists.takeIf { fields == 0 }
            "channels" -> VideoRoute.Channels.takeIf { fields == 0 }
            "downloads" -> VideoRoute.Downloads.takeIf { fields == 0 }
            "settings" -> VideoRoute.Settings.takeIf { fields == 0 }
            "import" -> VideoRoute.Import.takeIf { fields == 0 }
            else -> null
        }
    }

    private fun encodeOrigin(origin: ShortsOrigin): String = when (origin) {
        ShortsOrigin.Home -> "home"
        ShortsOrigin.Subscriptions -> "subs"
        is ShortsOrigin.Channel -> "channel=${origin.channelId}"
        is ShortsOrigin.Search -> "search=${origin.query}"
        is ShortsOrigin.Related -> "related=${origin.videoId}"
    }

    private fun decodeOrigin(text: String): ShortsOrigin? = when {
        text == "home" -> ShortsOrigin.Home
        text == "subs" -> ShortsOrigin.Subscriptions
        text.startsWith("channel=") -> ShortsOrigin.Channel(text.removePrefix("channel="))
        text.startsWith("search=") -> ShortsOrigin.Search(text.removePrefix("search="))
        text.startsWith("related=") -> ShortsOrigin.Related(text.removePrefix("related="))
        else -> null
    }

    private fun optional(value: String?): String = if (value == null) NONE else escape(value)

    /** [text] with `% : ; | ~` written as `%` and two upper-case hexadecimal digits. */
    fun escape(text: String): String {
        if (text.none { it in SPECIAL }) return text
        val out = StringBuilder(text.length + 8)
        for (c in text) {
            if (c in SPECIAL) out.append('%').append(HEX[c.code shr 4]).append(HEX[c.code and 15]) else out.append(c)
        }
        return out.toString()
    }

    /** What [escape] was given, or null when [text] holds a `%` that is not followed by two hexadecimal digits. */
    fun unescape(text: String): String? {
        if ('%' !in text) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            if (i + 3 > text.length) return null
            val high = HEX.indexOf(text[i + 1])
            val low = HEX.indexOf(text[i + 2])
            if (high < 0 || low < 0) return null
            out.append(((high shl 4) or low).toChar())
            i += 3
        }
        return out.toString()
    }

    private const val SPECIAL_CHARS = "%:;|~"
    private val SPECIAL = SPECIAL_CHARS.toSet()
    private const val HEX = "0123456789ABCDEF"
}
