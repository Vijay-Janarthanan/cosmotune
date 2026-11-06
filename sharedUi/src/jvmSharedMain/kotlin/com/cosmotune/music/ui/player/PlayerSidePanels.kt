package com.cosmotune.music.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.TypeScale
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricsSource
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.sharedui.resources.*
import com.cosmotune.music.ui.LyricsProviderState
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.nowplaying.CosmoLyricsPanel
import com.cosmotune.ui.nowplaying.LyricsStyle
import com.cosmotune.ui.nowplaying.NowPlayingUpNext
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.LocalCosmoShell
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.jetbrains.compose.resources.stringArrayResource
import org.jetbrains.compose.resources.stringResource

// The player's lyrics and queue, standing on their own outside it: what a
// window shows in a column beside the page, the way Apple Music's sidebar
// does, while the player itself stays closed. The same pieces the landscape
// player lays out in its right-hand pane, so a line lights, a row drags and a
// translation arrives exactly as they do in there.
//
// With the new look on they are Orbit Now Playing's own instead: the lyrics in the wide heading face with the source line and the
// two toggles under them (CosmoLyricsPanel), and the queue as Up next, the kit's song rows with their drag handles
// (NowPlayingUpNext). The old pieces are the look-off branch, unchanged.

/**
 * The lyric sheet with its provider line and its romanization and translation
 * toggles — the landscape player's lyrics pane, without the player around it.
 *
 * [position] is read only by the panel itself, for the same reason the player
 * passes it down as an object: a tick recomposes the lines, not this.
 */
