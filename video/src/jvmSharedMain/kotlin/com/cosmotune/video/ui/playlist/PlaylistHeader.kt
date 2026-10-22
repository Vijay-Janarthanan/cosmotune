package com.cosmotune.video.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.bridge.DropdownMenu
import com.cosmotune.ui.bridge.DropdownMenuItem
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_count_videos
import com.cosmotune.video.resources.video_dl_all
import com.cosmotune.video.resources.video_duration_hm
import com.cosmotune.video.resources.video_duration_m
import com.cosmotune.video.resources.video_meta_join
import com.cosmotune.video.resources.video_play_all
import com.cosmotune.video.resources.video_playlist
import com.cosmotune.video.resources.video_rename
import com.cosmotune.video.resources.video_save_playlist
import com.cosmotune.video.resources.video_saved_playlist
import com.cosmotune.video.resources.video_share
import com.cosmotune.video.resources.video_unsave_playlist
import com.cosmotune.video.resources.video_delete
import com.cosmotune.video.resources.video_your_playlist
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.ThumbnailFrame
import com.cosmotune.video.ui.blocks.ThumbnailShape
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import com.cosmotune.music.sharedui.resources.cosmo_shuffle
import com.cosmotune.music.sharedui.resources.more
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.music.sharedui.resources.Res as SharedRes

/*
 * The head of a playlist page (spec 5.6): the thumbnail, what the page is, the title, the owner, the count and the time, and the actions.
 */

/** Which kind of playlist a page shows. */
internal enum class PlaylistKind { LOCAL, REMOTE }

/** What the head says. [videoCount] and [totalSec] are null where they are not known (a remote playlist whose pages are not all in). */
@Immutable
internal class PlaylistShown(
    val kind: PlaylistKind,
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val ownerName: String?,
    val ownerId: String?,
    val videoCount: Int?,
    val totalSec: Long?,
    val saved: Boolean,
)

/** What the head's buttons do. A null callback is a button the page does not offer. */
internal class PlaylistActions(
    val hasVideos: Boolean,
    val preparing: Boolean,
    val onPlayAll: () -> Unit,
    val onShuffle: () -> Unit,
    val onDownloadAll: () -> Unit,
    val onToggleSaved: (() -> Unit)?,
    val onShare: (() -> Unit)?,
    val onRename: (() -> Unit)?,
    val onDelete: (() -> Unit)?,
    val onOpenOwner: (() -> Unit)?,
)

/** The hero's lift: the album hero's deeper shadow (24 dp, in the cover's `shadow`), over the cover's own (14 dp). */
private val HERO_LIFT = 24.dp
private val COVER_LIFT = 14.dp

/** The shadow is cast from the radius-22 stand-in of the thumbnail's superellipse (the cover rule gives 22 from 160 dp of height up). */
private val HERO_SHADOW_SHAPE = SuperellipseShape(CosmoRadii.lg).shadowShape

/** The wide hero's thumbnail (spec 5.6): 320 x 180. */
private val WIDE_THUMB_WIDTH = 320.dp
private val WIDE_THUMB_HEIGHT = 180.dp
private val WIDE_GAP = 24.dp

/** The owner's circle (spec 5.6): 28 dp, 10 dp before the name. */
private val OWNER_AVATAR = 28.dp
private val OWNER_GAP = 10.dp
private const val OWNER_NAME_SP = 15f
private const val META_SP = 13f

/** The buttons' gap, and the narrower one used when four icon buttons and "Play all" must share a window under [NARROW_DP]. */
private val ACTIONS_GAP = 8.dp
private val ACTIONS_GAP_NARROW = 4.dp
private val NARROW_DP = 380.dp

/** The thumbnail with the album hero's shadows, in the shape every thumbnail has. */
@Composable
private fun HeroThumbnail(url: String?, modifier: Modifier) {
    val colors = LocalCosmoColors.current
    ThumbnailFrame(
        url = url,
        modifier = modifier
            .graphicsLayer {
                shadowElevation = HERO_LIFT.toPx()
                shape = HERO_SHADOW_SHAPE
                clip = false
                spotShadowColor = colors.live(CoverField.SHADOW).copy(alpha = 1f)
                ambientShadowColor = Color.Transparent
            }
            .graphicsLayer {
                shadowElevation = COVER_LIFT.toPx()
                shape = HERO_SHADOW_SHAPE
                clip = false
                spotShadowColor = colors.live(CoverField.SHADOW).copy(alpha = 1f)
                ambientShadowColor = Color.Transparent
            },
        shape = ThumbnailShape,
    )
}

/** The phone's hero: the thumbnail centred, the window less 16 dp on each side, 16:9, 6 dp under the top. */
@Composable
internal fun PlaylistHeroPhone(url: String?, margin: Dp) {
    Box(Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 6.dp), contentAlignment = Alignment.Center) {
        HeroThumbnail(url, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
    }
}

