package com.cosmotune.video.ui.comments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.ui.blocks.cosmoTile
import com.cosmotune.ui.components.SuperellipseShape
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
import com.cosmotune.video.model.Comment
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_comments
import com.cosmotune.video.resources.video_comments_empty
import com.cosmotune.video.resources.video_comments_error
import com.cosmotune.video.ui.blocks.ChannelAvatar
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import com.cosmotune.video.ui.blocks.videoClickable
import org.jetbrains.compose.resources.stringResource

/*
 * The two comment composables the watch page (Y-19) and the Shorts pager (Y-31) call (plan task Y-23, spec 5.9 and 5.4). The file keeps
 * the name and the signatures Y-16 fixed; the list, the comment, the replies and the surface are in the files beside it.
 */

/** The first pages the tile loads, kept for the panel that opens next (10 minutes, the last 8 videos). */
private val SharedCommentsCache = CommentsPageCache(clock = { System.currentTimeMillis() })

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

/** The tile's padding (spec 5.4: the description's tile, margin 8 16, padding 14 16) and the avatar of the first comment (24 dp). */
private val TILE_MARGIN_V = 8.dp
private val TILE_PADDING_H = 16.dp
private val TILE_PADDING_V = 14.dp
private val PREVIEW_AVATAR = 24.dp
private const val PREVIEW_SP = 14f
private const val PREVIEW_LINE = 1.4f
private const val PREVIEW_LINES = 2

/**
 * The watch page's comments tile (spec 5.4: "Comments" with the count, and the first comment's 24 dp avatar and two lines; hidden when
 * comments are off); [onOpen] opens the [CommentsPanel]. While the first page loads the tile holds its place with a skeleton; if it
 * failed it says so (the panel tries again); a video with no comments yet says that. The first page is kept for the panel.
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
    val shape = remember { SuperellipseShape(CosmoRadii.card) }
    val title = stringResource(Res.string.video_comments)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = TILE_MARGIN_V)
            .videoClickable(CosmoRadii.card, onOpen, onMore = null)
            .cosmoTile(shape)
            .padding(horizontal = TILE_PADDING_H, vertical = TILE_PADDING_V),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = title,
                style = type.forText(type.title(TypeScale.TITLE_S), title),
                color = Color(palette.textPrimary),
                modifier = Modifier.alignByBaseline().semantics { heading() },
            )
            val total = (preview as? CommentsPreview.Ready)?.total
            if (total != null) {
                val count = VideoText.compact(total)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = count,
                    style = rememberMetaStyle(13f, count),
                    color = Color(palette.textSecondary),
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        when (val p = preview) {
            CommentsPreview.Loading -> PreviewSkeleton()
            is CommentsPreview.Ready -> {
                val first = p.first
                if (first != null) FirstComment(first) else PreviewLine(stringResource(Res.string.video_comments_empty))
            }
            CommentsPreview.Failed -> PreviewLine(stringResource(Res.string.video_comments_error))
            CommentsPreview.Off -> Unit
        }
    }
}

/** The first comment: its author's avatar and its text, two lines at 14 sp. */
@Composable
private fun FirstComment(comment: Comment) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val text = comment.text
    val style = remember(type, text) {
        type.forText(
            TextStyle(
                fontFamily = type.textFamily,
                fontWeight = FontWeight.Normal,
                fontSize = PREVIEW_SP.sp,
                lineHeight = PREVIEW_SP.sp * PREVIEW_LINE,
            ),
            text,
        )
    }
    Row(verticalAlignment = Alignment.Top) {
        ChannelAvatar(comment.authorAvatarUrl, PREVIEW_AVATAR)
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = style,
            color = Color(palette.textPrimary),
            maxLines = PREVIEW_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One line of secondary text in the tile ("No comments yet", or the failure). */
@Composable
private fun PreviewLine(text: String) {
    val palette = LocalCosmoColors.current.palette
    Text(
        text = text,
        style = rememberMetaStyle(PREVIEW_SP, text),
        color = Color(palette.textSecondary),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The place of the first comment while it loads: the avatar's circle and two lines. */
@Composable
private fun PreviewSkeleton() {
    Row(verticalAlignment = Alignment.Top) {
        CosmoShimmer(Modifier.size(PREVIEW_AVATAR), CircleShape)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CosmoShimmer(Modifier.fillMaxWidth().height(12.dp))
            CosmoShimmer(Modifier.fillMaxWidth(0.6f).height(12.dp))
        }
    }
}
