package com.cosmotune.ui.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.Springs
import com.cosmotune.design.WideNowPlayingMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSegmented
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.liftShadow
import com.cosmotune.ui.shell.LocalCosmoPlayerDismiss
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.cosmoAmbientGround
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.Song
import com.music.bitchord.sharedui.resources.Res
import com.music.bitchord.sharedui.resources.cosmo_collapse_player
import com.music.bitchord.sharedui.resources.cosmo_video_hide
import com.music.bitchord.sharedui.resources.cosmo_video_show
import com.music.bitchord.sharedui.resources.cosmo_watch_music_video
import com.music.bitchord.sharedui.resources.more
import com.music.bitchord.sharedui.resources.open_lyrics
import com.music.bitchord.sharedui.resources.played_by
import com.music.bitchord.sharedui.resources.playing_from
import com.music.bitchord.sharedui.resources.playing_radio
import com.music.bitchord.sharedui.resources.queue
import com.music.bitchord.ui.player.CanvasArtworkPlayer
import com.music.bitchord.ui.player.CanvasContentMode
import com.music.bitchord.ui.player.PlayerPlatform
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.jetbrains.compose.resources.stringResource

/**
 * Now Playing in a wide window (`V2.deskNow`; the desktop, or a tablet on its side): five pieces on the ambient ground, placed
 * where [WideNowPlayingMetrics.plan] says for the size of the window ([windowWidth] by [windowHeight]; the board's 1440 x 900
 * frame gives the board's numbers):
 * the [header] across the top, the [panel] (lyrics or queue) on the left, the [media] on the right (the video card over the cover,
 * which is the card's width square and centred in its height), and the [bar] along the foot, to the left of where the card's
 * column ends.
 *
 * Under the pieces is the ambient ground with the media, marked as what the sheets blur ([hazeState]). Everything keeps clear of
 * the display cutout and the system bars on all four sides (the desktop has none). Nothing here reads the playhead or the reveal.
 *
 * Each slot gets the [Modifier] that places and sizes it (the bar gets its place and its width: it is 80 dp tall by itself); the
 * [media] slot gets one for the cover and one for the card, and the cover's side.
 */
@Composable
internal fun NowPlayingWide(
    windowWidth: Dp,
    windowHeight: Dp,
    hazeState: HazeState,
    header: @Composable (Modifier) -> Unit,
    panel: @Composable (Modifier) -> Unit,
    media: @Composable (cover: Modifier, video: Modifier, coverSide: Dp) -> Unit,
    bar: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val left = with(density) { insets.getLeft(density, direction).toDp() }
    val top = with(density) { insets.getTop(density).toDp() }
    val right = with(density) { insets.getRight(density, direction).toDp() }
    val bottom = with(density) { insets.getBottom(density).toDp() }
    // The window's size comes from the caller (the overlay measured it), so nothing here is composed during layout.
    Box(modifier.fillMaxSize()) {
        val plan = remember(windowWidth, windowHeight, left, top, right, bottom) {
            WideNowPlayingMetrics.plan(windowWidth.value - (left + right).value, windowHeight.value - (top + bottom).value)
        }
        Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
            Box(Modifier.fillMaxSize().cosmoAmbientGround())
            media(Modifier.placed(plan.cover, left, top), Modifier.placed(plan.video, left, top), plan.cover.width.dp)
        }
        header(Modifier.placed(plan.header, left, top))
        panel(Modifier.placed(plan.lyrics, left, top))
        bar(Modifier.offset(left + plan.bar.x.dp, top + plan.bar.y.dp).width(plan.bar.width.dp))
    }
}

/** [box] ([WideNowPlayingMetrics] dp, from the content area's top-left) placed in the window, [left] and [top] in from its edges. */
private fun Modifier.placed(box: WideNowPlayingMetrics.Box, left: Dp, top: Dp): Modifier =
    offset(left + box.x.dp, top + box.y.dp).size(box.width.dp, box.height.dp)

