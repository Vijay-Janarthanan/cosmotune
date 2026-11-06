package com.cosmotune.video.ui.comments

import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.account.CommentInteractions
import com.cosmotune.video.model.Comment
import com.cosmotune.video.model.CommentRating
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_creator
import com.cosmotune.video.resources.video_hearted
import com.cosmotune.video.resources.video_likes_cd
import com.cosmotune.video.resources.video_pinned
import com.cosmotune.video.resources.video_replies
import com.cosmotune.video.resources.video_comment_dislike
import com.cosmotune.video.resources.video_comment_reply
import com.cosmotune.video.resources.video_like
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.ChannelAvatar
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import com.cosmotune.video.ui.blocks.rememberTitleStyle
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.music.sharedui.resources.Res as SharedRes
import com.cosmotune.music.sharedui.resources.cosmo_read_more
import com.cosmotune.music.sharedui.resources.cosmo_show_less
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * One comment (spec 5.9): a 32 dp avatar, 12 dp, then the author (13 sp, 650) with the age (12.5 sp, `textTertiary`), the text (14.5 sp,
 * line 1.45, six lines and then "Read more"), and a row with the likes, the creator's heart and "N replies". A pinned comment
 * carries the "Pinned" pill above its author; the uploader's carries a "Creator" mark beside the name. Padding 10 16.
 */

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

/** Room for a finger around a rating thumb. */
private val RATE_TARGET = 32.dp

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
 * comment above them).
 */
@Composable
internal fun CommentItem(
    comment: Comment,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    onReplies: (() -> Unit)? = null,
) {
    // whether the text is longer than six lines: kept with `rememberSaveable`, which a lazy list holds for an item that scrolled out of
    // view, so the button is there again at once when the comment comes back and the row does not grow a frame after it appears
    var cut by rememberSaveable(comment.id, comment.text) { mutableStateOf(false) }
    val look = orbitLook()
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = CosmoSpacing.margin, vertical = 4.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(look.row)
            .padding(start = 10.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
    ) {
        ChannelAvatar(comment.authorAvatarUrl, VideoMetrics.COMMENT_AVATAR_DP.dp, name = comment.authorName)
        Spacer(Modifier.width(CosmoSpacing.gutter))
        Column(Modifier.weight(1f)) {
            if (comment.pinned) {
                CommentPill(stringResource(Res.string.video_pinned), strong = true)
                Spacer(Modifier.height(4.dp))
            }
            // the author, the age and the text read as one thing; the buttons below them are their own
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                AuthorLine(comment)
                CommentText(comment, expanded, onCut = { cut = it })
            }
            ReadMore(cut, expanded, onToggleExpanded)
            val writes = LocalCommentInteractions.current?.takeIf { comment.writes != null || !it.canWrite }
            if (writes != null) {
                CommentRateRow(comment, writes)
                CommentActions(comment.copy(likeCount = null), onReplies)
            } else {
                CommentActions(comment, onReplies)
            }
        }
    }
}

/** The name, the Creator mark and the age on one line; a long name is cut, the mark and the age stay. */
@Composable
private fun AuthorLine(comment: Comment) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val author = comment.authorName.trim()
    val age = comment.publishedText?.trim()?.takeIf { it.isNotEmpty() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (author.isNotEmpty()) {
            Text(
                text = author,
                style = fonts.title.copy(fontSize = 14.sp),
                color = look.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (comment.byCreator) {
            Spacer(Modifier.width(6.dp))
            CommentPill(stringResource(Res.string.video_creator), strong = false)
        }
        if (age != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = age,
                style = fonts.label,
                color = look.dim,
                maxLines = 1,
            )
        }
    }
}