@Composable
fun LyricsSidePanel(
    song: Song,
    isPlaying: Boolean,
    position: PlaybackPosition,
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsProviderStates: Map<LyricsSource, LyricsProviderState>,
    onSelectLyricsProvider: (LyricsSource) -> Unit,
    lyricsUnavailable: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val lyricsOffsetMs by PlayerSettings.lyricsOffsetMs.collectAsStateWithLifecycle()
    val lyricsPlayhead = rememberLyricPlayhead(position)
    val seekToLyric: (Long) -> Unit = { lineTimeMs ->
        onSeek(adjustedLyricsSeekTarget(lineTimeMs, lyricsOffsetMs))
    }
    val lyricsLoadingLines = stringArrayResource(Res.array.lyrics_loading_lines)
    val lyricsLoadingText = remember(song.videoId, lyricsLoadingLines) {
        lyricsLoadingLines.randomOrNull().orEmpty()
    }
    val lyricsTranslation = rememberLyricsTranslation(
        trackId = song.videoId,
        lyrics = lyrics,
        lyricsSource = lyricsSource,
        lyricsUnavailable = lyricsUnavailable,
        loadingText = lyricsLoadingText,
        haptics = haptics,
    )
    // The provider drawer frosts this column, not the page beside it.
    val haze = remember { HazeState() }
    var showLyricsProviders by remember { mutableStateOf(false) }

    val orbit = LocalCosmoShell.current != null
    val orbitWide = orbitFonts().wide
    Box(modifier) {
        if (orbit) Box(Modifier.fillMaxSize().hazeSource(haze)) {
            CosmoLyricsPanel(
                lyrics = lyrics,
                lyricsUnavailable = lyricsUnavailable,
                loadingText = lyricsLoadingText,
                translation = lyricsTranslation,
                trackKey = song.videoId,
                playhead = lyricsPlayhead,
                isPlaying = isPlaying && position.advancing,
                onSeekToLine = seekToLyric,
                onOpenProviders = { showLyricsProviders = true },
                style = remember(orbitWide) { SIDE_LYRICS_STYLE.copy(fontFamily = orbitWide) },
                modifier = Modifier.fillMaxSize(),
                topPadding = 4.dp,
            )
        } else Box(Modifier.fillMaxSize().hazeSource(haze)) {
            LandscapeLyricsPane(
                hasLyrics = lyricsTranslation.displayedLyrics.isNotEmpty(),
                placeholder = if (lyricsUnavailable) {
                    stringResource(Res.string.lyrics_not_available)
                } else {
                    lyricsLoadingText
                },
                status = lyricsTranslation.status,
                onStatusClick = { showLyricsProviders = true },
                romanizationToggle = {
                    RomanizationToggleButton(
                        state = lyricsTranslation.romanizationState,
                        showingRomanization = lyricsTranslation.showingRomanization,
                        enabled = !lyrics.isNullOrEmpty(),
                        onClick = lyricsTranslation.toggleRomanization,
                    )
                },
                translationToggle = {
                    TranslationToggleButton(
                        state = lyricsTranslation.translationState,
                        showingTranslation = lyricsTranslation.showingTranslation,
                        enabled = !lyrics.isNullOrEmpty(),
                        onClick = lyricsTranslation.toggleTranslation,
                    )
                },
            ) { panelModifier ->
                LyricsTranslationMotion(
                    trigger = lyricsTranslation.transition,
                    reduceMotion = lyricsTranslation.reduceMotion,
                    modifier = panelModifier,
                ) { particleProgress ->
                    // Controls always open, as in the landscape player: there
                    // is no transport hidden behind these lines for a tap to
                    // bring back.
                    LyricsPanel(
                        lines = lyrics.orEmpty(),
                        subLines = lyricsTranslation.subLines,
                        trackKey = song.videoId,
                        playhead = lyricsPlayhead,
                        looking = !lyricsUnavailable,
                        isPlaying = isPlaying && position.advancing,
                        onSeekToLine = seekToLyric,
                        controlsOpen = true,
                        onRevealControls = {},
                        onHideControls = {},
                        translationProgress = particleProgress,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (showLyricsProviders) {
            LyricsProviderSheet(
                hazeState = haze,
                currentSource = lyricsSource,
                states = lyricsProviderStates,
                onSelect = onSelectLyricsProvider,
                onDismiss = { showLyricsProviders = false },
            )
        }
    }
}

/**
 * Whether the song plays, for a host whose call of [QueueSidePanel] does not say (the desktop's column provides it): Up next's
 * playing row circles its moon while it does.
 */
val LocalSidePanelPlaying = compositionLocalOf { false }

/**
 * The side panel's lyrics in Orbit: one step smaller than the wide player's (the column is 360 dp), the line being sung 1.12 times
 * as big and the lines already sung 0.9, in the wide heading face.
 */
private val SIDE_LYRICS_STYLE = LyricsStyle(TypeScale.TITLE_S, 1.12f, 0.9f)

/**
 * The live queue — the player's own, rows, drag and all — without the player. With the new look it is Up next: the playing row in
 * peach with its orbit ([isPlaying] circles its moon, [progress] moves it when given), the groups with Clear, and the AutoPlay
 * group's button when [onToggleAutoplay] is given.
 */
@Composable
fun QueueSidePanel(
    queue: List<Song>,
    queueIndex: Int,
    autoplayEnabled: Boolean,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = LocalSidePanelPlaying.current,
    progress: (() -> Float)? = null,
    onToggleAutoplay: (() -> Unit)? = null,
) {
    if (LocalCosmoShell.current != null) {
        NowPlayingUpNext(
            queue = queue,
            queueIndex = queueIndex,
            autoplayEnabled = autoplayEnabled,
            controlsLocked = rememberControlsLocked(),
            isPlaying = isPlaying,
            progress = progress,
            onJumpTo = onJumpTo,
            onRemove = onRemove,
            onMove = onMove,
            onClear = onClear,
            onToggleAutoplay = onToggleAutoplay,
            onDragActiveChange = {},
            fonts = orbitFonts(),
            look = orbitLook(),
            modifier = modifier,
            header = false,
        )
        return
    }
    InlineQueue(
        queue = queue,
        currentIndex = queueIndex,
        autoplayEnabled = autoplayEnabled,
        controlsLocked = rememberControlsLocked(),
        onJumpTo = onJumpTo,
        onRemove = onRemove,
        onMove = onMove,
        onClear = onClear,
        modifier = modifier,
    )
}
