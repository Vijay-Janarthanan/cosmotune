package com.cosmotune.video.ui.comments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.video.comments.CachedCommentsLoader
import com.cosmotune.video.comments.CommentsFeed
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.Comment

/** The sheet is this share of the window high: its list needs a bound, because the kit's sheet sizes itself to its content. */
private const val SHEET_HEIGHT_SHARE = 0.85f

/**
 * The comments of [videoId] in the kit's bottom sheet, opened from the watch page's comments tile: the same header, list, replies and
 * composer as [CommentsPanel], in the sheet every other mode uses. The sheet has its own grabber, scrim and dismissal; the header's title
 * stands in for the kit's, so the count and the order chips stay beside it.
 */
@Composable
fun CommentsSheet(videoId: String, onDismiss: () -> Unit) {
    val graph = LocalVideoGraph.current
    val scope = rememberCoroutineScope()
    val feed = remember(videoId, graph) { CommentsFeed(CachedCommentsLoader(graph.source, videoId, SharedCommentsCache), scope) }
    DisposableEffect(feed) {
        feed.start()
        onDispose { feed.close() }
    }
    val listState = remember(feed) { LazyListState() }
    val expanded = remember(feed) { ExpandedComments() }
    var replyTo by remember(feed) { mutableStateOf<Comment?>(null) }
    KitSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(SHEET_HEIGHT_SHARE)) {
            CommentsPanelBody(feed, sheet = true, onDismiss, listState, expanded, replyTo, onReplyTo = { replyTo = it })
        }
    }
}
