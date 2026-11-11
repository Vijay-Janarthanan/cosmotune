package com.cosmotune.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.orbit.WatchPicture
import com.cosmotune.ui.pip.PipBinding
import com.cosmotune.ui.pip.PipControls
import com.cosmotune.ui.pip.PipHandle
import com.cosmotune.ui.pip.PipOwner
import com.cosmotune.ui.pip.PipWish
import com.cosmotune.watch.WatchPlayer
import com.cosmotune.watch.WatchPlayerState
import com.cosmotune.watch.WatchPlayerStatus
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.WatchKey

/*
 * Watch's picture-in-picture (films, series and live TV), on the window's one picture-in-picture (`rememberPip` as
 * [PipOwner.WATCH]): ready while the film plays here (not on a cast receiver), in the video's own shape, with play and pause and,
 * for an episode the next one, for a channel the next channel; closing it pauses the film. The page then shows the picture alone
 * (the phone, the TV) or, on the desktop whose small window holds the picture, a stand-in with Return ([WatchPipStandIn]).
 */

/**
 * Binds [player] to picture-in-picture for the player page, through its [handle] (`rememberPip(PipOwner.WATCH)`). [local]: the film plays on this device (a cast receiver's has no
 * picture here). [nextChannel]: the channel after this one, for a channel. [surface] draws the picture in the desktop's window.
 */
@Composable
fun rememberWatchPip(
    handle: PipHandle,
    player: WatchPlayer,
    state: WatchPlayerState,
    playing: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    local: Boolean,
    nextChannel: (() -> Unit)?,
    surface: @Composable (WatchSurfaceScale, Modifier) -> Unit,
): PipBinding {
    val binding = remember(handle) { PipBinding(handle) }
    val wish = watchPipWish(player, state, playing, videoWidth, videoHeight, local, nextChannel, surface)
    SideEffect { binding.update(wish) }
    return binding
}

/** What Watch wishes of picture-in-picture now (see the file's note); pure apart from the lambdas it hands over. */
internal fun watchPipWish(
    player: WatchPlayer,
    state: WatchPlayerState,
    playing: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    local: Boolean,
    nextChannel: (() -> Unit)?,
    surface: @Composable (WatchSurfaceScale, Modifier) -> Unit,
): PipWish {
    val next: (() -> Unit)? = when {
        state.isLive -> nextChannel
        state.nextEpisode != null -> player::playNextEpisode
        else -> null
    }
    val seekBy: ((Long) -> Unit)? = if (state.isLive) null else { delta ->
        val duration = player.engine.snapshot.value.durationMs.takeIf { it > 0 } ?: state.durationMs
        val to = (player.engine.positionMs() + delta).coerceAtLeast(0L)
        player.engine.seekTo(if (duration > 0) to.coerceAtMost(duration) else to)
    }
    val title = listOfNotNull(state.summary?.name, state.subtitleLine).joinToString(" · ")
    return PipWish(
        ready = watchPipReady(local, playing, state.status),
        aspectWidth = if (videoWidth > 0 && videoHeight > 0) videoWidth else 16,
        aspectHeight = if (videoWidth > 0 && videoHeight > 0) videoHeight else 9,
        playing = playing,
        controls = PipControls(
            playPause = { if (player.engine.snapshot.value.playing) player.engine.pause() else player.engine.play() },
            next = next,
            seekBy = seekBy,
            positionMs = { player.engine.positionMs() },
            durationMs = { player.engine.snapshot.value.durationMs.takeIf { it > 0 } ?: player.state.value.durationMs },
        ),
        title = title,
        picture = { m -> surface(WatchSurfaceScale.FIT, m) },
        // Closing picture-in-picture pauses the film; expanding it leaves it playing on the page it came from.
        onClosed = { player.engine.pause() },
    )
}

/** Whether leaving the app takes Watch into picture-in-picture by itself: the film plays here and has not failed. */
internal fun watchPipReady(local: Boolean, playing: Boolean, status: WatchPlayerStatus): Boolean =
    local && playing && status != WatchPlayerStatus.ERROR && status != WatchPlayerStatus.NOT_FOUND && status != WatchPlayerStatus.ENDED

/**
 * The channel after [current] in [channels] (the channel list of its own playlist), round to the first after the last; null when
 * there is no other channel.
 */
fun channelAfter(channels: List<Channel>, current: WatchKey): Channel? {
    val others = channels.filter { it.key != current }
    if (others.isEmpty()) return null
    val at = channels.indexOfFirst { it.key == current }
    if (at < 0) return others.first()
    return (1 until channels.size).asSequence().map { channels[(at + it) % channels.size] }.first { it.key != current }
}

/**
 * The desktop's player page while the small window holds the picture: the title's still in the picture's place, "Playing in
 * picture in picture", and Return, which takes the picture back. Nothing is drawn over a video: there is none here.
 */
@Composable
internal fun WatchPipStandIn(still: String?, key: String, radius: Dp, fonts: OrbitFonts, onReturn: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(Color.Black)) {
        if (still != null) WatchPicture(still, key, Modifier.fillMaxSize(), radius = 0.dp)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)))
        Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OrbitLabel("Picture in picture", fonts, Color.White.copy(alpha = 0.78f))
            Text("Playing in the small window", style = fonts.body, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            WatchCardChip("Return", Color.White, fonts, onClick = onReturn)
        }
    }
}
