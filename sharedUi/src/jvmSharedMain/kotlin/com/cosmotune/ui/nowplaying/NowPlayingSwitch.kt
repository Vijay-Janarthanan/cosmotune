package com.cosmotune.ui.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.cosmotune.ui.theme.LocalCosmoShell
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.LyricsProviderState
import com.music.bitchord.ui.player.NowPlayingScreen

/**
 * The one entry point of Now Playing: the new screen while the new look is on (the shell exists), the old one while it is off.
 *
 * The parameter list is [NowPlayingScreen]'s, name for name and in the same order, and both branches pass every argument by
 * name. That is the seam: when upstream adds a parameter to [NowPlayingScreen] the old branch stops compiling, and this file is
 * where the new parameter has to be decided on, instead of silently going missing from the new screen. With the new look off
 * nothing here runs but the old call.
 */
@Composable
fun NowPlayingSwitch(
    song: Song,
    playedBy: String? = null,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    audioVersionSwitching: Boolean,
    qualityUpgraded: Boolean,
    queue: List<Song>,
    queueIndex: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    autoplayEnabled: Boolean,
    signedIn: Boolean,
    accountName: String?,
    likeStatus: LikeStatus,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onBlockedControl: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekFraction: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onQueueDragActiveChange: (Boolean) -> Unit = {},
    onClearQueue: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    onOpenPlaybackSource: () -> Unit,
    onListenTogether: () -> Unit,
    lyrics: List<LyricLine>?,
    lyricsSource: LyricsSource?,
    lyricsProviderStates: Map<LyricsSource, LyricsProviderState>,
    onSelectLyricsProvider: (LyricsSource) -> Unit,
    lyricsUnavailable: Boolean,
    lyricsOffsetOpen: Boolean,
    onDismissLyricsOffset: () -> Unit,
    windowWidth: Dp,
    windowHeight: Dp,
    modifier: Modifier = Modifier,
) {
    if (LocalCosmoShell.current != null) {
        CosmoNowPlaying(
            song = song, playedBy = playedBy, isPlaying = isPlaying, isLoading = isLoading, position = position,
            durationMs = durationMs, audioVersionSwitching = audioVersionSwitching, qualityUpgraded = qualityUpgraded, queue = queue,
            queueIndex = queueIndex, hasPrevious = hasPrevious, hasNext = hasNext, repeatMode = repeatMode, shuffleEnabled = shuffleEnabled,
            autoplayEnabled = autoplayEnabled, signedIn = signedIn, accountName = accountName, likeStatus = likeStatus,
            onToggleLike = onToggleLike, onPlayPause = onPlayPause, onNext = onNext, onPrevious = onPrevious,
            onBlockedControl = onBlockedControl, onSeek = onSeek, onSeekFraction = onSeekFraction, onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat, onToggleAutoplay = onToggleAutoplay, onJumpTo = onJumpTo, onRemoveFromQueue = onRemoveFromQueue,
            onMoveInQueue = onMoveInQueue, onQueueDragActiveChange = onQueueDragActiveChange, onClearQueue = onClearQueue,
            onOpenMenu = onOpenMenu, onOpenAlbum = onOpenAlbum, onOpenArtist = onOpenArtist, onOpenPlaybackSource = onOpenPlaybackSource,
            onListenTogether = onListenTogether, lyrics = lyrics, lyricsSource = lyricsSource, lyricsProviderStates = lyricsProviderStates,
            onSelectLyricsProvider = onSelectLyricsProvider, lyricsUnavailable = lyricsUnavailable, lyricsOffsetOpen = lyricsOffsetOpen,
            onDismissLyricsOffset = onDismissLyricsOffset, windowWidth = windowWidth, windowHeight = windowHeight, modifier = modifier,
        )
    } else {
        NowPlayingScreen(
            song = song, playedBy = playedBy, isPlaying = isPlaying, isLoading = isLoading, position = position,
            durationMs = durationMs, audioVersionSwitching = audioVersionSwitching, qualityUpgraded = qualityUpgraded, queue = queue,
            queueIndex = queueIndex, hasPrevious = hasPrevious, hasNext = hasNext, repeatMode = repeatMode, shuffleEnabled = shuffleEnabled,
            autoplayEnabled = autoplayEnabled, signedIn = signedIn, accountName = accountName, likeStatus = likeStatus,
            onToggleLike = onToggleLike, onPlayPause = onPlayPause, onNext = onNext, onPrevious = onPrevious,
            onBlockedControl = onBlockedControl, onSeek = onSeek, onSeekFraction = onSeekFraction, onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat, onToggleAutoplay = onToggleAutoplay, onJumpTo = onJumpTo, onRemoveFromQueue = onRemoveFromQueue,
            onMoveInQueue = onMoveInQueue, onQueueDragActiveChange = onQueueDragActiveChange, onClearQueue = onClearQueue,
            onOpenMenu = onOpenMenu, onOpenAlbum = onOpenAlbum, onOpenArtist = onOpenArtist, onOpenPlaybackSource = onOpenPlaybackSource,
            onListenTogether = onListenTogether, lyrics = lyrics, lyricsSource = lyricsSource, lyricsProviderStates = lyricsProviderStates,
            onSelectLyricsProvider = onSelectLyricsProvider, lyricsUnavailable = lyricsUnavailable, lyricsOffsetOpen = lyricsOffsetOpen,
            onDismissLyricsOffset = onDismissLyricsOffset, windowWidth = windowWidth, windowHeight = windowHeight, modifier = modifier,
        )
    }
}