/**
 * The wide page's hero row (spec 5.6): the thumbnail 320 x 180 (radius 22), 24 dp, and the title block with the actions under it. Padding
 * [margin] at the sides, 6 dp on top and 12 under.
 */
@Composable
internal fun PlaylistHeroRow(shown: PlaylistShown, actions: PlaylistActions, margin: Dp) {
    Row(
        Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 6.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(WIDE_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeroThumbnail(shown.thumbnailUrl, Modifier.size(WIDE_THUMB_WIDTH, WIDE_THUMB_HEIGHT))
        Column(Modifier.weight(1f)) {
            PlaylistTitleBlock(shown, wide = true, actions = actions, margin = 0.dp)
            PlaylistActionsRow(shown, actions, wide = true, margin = 0.dp)
        }
    }
}

/** The overline, the title, the owner and the count and time. */
@Composable
internal fun PlaylistTitleBlock(shown: PlaylistShown, wide: Boolean, actions: PlaylistActions, margin: Dp) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val overline = stringResource(
        when {
            shown.kind == PlaylistKind.LOCAL -> Res.string.video_your_playlist
            shown.saved -> Res.string.video_saved_playlist
            else -> Res.string.video_playlist
        },
    )
    val titleStyle = if (wide) type.display(TypeScale.DISPLAY_S) else type.title(TypeScale.TITLE_L)
    val meta = playlistMeta(shown.videoCount, shown.totalSec)
    Column(Modifier.fillMaxWidth().padding(start = margin, end = margin, top = if (wide) 0.dp else 16.dp)) {
        Text(
            text = overline,
            style = type.forText(
                TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 12.sp * 1.3f, letterSpacing = 0.01.em),
                overline,
            ),
            color = Color(palette.textSecondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = shown.title,
            style = type.forText(titleStyle, shown.title),
            color = Color(palette.textPrimary),
            maxLines = if (wide) 2 else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp).semantics { heading() },
        )
        val owner = shown.ownerName?.takeIf { it.isNotBlank() }
        if (owner != null) OwnerRow(owner, actions.onOpenOwner)
        if (meta != null) {
            Text(
                text = meta,
                style = rememberMetaStyle(META_SP, meta),
                color = Color(palette.textTertiary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (owner != null) 0.dp else 6.dp),
            )
        }
    }
}

