package com.cosmotune.video.ui.comments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.comments.CommentsFailure
import com.cosmotune.video.comments.CommentsState
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.Comment
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_comments_error
import com.cosmotune.video.resources.video_error_bot
import com.cosmotune.video.resources.video_error_network
import com.cosmotune.video.resources.video_error_parsing
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import org.jetbrains.compose.resources.stringResource

/*
 * The list of comments (spec 5.9): a lazy column keyed by comment id, skeleton rows while the first page loads and while a later one does,
 * the next page asked for when the last few rows come into view, and a "Try again" row when a page failed. The replies use it too.
 */

/** The next page is asked for when the last visible row is within this many rows of the end. */
internal const val LOAD_AHEAD = 4

/** Skeleton rows shown while the first page loads. */
private const val SKELETON_ROWS = 5

/** Room under the last row. */
private val LIST_END = 8.dp

private const val COMMENT = "comment"
private const val SKELETON = "skeleton"
private const val ERROR = "error"

/**
 * The comments of [state], with [leading] items first (the replies' comment and its rule). [onReplies] opens a comment's replies
 * (null: no comment offers them). [onLoadMore] is called near the end while [CommentsState.hasMore]; [onRetry] by the "Try again" row.
 * [floating] is the sheet's C look for the buttons.
 */
@Composable
internal fun CommentsList(
    state: CommentsState,
    listState: LazyListState,
    expanded: ExpandedComments,
    floating: Boolean,
    onReplies: ((Comment) -> Unit)?,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (LazyListScope.() -> Unit)? = null,
) {
    // read in a derived state: the list scrolling does not recompose this, only the end coming near does
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - LOAD_AHEAD
        }
    }
    // keyed on the size and on the loading flag too: a page that did not fill the screen asks for the next one at once
    LaunchedEffect(nearEnd, state.items.size, state.loadingMore, state.hasMore) {
        if (nearEnd && state.hasMore) onLoadMore()
    }
    LazyColumn(modifier = modifier, state = listState, contentPadding = PaddingValues(bottom = LIST_END)) {
        leading?.invoke(this)
        if (state.loading && state.items.isEmpty()) {
            items(SKELETON_ROWS, key = { "~skeleton-$it" }, contentType = { SKELETON }) { CommentSkeletonRow() }
        }
        state.failure?.let { failure ->
            item(key = "~failure", contentType = ERROR) { InlineFailure(failure, floating, onRetry) }
        }
        items(state.items, key = { it.id }, contentType = { COMMENT }) { comment ->
            CommentItem(
                comment = comment,
                expanded = comment.id in expanded,
                onToggleExpanded = { expanded.toggle(comment.id) },
                floating = floating,
                onReplies = if (onReplies != null && comment.replies != null) ({ onReplies(comment) }) else null,
            )
        }
        if (state.loadingMore) {
            item(key = "~more", contentType = SKELETON) { CommentSkeletonRow() }
        }
        state.moreFailure?.let { failure ->
            item(key = "~more-failure", contentType = ERROR) { InlineFailure(failure, floating, onRetry) }
        }
    }
}

/** The skeleton of a comment: the avatar's circle and three lines (the author, then the text), in the shape of the row it stands for. */
@Composable
internal fun CommentSkeletonRow(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = 10.dp),
    ) {
        CosmoShimmer(Modifier.size(VideoMetrics.COMMENT_AVATAR_DP.dp), CircleShape)
        Spacer(Modifier.width(CosmoSpacing.gutter))
        Column(Modifier.weight(1f)) {
            CosmoShimmer(Modifier.width(96.dp).height(12.dp))
            Spacer(Modifier.height(8.dp))
            CosmoShimmer(Modifier.fillMaxWidth().height(12.dp))
            Spacer(Modifier.height(6.dp))
            CosmoShimmer(Modifier.fillMaxWidth(0.7f).height(12.dp))
        }
    }
}

/** A page that did not load, inside the list: what went wrong and "Try again". */
@Composable
private fun InlineFailure(failure: CommentsFailure, floating: Boolean, onRetry: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val message = commentsFailureText(failure)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = rememberMetaStyle(13f, message),
            color = Color(palette.textSecondary),
            modifier = Modifier.weight(1f),
        )
        CosmoButton(
            text = stringResource(Res.string.video_retry),
            onClick = onRetry,
            kind = CosmoButtonKind.TEXT,
            size = CosmoButtonSize.SMALL,
            floating = floating,
        )
    }
}

/** What to say about a [failure]: the words of the player's errors for the cases they cover, else "Could not load the comments". */
@Composable
internal fun commentsFailureText(failure: CommentsFailure): String = stringResource(
    when (failure) {
        CommentsFailure.NETWORK -> Res.string.video_error_network
        CommentsFailure.BOT_CHECK -> Res.string.video_error_bot
        CommentsFailure.SOURCE_CHANGED -> Res.string.video_error_parsing
        CommentsFailure.OTHER -> Res.string.video_comments_error
    },
)