/**
 * The header (`.dn-head`): the song's cover (56 dp), its title (17 sp, weight 700) over "artist · where it is playing from" (13.5 sp,
 * secondary ink: a tap on that line goes where the phone's caption goes), then the Lyrics | Queue tabs, the video button (only
 * when the song has a video), More and the button that closes the player; 14 dp apart. The tabs are the two panels of the left
 * column, one of them always showing.
 *
 * The wide page has no tools row, so [onWatchMusicVideo] ("Watch the music video", Video mode's link) puts its button here, just
 * before the video button; null leaves the header as it is.
 */
@Composable
internal fun NowPlayingWideHeader(
    song: Song,
    playedBy: String?,
    coverUrl: String?,
    queueSelected: Boolean,
    onSelectTab: (Int) -> Unit,
    hasVideo: Boolean,
    videoOn: Boolean,
    onToggleVideo: () -> Unit,
    onOpenOrigin: () -> Unit,
    onOpenMenu: () -> Unit,
    modifier: Modifier = Modifier,
    onWatchMusicVideo: (() -> Unit)? = null,
) {
    val dismiss = LocalCosmoPlayerDismiss.current
    val type = LocalCosmoType.current
    val palette = LocalCosmoColors.current.palette
    val whole: String? = playedBy?.let { stringResource(Res.string.played_by, it) }
        ?: song.radioName?.let { stringResource(Res.string.playing_radio, it) }
    val source = whole ?: stringResource(Res.string.playing_from, song.playbackSource ?: song.albumName ?: stringResource(Res.string.queue))
    val caption = listOf(song.artist, source).filter { it.isNotBlank() }.joinToString(" · ")
    val title = remember(type, song.title) { type.forText(TextStyle(fontFamily = type.textFamily, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold), song.title) }
    val line = remember(type, caption) { type.forText(TextStyle(fontFamily = type.textFamily, fontSize = 13.5.sp, lineHeight = 17.sp), caption) }
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(WideNowPlayingMetrics.HEADER_GAP_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(coverUrl, WideNowPlayingMetrics.HEADER_COVER_DP.dp, CosmoRadii.coverDesktopHead)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(song.title, style = title, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                caption,
                style = line,
                color = Color(palette.textSecondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(CosmoRadii.xs)).clickable(role = Role.Button, onClick = onOpenOrigin),
            )
        }
        CosmoSegmented(listOf(stringResource(Res.string.open_lyrics), stringResource(Res.string.queue)), if (queueSelected) 1 else 0, onSelectTab)
        if (onWatchMusicVideo != null) {
            CosmoIconButton(CosmoIcons.Video, stringResource(Res.string.cosmo_watch_music_video), onWatchMusicVideo, style = CosmoIconButtonStyle.TONAL, size = HEADER_BUTTON)
        }
        if (hasVideo) {
            CosmoIconButton(
                if (videoOn) CosmoIcons.Canvas else CosmoIcons.CanvasOff,
                stringResource(if (videoOn) Res.string.cosmo_video_hide else Res.string.cosmo_video_show),
                onToggleVideo,
                style = CosmoIconButtonStyle.TONAL, size = HEADER_BUTTON, on = videoOn,
            )
        }
        CosmoIconButton(CosmoIcons.More, stringResource(Res.string.more), onOpenMenu, style = CosmoIconButtonStyle.TONAL, size = HEADER_BUTTON)
        CosmoIconButton(CosmoIcons.ChevronDown, stringResource(Res.string.cosmo_collapse_player), dismiss, style = CosmoIconButtonStyle.TONAL, size = HEADER_BUTTON)
    }
}

/**
 * The left column: the lyrics or the queue, one of them at a time, crossing over in [PANEL_FADE_MS] (150 ms under reduced
 * motion). Both get the column's whole box; the queue's card and the lyrics' fading edges end 8 dp above the player bar.
 */
