package com.cosmotune.video.ui.comments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.model.Comment
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_creator
import com.cosmotune.video.resources.video_hearted
import com.cosmotune.video.resources.video_likes_cd
import com.cosmotune.video.resources.video_pinned
import com.cosmotune.video.resources.video_replies
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.ChannelAvatar
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import com.cosmotune.video.ui.blocks.rememberTitleStyle
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.music.sharedui.resources.Res as SharedRes
import com.cosmotune.music.sharedui.resources.cosmo_read_more
import com.cosmotune.music.sharedui.resources.cosmo_show_less

/*
 * One comment (spec 5.9): a 32 dp avatar, 12 dp, then the author (13 sp, 650) with the age (12.5 sp, `textTertiary`), the text (14.5 sp,
 * line 1.45, six lines and then "Read more"), and a row with the likes, the creator's heart and "N replies". A pinned comment
 * carries the "Pinned" pill above its author; the uploader's carries a "Creator" mark beside the name. Padding 10 16.
 */

/** The author's name: 13 sp, weight 650 (`rememberTitleStyle`). */
private const val AUTHOR_SP = 13f

/** The age and the like count: 12.5 sp. */
private const val SMALL_SP = 12.5f

/** The comment's line height: 1.45 of its size. */
private const val COMMENT_LINE = 1.45f

/** The pills (Pinned, Creator): 18 dp tall, 11.5 sp, weight 700. */
private val PILL_HEIGHT = 18.dp
private const val PILL_SP = 11.5f
private const val PILL_WEIGHT = 700
private val PILL_PADDING = 8.dp

/** The Creator mark is a faint wash of ink, not the accent: the accent pill is for "Pinned". */
private const val CREATOR_WASH = 0.12f

/** The like count's thumb: 16 dp; the creator's heart: 14 dp. */
private val LIKE_ICON = 16.dp
private val HEART_ICON = 14.dp

/** Space between the likes, the heart and the replies button. */
private val ACTION_GAP = 14.dp

/** A text button keeps 12 dp of padding inside its label: the label stands on the text's edge. */
private val TEXT_BUTTON_INSET = 12.dp

/** The set of comments whose full text is shown, kept by the list so a comment scrolled away and back stays open. */
@Stable
internal class ExpandedComments {
    private val ids = mutableStateMapOf<String, Boolean>()

    operator fun contains(id: String): Boolean = ids[id] == true

    fun toggle(id: String) {
        if (ids[id] == true) ids.remove(id) else ids[id] = true
    }
}

/**
 * One comment. [expanded] shows all of its text instead of six lines, and [onToggleExpanded] flips that (the "Read more" button, which
 * appears only when the text was cut). [onReplies] opens its replies; null shows no replies button (the replies themselves, and the
 * comment above them). [floating] is the sheet's C look for the buttons; the desktop column and the replies on a page are B.
 */
@Composable
internal fun CommentItem(
    comment: Comment,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
    onReplies: (() -> Unit)? = null,
) {
    // whether the text is longer than six lines: kept with `rememberSaveable`, which a lazy list holds for an item that scrolled out of
    // view, so the button is there again at once when the comment comes back and the row does not grow a frame after it appears
    var cut by rememberSaveable(comment.id, comment.text) { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = 10.dp),
    ) {
        ChannelAvatar(comment.authorAvatarUrl, VideoMetrics.COMMENT_AVATAR_DP.dp)
        Spacer(Modifier.width(CosmoSpacing.gutter))
        Column(Modifier.weight(1f)) {
            if (comment.pinned) {
                CommentPill(stringResource(Res.string.video_pinned), accent = true)
                Spacer(Modifier.height(4.dp))
            }
            // the author, the age and the text read as one thing; the buttons below them are their own
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                AuthorLine(comment)
                CommentText(comment, expanded, onCut = { cut = it })
            }
            ReadMore(cut, expanded, onToggleExpanded, floating)
            CommentActions(comment, floating, onReplies)
        }
    }
}

