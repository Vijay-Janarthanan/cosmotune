package com.cosmotune.video.account

import com.cosmotune.media.comments.TimedComments
import com.cosmotune.video.model.CommentSort

/** Video mode hands the music player's timed comments their source: a song video's first page of top comments, read without a sign-in. */
object VideoTimedComments {
    fun install() {
        TimedComments.fetch = { videoId ->
            InnerTubeComments().first(videoId, CommentSort.TOP).items.map { Triple(it.text, it.authorName, it.likeCount ?: 0L) }
        }
    }
}
