package com.cosmotune.video.account

import com.cosmotune.music.data.DebugLog as Log
import com.cosmotune.video.model.VideoItem
import kotlinx.serialization.json.put

/**
 * The related videos of a watch page asked of the YouTube site's own `next` endpoint (the WEB client, [YoutubeWeb]): the second way
 * to "Up next" when the extractor the watch page is built on returns none. It needs no account; with a session
 * (`Innertube.cookie`) the same call is personalised by the site, so the list is the one the site would show that user.
 *
 * One request per [fetch]. The caller caches and bounds the calls (one per video, kept ten minutes); nothing here retries.
 * An answer that holds no videos and nothing unreadable is an honest empty list; an answer that held items this parser could not
 * read is [AccountFailure.Changed], logged by shape name only.
 */
internal class InnerTubeRelated(private val web: YoutubeWeb = InnertubeWeb) {

    /** The videos of [videoId]'s "Up next" column, in the site's order, [videoId] itself left out. Throws [AccountFailure]. */
    suspend fun fetch(videoId: String): List<VideoItem> {
        val response = web.post("next") {
            put("videoId", videoId)
            // The two flags the site's own player sends; without them a video that asks "are you sure" answers with the prompt only.
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        YoutubeFeedParser.errorOf(response)?.let { throw it }
        val feed = YoutubeFeedParser.parseRelated(response, videoId)
        if (feed.looksChanged) {
            Log.w(TAG, "related: nothing readable for a watch page that held ${feed.unreadable} unreadable items")
            throw AccountFailure.Changed("The related videos could not be read")
        }
        return feed.videos
    }

    private companion object {
        const val TAG = "VideoAccount"
    }
}

/** Where [NewPipeVideoSource][com.cosmotune.video.source.newpipe.NewPipeVideoSource] gets related videos when the extractor has none. */
internal fun interface RelatedFallback {
    /** The related videos of [videoId]; throws on any failure (the caller maps it to a source error). */
    suspend fun fetch(videoId: String): List<VideoItem>

    companion object {
        /** The real one: the site's `next` endpoint. */
        val Site: RelatedFallback by lazy { InnerTubeRelated().let { RelatedFallback { id -> it.fetch(id) } } }
    }
}
