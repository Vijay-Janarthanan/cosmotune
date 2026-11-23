package com.cosmotune.video.ui.player

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.player.ChapterSkip
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_unlock_controls
import com.cosmotune.video.ui.VideoIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Over the locked fullscreen video: it takes every touch, so a pocket or a child cannot pause, seek or leave; a tap shows the unlock
 * button at the top for a moment, and the button opens the lock.
 */
@Composable
internal fun PlayerLockLayer(onUnlock: () -> Unit, modifier: Modifier) {
    var shownAt by remember { mutableStateOf(0L) }
    LaunchedEffect(shownAt) {
        if (shownAt != 0L) {
            kotlinx.coroutines.delay(LOCK_BUTTON_MS)
            shownAt = 0L
        }
    }
    Box(modifier.pointerInput(Unit) { detectTapGestures { shownAt = System.nanoTime() } }) {
        if (shownAt != 0L) {
            PlayerButton(
                icon = VideoIcons.Lock,
                contentDescription = stringResource(Res.string.video_unlock_controls),
                onClick = onUnlock,
                size = VideoMetrics.CHROME_ICON_DP.dp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            )
        }
    }
}

/** How long the unlock button stays after a tap on the locked video. */
private const val LOCK_BUTTON_MS = 3_000L

/** Seeks to the next chapter (or the start of this one, or the one before, going back); false when there is none to go to. */
internal fun skipChapter(playback: VideoPlayback, state: PlaybackUiState, forward: Boolean): Boolean {
    val chapters = state.details?.chapters.orEmpty()
    val at = playback.positionMs()
    val target = (if (forward) ChapterSkip.next(chapters, at) else ChapterSkip.previous(chapters, at)) ?: return false
    playback.seekTo(target)
    return true
}