@Composable
internal fun NowPlayingWidePanels(
    queueSelected: Boolean,
    reduced: Boolean,
    lyrics: @Composable () -> Unit,
    queue: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ms = if (reduced) Springs.REDUCED_MS else PANEL_FADE_MS
    Box(modifier) {
        AnimatedVisibility(!queueSelected, Modifier.fillMaxSize(), enter = fadeIn(tween(ms)), exit = fadeOut(tween(ms))) { lyrics() }
        AnimatedVisibility(queueSelected, Modifier.fillMaxSize(), enter = fadeIn(tween(ms)), exit = fadeOut(tween(ms))) { queue() }
    }
}

/**
 * The right column: the cover (the card's width, square, radius 28, with its own shadow) and, over it, the video card
 * (396 x 704 on the board's frame: radius 32, with a shadow whose key light is the cover's glow at 55 %). [reveal] (0 = the cover
 * shows, 1 = the video is all the way in) crosses one into the other: the cover fades out as the clip's own alpha comes up
 * (`presentationAlpha`, not a Compose layer: on the phone the clip is a `TextureView`) and the card's shadow comes in with it. The
 * cover's resting rectangle goes to the shell as `playerCoverBounds`, which the open and close morph lands its travelling cover on.
 *
 * While [blockedByMobileData] the host's one-line "Video is paused on mobile data" with its Allow button sits at the card's
 * top-left corner, 14 dp in, where the board's video tag sits. Nothing here is read per frame except in layer, draw and the clip's
 * own alpha lambda.
 */
@Composable
internal fun NowPlayingWideMedia(
    canvas: CanvasArtwork?,
    wantVideo: Boolean,
    isPlaying: Boolean,
    reveal: State<Float>,
    onRenderedChanged: (Boolean) -> Unit,
    blockedByMobileData: Boolean,
    coverUrl: String?,
    shell: CosmoShellState?,
    coverModifier: Modifier,
    videoModifier: Modifier,
    coverSide: Dp,
) {
    val colors = LocalCosmoColors.current
    val shape = remember { SuperellipseShape(WideNowPlayingMetrics.VIDEO_RADIUS_DP.dp) }
    Box(
        coverModifier
            .onGloballyPositioned { shell?.playerCoverBounds = it.boundsInRoot() }
            // Each draw operation takes the alpha on its own (no offscreen layer), so the cover's shadow is not cut at its bounds.
            .graphicsLayer { alpha = 1f - reveal.value.coerceIn(0f, 1f); compositingStrategy = CompositingStrategy.ModulateAlpha },
    ) {
        CoverImage(coverUrl, coverSide, CosmoRadii.coverNowPlaying)
    }
    if (wantVideo && canvas != null) {
        Box(
            videoModifier.liftShadow(
                shape, VIDEO_ELEVATION,
                // The shadow's own colours fade with the video: a layer's alpha would cut it at the card's edge.
                spot = { colors.live(CoverField.GLOW).let { it.copy(alpha = it.alpha * GLOW_SHADOW_ALPHA * reveal.value.coerceIn(0f, 1f)) } },
                ambient = { Color.Black.copy(alpha = AMBIENT_SHADOW_ALPHA * reveal.value.coerceIn(0f, 1f)) },
            ),
        ) {
            Box(Modifier.fillMaxSize().clip(shape)) {
                CanvasArtworkPlayer(
                    canvas = canvas,
                    isPlaying = isPlaying,
                    modifier = Modifier.fillMaxSize(),
                    contentMode = CanvasContentMode.CROP,
                    onRenderedChanged = onRenderedChanged,
                    presentationAlpha = { reveal.value.coerceIn(0f, 1f) },
                )
            }
        }
    }
    if (blockedByMobileData) {
        Box(videoModifier.padding(14.dp), contentAlignment = Alignment.TopStart) { PlayerPlatform.host.CanvasMobileDataHint(Modifier) }
    }
}

private val HEADER_BUTTON = 40.dp

/** The card's lift: more than the cover's 14 dp, it is a bigger thing. */
private val VIDEO_ELEVATION = 28.dp

/** The glow's share of the key shadow (`color-mix(glow 55%, transparent)`), and the soft black round the card. */
private const val GLOW_SHADOW_ALPHA = 0.55f
private const val AMBIENT_SHADOW_ALPHA = 0.35f