/** The name, the Creator mark and the age on one line; a long name is cut, the mark and the age stay. */
@Composable
private fun AuthorLine(comment: Comment) {
    val palette = LocalCosmoColors.current.palette
    val author = comment.authorName.trim()
    val age = comment.publishedText?.trim()?.takeIf { it.isNotEmpty() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (author.isNotEmpty()) {
            Text(
                text = author,
                style = rememberTitleStyle(AUTHOR_SP, author),
                color = Color(palette.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (comment.byCreator) {
            Spacer(Modifier.width(6.dp))
            CommentPill(stringResource(Res.string.video_creator), accent = false)
        }
        if (age != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = age,
                style = rememberMetaStyle(SMALL_SP, age),
                color = Color(palette.textTertiary),
                maxLines = 1,
            )
        }
    }
}

/** The text: [VideoMetrics.COMMENT_MAX_LINES] lines, or all of it when [expanded]; [onCut] hears from each layout whether it is longer than that. */
@Composable
private fun CommentText(comment: Comment, expanded: Boolean, onCut: (Boolean) -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val text = comment.text
    val style = remember(type, text) {
        type.forText(
            TextStyle(
                fontFamily = type.textFamily,
                fontWeight = FontWeight.Normal,
                fontSize = VideoMetrics.COMMENT_TEXT_SP.sp,
                lineHeight = VideoMetrics.COMMENT_TEXT_SP.sp * COMMENT_LINE,
            ),
            text,
        )
    }
    Text(
        text = text,
        style = style,
        color = Color(palette.textPrimary),
        maxLines = if (expanded) Int.MAX_VALUE else VideoMetrics.COMMENT_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        // cut: six lines lost something; once opened, "more than six lines" is what says the button should stay as "Show less"
        onTextLayout = { result -> onCut(if (expanded) result.lineCount > VideoMetrics.COMMENT_MAX_LINES else result.hasVisualOverflow) },
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** "Read more" under a text that was cut, "Show less" under one that is open. */
@Composable
private fun ReadMore(cut: Boolean, expanded: Boolean, onToggle: () -> Unit, floating: Boolean) {
    if (!cut && !expanded) return
    CosmoButton(
        text = stringResource(if (expanded) SharedRes.string.cosmo_show_less else SharedRes.string.cosmo_read_more),
        onClick = onToggle,
        modifier = Modifier.offset(x = -TEXT_BUTTON_INSET),
        kind = CosmoButtonKind.TEXT,
        size = CosmoButtonSize.SMALL,
        floating = floating,
    )
}

/** The like count, the creator's heart and "N replies", on one row; nothing when the comment has none of them. */
@Composable
private fun CommentActions(comment: Comment, floating: Boolean, onReplies: (() -> Unit)?) {
    val palette = LocalCosmoColors.current.palette
    val likes = comment.likeCount?.takeIf { it > 0 }
    val hearted = comment.heartedByCreator
    val canOpen = onReplies != null && comment.replies != null
    if (likes == null && !hearted && !canOpen) return
    Row(
        Modifier.padding(top = 4.dp).heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ACTION_GAP),
    ) {
        if (likes != null) {
            val count = VideoText.compact(likes)
            val description = stringResource(Res.string.video_likes_cd, count)
            Row(Modifier.clearAndSetSemantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
                Icon(VideoIcons.ThumbUp, contentDescription = null, tint = Color(palette.textSecondary), modifier = Modifier.size(LIKE_ICON))
                Spacer(Modifier.width(6.dp))
                Text(text = count, style = rememberMetaStyle(SMALL_SP, count), color = Color(palette.textSecondary), maxLines = 1)
            }
        }
        if (hearted) {
            Icon(
                CosmoIcons.HeartFill,
                contentDescription = stringResource(Res.string.video_hearted),
                tint = Color(palette.accent),
                modifier = Modifier.size(HEART_ICON),
            )
        }
        if (canOpen) {
            val replies = comment.replyCount.coerceAtLeast(1)
            CosmoButton(
                text = pluralStringResource(Res.plurals.video_replies, replies, replies),
                onClick = onReplies,
                modifier = Modifier.offset(x = -TEXT_BUTTON_INSET),
                kind = CosmoButtonKind.TEXT,
                size = CosmoButtonSize.SMALL,
                floating = floating,
            )
        }
    }
}

/** A small pill of text: "Pinned" in the accent container, "Creator" on a faint wash of ink. 18 dp tall, 11.5 sp, weight 700. */
@Composable
private fun CommentPill(text: String, accent: Boolean) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val style = remember(type, text) {
        type.forText(
            TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight(PILL_WEIGHT), fontSize = PILL_SP.sp, lineHeight = PILL_SP.sp * 1.2f),
            text,
        )
    }
    Box(
        Modifier
            .height(PILL_HEIGHT)
            .clip(CircleShape)
            .drawBehind {
                drawRect(
                    if (accent) colors.live(CoverField.ACCENT_CONTAINER) else colors.live(CoverField.TEXT_PRIMARY).copy(alpha = CREATOR_WASH),
                )
            }
            .padding(horizontal = PILL_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = style,
            color = Color(if (accent) palette.onAccentContainer else palette.textSecondary),
            maxLines = 1,
        )
    }
}
