package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.LocalOrbitUndo
import com.cosmotune.ui.orbit.OrbitUndoHost
import com.cosmotune.music.data.model.Song
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitQueueList

/**
 * Up next on the phone's Now Playing: the shared queue ([OrbitQueueList], the Music board's 7a and 7b), the one the desktop's side
 * panel shows. A tap plays a row, the handle drags it within its own group (what you queued, what comes from where you started, what
 * AutoPlay picked), a swipe to the left (or Remove in select mode) takes it out with "Removed · Undo", which [onRestore] answers by
 * putting the song back where it was; Clear empties what you queued, and AutoPlay can be turned on and off at the head of its group.
 * In a party whose host holds the controls ([controlsLocked]) the queue is only looked at.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun NowPlayingUpNext(
    queue: List<Song>,
    queueIndex: Int,
    autoplayEnabled: Boolean,
    controlsLocked: Boolean,
    isPlaying: Boolean,
    /** Where the playing song is (0..1); null where it is not known, and the playing row's moon circles while it plays. */
    progress: (() -> Float)?,
    onJumpTo: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    /** Null where the host has no way to turn AutoPlay on or off (the desktop's side panel): the group heads show no button. */
    onToggleAutoplay: (() -> Unit)?,
    onDragActiveChange: (Boolean) -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    header: Boolean = true,
    /** Puts a removed song back at its place (the snackbar's Undo); without it a removal offers no Undo. */
    onRestore: ((Song, Int) -> Unit)? = null,
) {
    // The player stands over the dock that carries the window's snackbar, so Up next shows "Removed · Undo" at its own foot.
    Box(modifier) {
        OrbitQueueList(
            queue = queue,
            queueIndex = queueIndex,
            autoplayEnabled = autoplayEnabled,
            controlsLocked = controlsLocked,
            isPlaying = isPlaying,
            progress = progress,
            onJumpTo = onJumpTo,
            onRemove = onRemove,
            onMove = onMove,
            onClear = onClear,
            onToggleAutoplay = onToggleAutoplay,
            modifier = Modifier.fillMaxSize(),
            onDragActiveChange = onDragActiveChange,
            playingFrom = queue.getOrNull(queueIndex + 1)?.playbackSource?.takeIf { it.isNotBlank() },
            onRestore = onRestore,
            header = header,
        )
        OrbitUndoHost(LocalOrbitUndo.current, Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp).testTag("nowplaying:upnext:undo"))
    }
}
