package com.cosmotune.desktop

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import com.cosmotune.music.desktop.desktopSongContextMenu
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.BlockMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.components.CoverFrame
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.tabular
import com.cosmotune.music.data.model.ROW_ART_PX
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.components.SourceQualityBadge
import com.cosmotune.music.ui.components.sourceQualityBadge
import com.cosmotune.music.desktop.DesktopArtwork
import com.cosmotune.music.desktop.DesktopStrings

/*
 * The pieces the desktop's song rows share (`v2/screens-v2.css` `.d2-tracks li`): the hover and wash animations of a row, its
 * hover-revealed actions and the duration's type, and the locals the root fills for them. The rows themselves are the collection
 * page's (`CollectionTrackRow`, on the shared `OrbitTrackRow`).
 */

/** The size of an action button of a row: the desktop's pointer does not need the phone's 48 dp. */
internal val DESKTOP_ROW_ACTION: Dp = 32.dp

/** `.d2-tracks li` columns: the duration takes 60 dp. */
internal val DESKTOP_DURATION_COLUMN: Dp = 60.dp

/**
 * Whether the audio is playing right now (not paused), for the bars of the playing row. The old pages cannot see it (`playback` is a
 * local of the root composable), so the root provides it once, next to the playing song, and the pages start their `isPlaying`
 * from it. False where nothing provides it (a render, a test).
 */
internal val LocalCosmoPlaying = compositionLocalOf { false }

/**
 * The player's play/pause as the user would press it (the one that honours Listen Together's lock), for the artist page's big button.
 * Provided by the root, like [LocalCosmoPlaying]; null where nothing provides it, and the page then plays its list on every tap.
 */
internal val LocalCosmoPlayPause = compositionLocalOf<(() -> Unit)?> { null }

/** A callback with one identity for as long as it stays in the composition that always calls the latest [action]: a local that holds it never invalidates its readers. */
@Composable
internal fun rememberStableAction(action: () -> Unit): () -> Unit {
    val latest by rememberUpdatedState(action)
    return remember { { latest() } }
}

/** The duration and the other faint numbers of a row: 13 sp with tabular figures, so a column of them lines up. */
internal fun CosmoType.desktopRowDuration(): TextStyle =
    TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 13.sp * 1.3f).tabular()

// ---- the surface of a row ----

/**
 * What a row animates: the state layer under the pointer, the wash of the song that is playing, and how far the hover-revealed
 * actions have come in. The three are read while drawing, so a pointer crossing the row recomposes it twice (in and out) and no more.
 */
@Stable
internal class CosmoRowChrome(
    val interaction: MutableInteractionSource,
    val hover: State<Float>,
    val wash: State<Float>,
    val reveal: State<Float>,
    val focusWithin: MutableState<Boolean>,
)

@Composable
internal fun rememberCosmoRowChrome(current: Boolean): CosmoRowChrome {
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focusWithin = remember { mutableStateOf(false) }
    val hover = animateFloatAsState(if (hovered) 1f else 0f, CosmoMotion.effects(reduced), label = "row hover")
    val wash = animateFloatAsState(if (current) 1f else 0f, CosmoMotion.effects(reduced), label = "row wash")
    val reveal = animateFloatAsState(if (hovered || focusWithin.value) 1f else 0f, CosmoMotion.effects(reduced), label = "row actions")
    return remember(interaction) { CosmoRowChrome(interaction, hover, wash, reveal, focusWithin) }
}

// ---- the actions of a row ----

/**
 * The like, download, add and remove buttons of a row, 32 dp each and PLAIN, in that order, before the duration. A button that
 * has nothing to say stays out of the way until the pointer is over the row or the keyboard is in it ([reveal]); the heart of a
 * liked song and the download of a song that is downloading or downloaded are always shown. Every button that can exist on the page
 * keeps its place whether it shows or not, so the duration column lines up from row to row: [reserveRemove] keeps the remove
 * button's place on a row that cannot be removed.
 *
 * A button is 32 dp in the row's layout: the 48 dp touch target is for fingers, and the desktop's pointer is not one.
 */
@Composable
internal fun CosmoDesktopRowActions(
    song: Song,
    reveal: State<Float>,
    liked: Boolean,
    onToggleLike: ((Song) -> Unit)?,
    onDownload: ((Song) -> Unit)?,
    downloaded: Boolean,
    downloadInProgress: Boolean,
    onAddToPlaylist: ((Song) -> Unit)?,
    onRemove: ((Song) -> Unit)?,
    reserveRemove: Boolean,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides DESKTOP_ROW_ACTION) {
        Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            onToggleLike?.let { toggle ->
                CosmoIconButton(
                    icon = if (liked) CosmoIcons.HeartFill else CosmoIcons.Heart,
                    contentDescription = DesktopStrings["like", "Like"],
                    onClick = { toggle(song) },
                    modifier = Modifier.revealedBy(reveal, always = liked),
                    style = CosmoIconButtonStyle.PLAIN,
                    size = DESKTOP_ROW_ACTION,
                    on = liked,
                    accentWhenOn = true,
                )
            }
            onDownload?.let { save ->
                val shown = downloaded || downloadInProgress
                if (downloadInProgress) {
                    DownloadingMark(
                        label = DesktopStrings["download", "Download"],
                        onClick = { save(song) },
                        modifier = Modifier.revealedBy(reveal, always = true),
                    )
                } else {
                    CosmoIconButton(
                        icon = if (downloaded) CosmoIcons.Downloaded else CosmoIcons.Download,
                        contentDescription = if (downloaded) DesktopStrings["downloaded", "Downloaded"] else DesktopStrings["download", "Download"],
                        onClick = { if (!downloaded) save(song) },
                        modifier = Modifier.revealedBy(reveal, always = shown),
                        style = CosmoIconButtonStyle.PLAIN,
                        size = DESKTOP_ROW_ACTION,
                        on = if (downloaded) true else null,
                        accentWhenOn = true,
                    )
                }
            }
            onAddToPlaylist?.let { add ->
                CosmoIconButton(
                    icon = CosmoIcons.Plus,
                    contentDescription = DesktopStrings["add_to_playlist", "Add to playlist"],
                    onClick = { add(song) },
                    modifier = Modifier.revealedBy(reveal, always = false),
                    style = CosmoIconButtonStyle.PLAIN,
                    size = DESKTOP_ROW_ACTION,
                )
            }
            if (onRemove != null) {
                CosmoIconButton(
                    icon = CosmoIcons.Close,
                    contentDescription = DesktopStrings["d_remove_from_playlist", "Remove from playlist"],
                    onClick = { onRemove(song) },
                    modifier = Modifier.revealedBy(reveal, always = false),
                    style = CosmoIconButtonStyle.PLAIN,
                    size = DESKTOP_ROW_ACTION,
                )
            } else if (reserveRemove) {
                Spacer(Modifier.size(DESKTOP_ROW_ACTION))
            }
        }
    }
}

/** The opacity of a revealed action, read while drawing: fully there when [always], otherwise as far in as the row's [reveal]. */
private fun Modifier.revealedBy(reveal: State<Float>, always: Boolean): Modifier =
    graphicsLayer { alpha = if (always) 1f else reveal.value }

/** The spinner of a download under way, in a button's place: clicking it asks again, as the old row did. */
@Composable
private fun DownloadingMark(label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(DESKTOP_ROW_ACTION)
            .clip(RoundedCornerShape(CosmoRadii.rSm))
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CosmoLoadingBlob(Modifier.size(16.dp))
    }
}
