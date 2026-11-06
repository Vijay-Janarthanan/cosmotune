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
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import com.cosmotune.ui.sheets.SurfaceSheetSwitch
import com.cosmotune.video.comments.CachedCommentsLoader
import com.cosmotune.video.comments.CommentsFeed
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.Comment

/** The list is this share of the window high: it needs a bound, because the Orbit sheet scrolls its content itself. */
private const val SHEET_HEIGHT_SHARE = 0.7f

/**
 * The comments of [videoId] in the Orbit bottom sheet, opened from the watch page's comments tile: the same header, list, replies and
 * composer as [CommentsPanel], in the sheet every other mode uses. The sheet has its own grabber, scrim and dismissal; the header's title
 * stands in for the kit's, so the count and the order chips stay beside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val density = LocalDensity.current
    val listHeight = with(density) { (LocalWindowInfo.current.containerSize.height * SHEET_HEIGHT_SHARE).toDp() }
    SurfaceSheetSwitch(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().height(listHeight)) {
            CommentsPanelBody(feed, sheet = true, onDismiss, listState, expanded, replyTo, onReplyTo = { replyTo = it })
        }
    }
}
