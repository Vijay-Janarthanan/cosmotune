package com.cosmotune.video.ui.playlist

import com.cosmotune.ui.orbit.OrbitPageHeader
import com.cosmotune.ui.orbit.OrbitHeaderButton
import com.cosmotune.ui.orbit.OrbitHeaderSkeleton
import com.cosmotune.ui.orbit.OrbitCardColors
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.ui.blocks.ThumbnailFrame
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.bridge.DropdownMenu
import com.cosmotune.ui.bridge.DropdownMenuItem
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing
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
import com.cosmotune.video.resources.video_playlist_save_csv
import com.cosmotune.video.resources.video_playlist_save_links
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
import androidx.compose.foundation.shape.RoundedCornerShape
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * The head of a playlist page (spec 5.6) as an Orbit colour card: the picture, what the page is, the title, the owner, the count and
 * the time, and the actions, with "Play all" in the bite.
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
    /** Saves the playlist to a file: as a list of links, or as the site's own CSV. */
    val onExport: ((com.cosmotune.video.library.PlaylistExport.Kind) -> Unit)? = null,
)

/**
 * The playlist's colour card (Orbit, as the album page): the shared compact header ([OrbitPageHeader]) in the colour of the playlist's
 * picture, the picture as its disc, the mono line ("PLAYLIST · 42 VIDEOS · 3 H 12 MIN"), the title in
 * the wide face, the owner (a tap opens their channel), and the round buttons: Shuffle, Save (a remote playlist), "Download all" and
 * More (Share, or Rename and Delete). "Play all" stands in the bite.
 */
@Composable
internal fun PlaylistCard(shown: PlaylistShown, actions: PlaylistActions, wide: Boolean, margin: Dp) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val (fill, ink) = coverFill(shown.thumbnailUrl, shown.id)
    val kind = stringResource(
        when {
            shown.kind == PlaylistKind.LOCAL -> Res.string.video_your_playlist
            shown.saved -> Res.string.video_saved_playlist
            else -> Res.string.video_playlist
        },
    )
    val label = listOfNotNull(kind, playlistMeta(shown.videoCount, shown.totalSec)).joinToString(" · ").uppercase()
    val owner = shown.ownerName?.takeIf { it.isNotBlank() }
    val playAll = stringResource(Res.string.video_play_all)
    val shuffle = stringResource(SharedRes.string.cosmo_shuffle)
    val downloadAll = stringResource(Res.string.video_dl_all)
    val save = stringResource(if (shown.saved) Res.string.video_unsave_playlist else Res.string.video_save_playlist)
    val hasMore = actions.onShare != null || actions.onRename != null || actions.onDelete != null || actions.onExport != null
    // The shared compact header (as the album page): the picture as its disc beside the words, Shuffle, Save, Download all and More
    // as the one row of actions, and Play all in the bite.
    OrbitPageHeader(
        cover = shown.thumbnailUrl, label = label, title = shown.title, subtitle = owner, playing = false,
        onPlay = { if (actions.hasVideos) actions.onPlayAll() },
        modifier = Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 6.dp, bottom = 12.dp).testTag("video:playlist:card")
            .semantics { contentDescription = playAll },
        colors = OrbitCardColors(fill, ink), onSubtitle = actions.onOpenOwner,
    ) { colors ->
        val faded = if (actions.hasVideos) colors else OrbitCardColors(colors.fill, colors.ink.copy(alpha = 0.4f))
        OrbitHeaderButton(CosmoIcons.Shuffle, shuffle, faded, { if (actions.hasVideos) actions.onShuffle() })
        actions.onToggleSaved?.let { OrbitHeaderButton(VideoIcons.Bookmark, save, colors, it, on = shown.saved) }
        val canDownload = actions.hasVideos && !actions.preparing
        OrbitHeaderButton(CosmoIcons.Download, downloadAll, if (canDownload) colors else OrbitCardColors(colors.fill, colors.ink.copy(alpha = 0.4f)),
            { if (canDownload) actions.onDownloadAll() })
        if (hasMore) PlaylistMore(actions, colors.ink, colors.fill)
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

/** The More button on the card and its Orbit menu: Share, or Rename and Delete. */
@Composable
private fun PlaylistMore(actions: PlaylistActions, ink: Color, fill: Color) {
    var open by remember { mutableStateOf(false) }
    val share = stringResource(Res.string.video_share)
    val rename = stringResource(Res.string.video_rename)
    val delete = stringResource(Res.string.video_delete)
    Box {
        OrbitRoundButton(CosmoIcons.MoreVertical, stringResource(SharedRes.string.more), ink, { open = true }, onInk = fill)
        OrbitMenu(
            open = open, onDismiss = { open = false }, fonts = orbitFonts(), look = orbitLook(),
            items = listOfNotNull(
                actions.onShare?.let { OrbitMenuItem(share, icon = CosmoIcons.Share, onClick = it) },
                actions.onExport?.let { export ->
                    OrbitMenuItem(stringResource(Res.string.video_playlist_save_links), icon = CosmoIcons.Download) { export(com.cosmotune.video.library.PlaylistExport.Kind.LINKS) }
                },
                actions.onExport?.let { export ->
                    OrbitMenuItem(stringResource(Res.string.video_playlist_save_csv), icon = CosmoIcons.Download) { export(com.cosmotune.video.library.PlaylistExport.Kind.CSV) }
                },
                actions.onRename?.let { OrbitMenuItem(rename, onClick = it) },
                actions.onDelete?.let { OrbitMenuItem(delete, icon = CosmoIcons.Close, onClick = it) },
            ),
        )
    }
}

/** The colour card as it will be, a quiet block with the picture's, the words' and the buttons' places in shimmer, while the first page loads. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun PlaylistHeaderSkeleton(wide: Boolean, margin: Dp) {
    OrbitHeaderSkeleton(Modifier.fillMaxWidth().padding(start = margin, end = margin, top = 6.dp, bottom = 12.dp))
}