/** The text: [VideoMetrics.COMMENT_MAX_LINES] lines, or all of it when [expanded]; [onCut] hears from each layout whether it is longer than that. */
@Composable
private fun CommentText(comment: Comment, expanded: Boolean, onCut: (Boolean) -> Unit) {
    val look = orbitLook()
    val type = LocalCosmoType.current
    val text = comment.text
    val style = remember(type, text) {
        type.forText(type.bodyText, text)
    }
    Text(
        text = text,
        style = style,
        color = look.ink,
        maxLines = if (expanded) Int.MAX_VALUE else VideoMetrics.COMMENT_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        // cut: six lines lost something; once opened, "more than six lines" is what says the button should stay as "Show less"
        onTextLayout = { result -> onCut(if (expanded) result.lineCount > VideoMetrics.COMMENT_MAX_LINES else result.hasVisualOverflow) },
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** "Read more" under a text that was cut, "Show less" under one that is open. */
@Composable
private fun ReadMore(cut: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    if (!cut && !expanded) return
    CommentLink(
        text = stringResource(if (expanded) SharedRes.string.cosmo_show_less else SharedRes.string.cosmo_read_more),
        onClick = onToggle,
        modifier = Modifier.offset(x = -TEXT_BUTTON_INSET),
    )
}

/**
 * The buttons of a list that can write: like with its count, dislike, and "Reply". Pressing any of them signed out opens the account sheet,
 * through [CommentInteractions].
 */
@Composable
private fun CommentRateRow(comment: Comment, writes: CommentInteractions) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val ratings by writes.ratings.collectAsState()
    val rating = writes.ratingOf(comment, ratings)
    val likes = writes.likesOf(comment, ratings)
    Row(Modifier.padding(top = 4.dp).heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        RateButton(
            icon = VideoIcons.ThumbUp,
            label = stringResource(Res.string.video_like),
            on = rating == CommentRating.LIKED,
            onClick = { writes.toggleLike(comment) },
        )
        if (likes != null) {
            val count = VideoText.compact(likes)
            Text(text = count, style = fonts.label, color = look.dim, maxLines = 1)
        }
        Spacer(Modifier.width(ACTION_GAP))
        RateButton(
            icon = VideoIcons.ThumbDown,
            label = stringResource(Res.string.video_comment_dislike),
            on = rating == CommentRating.DISLIKED,
            onClick = { writes.toggleDislike(comment) },
        )
        Spacer(Modifier.width(ACTION_GAP))
        CommentLink(stringResource(Res.string.video_comment_reply), { writes.startReply(comment) })
    }
}

/** A thumb the size of a like count's, with room for a finger; in the accent while it is on, and read out as on or off. */
@Composable
private fun RateButton(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val look = orbitLook()
    Box(
        Modifier
            .size(RATE_TARGET)
            .clip(CircleShape)
            .toggleable(value = on, role = Role.Switch, onValueChange = { onClick() })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (on) look.accent else look.dim, modifier = Modifier.size(LIKE_ICON))
    }
}

/** The like count, the creator's heart and "N replies", on one row; nothing when the comment has none of them. */
@Composable
private fun CommentActions(comment: Comment, onReplies: (() -> Unit)?) {
    val look = orbitLook()
    val fonts = orbitFonts()
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
                Icon(VideoIcons.ThumbUp, contentDescription = null, tint = look.dim, modifier = Modifier.size(LIKE_ICON))
                Spacer(Modifier.width(6.dp))
                Text(text = count, style = fonts.label, color = look.dim, maxLines = 1)
            }
        }
        if (hearted) {
            Icon(
                CosmoIcons.HeartFill,
                contentDescription = stringResource(Res.string.video_hearted),
                tint = look.accent,
                modifier = Modifier.size(HEART_ICON),
            )
        }
        if (canOpen) {
            val replies = comment.replyCount.coerceAtLeast(1)
            CommentLink(
                text = pluralStringResource(Res.plurals.video_replies, replies, replies),
                onClick = onReplies,
                modifier = Modifier.offset(x = -TEXT_BUTTON_INSET),
            )
        }
    }
}

/** A small pill of text: "Pinned" on `surface3`, "Creator" on a faint wash of ink. 18 dp tall, 11.5 sp, weight 700. */
@Composable
private fun CommentPill(text: String, strong: Boolean) {
    val look = orbitLook()
    val fonts = orbitFonts()
    Box(
        Modifier
            .height(PILL_HEIGHT)
            .clip(CircleShape)
            .background(if (strong) look.accent.copy(alpha = 0.22f) else look.ink.copy(alpha = CREATOR_WASH))
            .padding(horizontal = PILL_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = fonts.label,
            color = if (strong) look.accent else look.dim,
            maxLines = 1,
        )
    }
}
