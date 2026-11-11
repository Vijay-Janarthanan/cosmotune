package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitVideoCard
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_more
import org.jetbrains.compose.resources.stringResource

/**
 * The feed card (Orbit board B, "Video mode"): the kit's [OrbitVideoCard], the picture across the page's width less its margins, a
 * 16:9 rectangle with nothing on it and the play moon off its corner; under it the channel's disc, the title on two lines and the
 * meta line ("Channel · 12:04 · 2 days ago"). It stands in the page's 16 dp margin with 20 dp after it, so a list needs no padding of its own.
 *
 * A tap (or the moon) calls [onClick]; a hold, a right click and the screen reader's "More options" call [onMore] (the video sheet).
 * [watchedFraction] puts the watched line under the picture, [title] stands in for the item's (a DeArrow title), [downloaded] says
 * so in the meta line, [onAvatarClick] opens the channel. On a wide page the grid ([videoGridItems]) draws the same card in its cells.
 */
@Composable
fun VideoCard(
    item: VideoItem,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    watchedFraction: Float? = null,
    title: String = item.title,
    downloaded: Boolean = false,
    onAvatarClick: (() -> Unit)? = null,
) {
    VideoCardBody(item, onClick, onMore, modifier.padding(horizontal = CosmoSpacing.margin).padding(bottom = CARD_AFTER), watchedFraction, title, downloaded, onAvatarClick = onAvatarClick)
}

/**
 * The one card anatomy: the phone's [VideoCard] stands it in the page's margin, the wide grid in a cell ([width] unspecified: the
 * cell's width), a shelf at its card width. [avatar] false leaves the channel's disc out (a shelf, where the channel is in the meta).
 */
@Composable
internal fun VideoCardBody(
    item: VideoItem,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier,
    watchedFraction: Float?,
    title: String,
    downloaded: Boolean,
    onAvatarClick: (() -> Unit)? = null,
    width: Dp = Dp.Unspecified,
    avatar: Boolean = true,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val meta = CardMetaLine(item, rememberNowMs(), downloaded = downloaded)
    val more = stringResource(Res.string.video_more)
    val hover = rememberCardHover()
    PreResolveOnHover(hover, item)
    OrbitVideoCard(
        title = title,
        meta = meta,
        thumbnail = item.thumbnailUrl,
        live = item.isLive,
        fonts = fonts,
        look = look,
        onClick = onClick,
        onLongClick = onMore,
        width = width,
        progress = watchedFraction,
        modifier = with(hover) { modifier.track() }.semantics { customActions = listOf(CustomAccessibilityAction(more) { onMore(); true }) },
        picture = { m -> ThumbnailFrame(item.thumbnailUrl, with(hover) { m.lifted() }, RectangleShape) },
        avatar = if (!avatar) null else ({
            val tap = if (onAvatarClick != null) Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onAvatarClick) else Modifier
            ChannelAvatar(item.channelAvatarUrl, CARD_AVATAR, tap, name = item.channelName)
        }),
    )
}

/** The room after a card of a list. */
private val CARD_AFTER = 20.dp

/** The channel's disc beside a card's words (the board's 32 dp). */
private val CARD_AVATAR = 32.dp
