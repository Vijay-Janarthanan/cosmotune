package com.cosmotune.video.ui.comments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.comments.CachedCommentsLoader
import com.cosmotune.video.comments.CommentsFeed
import com.cosmotune.video.comments.CommentsPageCache
import com.cosmotune.video.comments.CommentsPreview
import com.cosmotune.video.comments.preview
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_comments
import com.cosmotune.video.resources.video_comments_empty
import com.cosmotune.video.resources.video_comments_error
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import com.cosmotune.video.ui.blocks.videoClickable
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.cosmotune.video.model.Comment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * The two comment composables the watch page (Y-19) and the Shorts pager (Y-31) call (plan task Y-23, spec 5.9 and 5.4). The file keeps
 * the name and the signatures Y-16 fixed; the list, the comment, the replies and the surface are in the files beside it.
 */

/** The first pages the tile loads, kept for the panel that opens next (10 minutes, the last 8 videos). */
internal val SharedCommentsCache = CommentsPageCache(clock = { System.currentTimeMillis() })

/**
 * The comments of [videoId] (spec 5.9): as a sheet under the player when [sheet] (the phone; [onDismiss] closes it), or as the
 * list of the desktop's side column. The caller places it with [modifier] (on the phone: the offset that puts its top at the bottom of
 * the player; the sheet is as high as the room under that allows, at most 85 % of the window).
 *
 * The header has "Comments", the count, Top and Newest when the source offers them, and on the sheet a close button; a comment
 * shows six lines and then "Read more", its likes, the creator's heart and "N replies", which open that comment's replies inside the
 * panel with a back row (the system Back key goes back from them, too). The next page loads as the end comes near. Comments that are
 * turned off give "Comments are turned off".
 */
@Composable
fun CommentsPanel(videoId: String, sheet: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalVideoGraph.current
    val scope = rememberCoroutineScope()
    // a new video (autoplay moved on) is a new list: the old one stops asking and its replies close
    val feed = remember(videoId, graph) { CommentsFeed(CachedCommentsLoader(graph.source, videoId, SharedCommentsCache), scope) }
    DisposableEffect(feed) {
        feed.start()
        onDispose { feed.close() }
    }
    val listState = remember(feed) { LazyListState() }
    val expanded = remember(feed) { ExpandedComments() }
    var replyTo by remember(feed) { mutableStateOf<Comment?>(null) }
    CommentsSurface(sheet, onDismiss, modifier) {
        CommentsPanelBody(feed, sheet, onDismiss, listState, expanded, replyTo, onReplyTo = { replyTo = it })
    }
}

/** The teaser: 64 dp (DESIGN_SYSTEM.md 6.2), the page's margin at its sides, 8 dp above and below. */
private val TEASER_HEIGHT = 64.dp
private val TEASER_MARGIN_V = 8.dp
private val TEASER_PADDING_H = 16.dp

/**
 * The watch page's comments teaser (DESIGN_SYSTEM.md 6.2), the page's only card: 64 dp, `surface1`, `rMd`, "Comments" with the count over one
 * line of the first comment; hidden when comments are off. [onOpen] opens the [CommentsPanel]. While the first page loads it holds its place
 * with a skeleton; if it failed it says so (the panel tries again); a video with no comments yet says that. The first page is kept for the panel.
 */
@Composable
fun CommentsPreviewTile(videoId: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalVideoGraph.current
    val preview by produceState<CommentsPreview>(CommentsPreview.Loading, videoId, graph) {
        value = CachedCommentsLoader(graph.source, videoId, SharedCommentsCache).preview()
    }
    if (preview == CommentsPreview.Off) return
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val shape = RoundedCornerShape(CosmoRadii.rMd)
    val title = stringResource(Res.string.video_comments)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = TEASER_MARGIN_V)
            .heightIn(min = TEASER_HEIGHT)
            .clip(shape)
            .background(Color(palette.surface))
            .videoClickable(CosmoRadii.rMd, onOpen, onMore = null)
            .padding(horizontal = TEASER_PADDING_H, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = title,
                style = type.forText(type.rowTitle, title),
                color = Color(palette.textPrimary),
                modifier = Modifier.alignByBaseline().semantics { heading() },
            )
            val total = (preview as? CommentsPreview.Ready)?.total
            if (total != null) {
                val count = VideoText.compact(total)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = count,
                    style = rememberMetaStyle(count),
                    color = Color(palette.textSecondary),
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        when (val p = preview) {
            CommentsPreview.Loading -> CosmoShimmer(Modifier.fillMaxWidth(0.7f).height(12.dp))
            is CommentsPreview.Ready -> PreviewLine(p.first?.text ?: stringResource(Res.string.video_comments_empty))
            CommentsPreview.Failed -> PreviewLine(stringResource(Res.string.video_comments_error))
            CommentsPreview.Off -> Unit
        }
    }
}

/** One line of secondary text in the teaser: the first comment, "No comments yet", or the failure. */
@Composable
private fun PreviewLine(text: String) {
    val palette = LocalCosmoColors.current.palette
    Text(
        text = text,
        style = rememberMetaStyle(text),
        color = Color(palette.textSecondary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
