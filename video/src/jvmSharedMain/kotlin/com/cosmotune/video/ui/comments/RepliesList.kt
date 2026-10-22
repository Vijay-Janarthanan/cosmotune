package com.cosmotune.video.ui.comments

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.comments.CommentsFeed
import com.cosmotune.video.comments.RepliesLoader
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.model.Comment
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_replies
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.music.sharedui.resources.Res as SharedRes
import com.cosmotune.music.sharedui.resources.cosmo_back

/*
 * The replies of one comment (spec 5.9), inside the panel: a back row ("N replies" with an arrow, and the close button on the sheet),
 * the comment itself with a rule under it, then its replies, paged the same way as the comments.
 */

/** The back row: padding 4 20 4 12, the 40 dp back button, then the count as a title. */
private val BACK_ROW_START = 12.dp
private val BACK_ROW_END = 20.dp

/**
 * The replies that [token] opens, under [parent]. [onBack] returns to the comments; [onDismiss] closes the panel (the close button of the
 * sheet). [sheet] is the phone's sheet (the C look) rather than the desktop's column.
 */
@Composable
internal fun RepliesList(
    parent: Comment,
    token: PageToken,
    sheet: Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalVideoGraph.current
    val scope = rememberCoroutineScope()
    val feed = remember(token, graph) { CommentsFeed(RepliesLoader(graph.source, token), scope) }
    DisposableEffect(feed) {
        feed.start()
        onDispose { feed.close() }
    }
    val state by feed.state.collectAsState()
    val listState = remember(feed) { LazyListState() }
    val expanded = remember(feed) { ExpandedComments() }
    Column(modifier.fillMaxSize()) {
        RepliesBackRow(parent.replyCount, sheet, onBack, onDismiss)
        CommentsList(
            state = state,
            listState = listState,
            expanded = expanded,
            floating = sheet,
            onReplies = null,
            onLoadMore = feed::loadMore,
            onRetry = feed::retry,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            leading = {
                item(key = "~parent", contentType = "comment") {
                    CommentItem(
                        comment = parent,
                        expanded = parent.id in expanded,
                        onToggleExpanded = { expanded.toggle(parent.id) },
                        floating = sheet,
                        onReplies = null,
                    )
                }
                item(key = "~rule", contentType = "rule") { Rule() }
            },
        )
    }
}

/** The back row: the arrow, "N replies" as the title, and on the sheet the close button at the end. */
@Composable
private fun RepliesBackRow(count: Int, sheet: Boolean, onBack: () -> Unit, onDismiss: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val replies = count.coerceAtLeast(1)
    val title = pluralStringResource(Res.plurals.video_replies, replies, replies)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = BACK_ROW_START, top = 4.dp, end = BACK_ROW_END, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CosmoIconButton(
            icon = CosmoIcons.ArrowLeft,
            contentDescription = stringResource(SharedRes.string.cosmo_back),
            onClick = onBack,
            size = 40.dp,
            floating = sheet,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            style = type.forText(type.title(TypeScale.TITLE_S), title),
            color = Color(palette.textPrimary),
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (sheet) CommentsCloseButton(onDismiss)
    }
}

/** A hairline under the comment whose replies these are: ink at 10 %, inside the page margin. */
@Composable
private fun Rule() {
    val colors = LocalCosmoColors.current
    Spacer(Modifier.height(4.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin)
            .height(1.dp)
            .drawBehind { drawRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = RULE_ALPHA)) },
    )
    Spacer(Modifier.height(4.dp))
}

private const val RULE_ALPHA = 0.10f