/** The owner (spec 5.6): a 28 dp circle with their initial, and the name in 15 sp weight 650; a 48 dp row that opens their channel when it can. */
@Composable
private fun OwnerRow(name: String, onClick: (() -> Unit)?) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val initial = remember(name) { PlaylistText.initial(name) }
    val interaction = remember { MutableInteractionSource() }
    val action = if (onClick != null) {
        Modifier.cosmoFocusRing(interaction, CosmoRadii.xs).clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(action),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OWNER_GAP),
    ) {
        Box(
            Modifier
                .size(OWNER_AVATAR)
                .clip(CircleShape)
                .drawBehind { drawRect(colors.live(CoverField.ACCENT_CONTAINER)) }
                // the name beside it says who this is
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial,
                style = type.forText(TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp), initial),
                color = Color(palette.onAccentContainer),
            )
        }
        Text(
            text = name,
            style = type.forText(
                TextStyle(fontFamily = type.textFamily, fontWeight = FontWeight(650), fontSize = OWNER_NAME_SP.sp, lineHeight = OWNER_NAME_SP.sp * 1.3f),
                name,
            ),
            color = Color(palette.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "42 videos · 3 h 12 min": the count and the time that are known, joined; null when neither is. */
@Composable
internal fun playlistMeta(count: Int?, totalSec: Long?): String? {
    val countText = count?.let { pluralStringResource(Res.plurals.video_count_videos, it, it) }
    val timeText = totalSec?.let { durationWords(it) }
    return when {
        countText != null && timeText != null -> stringResource(Res.string.video_meta_join, countText, timeText)
        else -> countText ?: timeText
    }
}

/** A total time in words: "3 h 12 min", "45 min"; under a minute the badge's form ("0:40"). */
@Composable
internal fun durationWords(sec: Long): String {
    val parts = VideoText.durationParts(sec)
    return when {
        parts.hours > 0 -> stringResource(Res.string.video_duration_hm, parts.hours, parts.minutes)
        parts.minutes > 0 -> stringResource(Res.string.video_duration_m, parts.minutes)
        else -> VideoText.duration(sec)
    }
}

/**
 * The actions (spec 5.6): "Play all" (`PRIMARY`, `LARGE`), Shuffle (a 48 dp tonal icon button), Save (a bookmark, a tonal icon button that is on
 * when saved; remote playlists), "Download all" (a tonal icon button), and More (plain; it holds Share for a remote playlist, Rename and Delete
 * for your own), 8 dp apart (4 under 380 dp with four icon buttons). Padding 16 [margin] 6 on the phone; on the wide page the buttons wrap
 * under the title.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlaylistActionsRow(shown: PlaylistShown, actions: PlaylistActions, wide: Boolean, margin: Dp) {
    val playAll = stringResource(Res.string.video_play_all)
    val shuffle = stringResource(SharedRes.string.cosmo_shuffle)
    val downloadAll = stringResource(Res.string.video_dl_all)
    val save = stringResource(if (shown.saved) Res.string.video_unsave_playlist else Res.string.video_save_playlist)
    val hasMore = actions.onShare != null || actions.onRename != null || actions.onDelete != null
    val content: @Composable () -> Unit = {
        CosmoButton(
            text = playAll,
            onClick = actions.onPlayAll,
            kind = CosmoButtonKind.PRIMARY,
            size = CosmoButtonSize.LARGE,
            icon = CosmoIcons.Play,
            enabled = actions.hasVideos,
        )
        CosmoIconButton(
            icon = CosmoIcons.Shuffle,
            contentDescription = shuffle,
            onClick = actions.onShuffle,
            style = CosmoIconButtonStyle.TONAL,
            size = 48.dp,
            enabled = actions.hasVideos,
        )
        if (actions.onToggleSaved != null) {
            CosmoIconButton(
                icon = VideoIcons.Bookmark,
                contentDescription = save,
                onClick = actions.onToggleSaved,
                style = CosmoIconButtonStyle.TONAL,
                size = 48.dp,
                on = shown.saved,
            )
        }
        CosmoIconButton(
            icon = CosmoIcons.Download,
            contentDescription = downloadAll,
            onClick = actions.onDownloadAll,
            style = CosmoIconButtonStyle.TONAL,
            size = 48.dp,
            enabled = actions.hasVideos && !actions.preparing,
        )
    }
    if (wide) {
        FlowRow(
            Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(ACTIONS_GAP),
            verticalArrangement = Arrangement.spacedBy(ACTIONS_GAP),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            content()
            if (hasMore) PlaylistMore(actions)
        }
    } else {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = if (maxWidth < NARROW_DP) ACTIONS_GAP_NARROW else ACTIONS_GAP
            Row(
                Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 16.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content()
                Spacer(Modifier.weight(1f))
                if (hasMore) PlaylistMore(actions)
            }
        }
    }
}

/** The More button and its menu: Share, or Rename and Delete. */
@Composable
private fun PlaylistMore(actions: PlaylistActions) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    var open by remember { mutableStateOf(false) }
    Box {
        CosmoIconButton(
            icon = CosmoIcons.MoreVertical,
            contentDescription = stringResource(SharedRes.string.more),
            onClick = { open = true },
            style = CosmoIconButtonStyle.PLAIN,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun entry(label: String, onClick: () -> Unit) {
                DropdownMenuItem(
                    text = { Text(label, style = type.label, color = Color(palette.textPrimary)) },
                    onClick = {
                        open = false
                        onClick()
                    },
                )
            }
            actions.onShare?.let { entry(stringResource(Res.string.video_share), it) }
            actions.onRename?.let { entry(stringResource(Res.string.video_rename), it) }
            actions.onDelete?.let { entry(stringResource(Res.string.video_delete), it) }
        }
    }
}

/** The thumbnail, three lines of title and the buttons as they will be, in shimmer blocks, while the first page loads. */
@Composable
internal fun PlaylistHeaderSkeleton(wide: Boolean, margin: Dp) {
    if (wide) {
        Row(
            Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(WIDE_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CosmoShimmer(Modifier.size(WIDE_THUMB_WIDTH, WIDE_THUMB_HEIGHT), SuperellipseShape(CosmoRadii.lg))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CosmoShimmer(Modifier.fillMaxWidth(0.2f).height(12.dp))
                CosmoShimmer(Modifier.fillMaxWidth(0.7f).height(28.dp))
                CosmoShimmer(Modifier.fillMaxWidth(0.3f).height(13.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(ACTIONS_GAP)) {
                    CosmoShimmer(Modifier.size(124.dp, 56.dp), SuperellipseShape(17.dp))
                    CosmoShimmer(Modifier.size(48.dp), SuperellipseShape(14.dp))
                    CosmoShimmer(Modifier.size(48.dp), SuperellipseShape(14.dp))
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 6.dp)) {
                CosmoShimmer(Modifier.fillMaxWidth().aspectRatio(16f / 9f), SuperellipseShape(CosmoRadii.lg))
            }
            Column(Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CosmoShimmer(Modifier.fillMaxWidth(0.2f).height(12.dp))
                CosmoShimmer(Modifier.fillMaxWidth(0.8f).height(24.dp))
                CosmoShimmer(Modifier.fillMaxWidth(0.4f).height(13.dp))
            }
            Row(Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 16.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(ACTIONS_GAP)) {
                CosmoShimmer(Modifier.size(124.dp, 56.dp), SuperellipseShape(17.dp))
                CosmoShimmer(Modifier.size(48.dp), SuperellipseShape(14.dp))
                CosmoShimmer(Modifier.size(48.dp), SuperellipseShape(14.dp))
            }
        }
    }
}
