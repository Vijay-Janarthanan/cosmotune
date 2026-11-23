package com.cosmotune.podio

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.PlaybackService
import com.cosmotune.music.playback.playSongs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts Podio playback with no screen open: the radio alarm and automation apps (Tasker and the like) come in here. The player
 * service is reached as the widget reaches it, and held on to until the playing service keeps itself in the foreground.
 */
object PodioRemotePlay {
    private const val GIVE_UP_MS = 9_000L
    private const val SETTLE_MS = 10_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Plays [song] from [positionMs]; [gentle] brings the volume up over half a minute ([PodioAlarms.GENTLE_STEPS]). Returns once
     * playback was asked for; the ramp and the controller's release go on by themselves.
     */
    suspend fun play(context: Context, song: Song, positionMs: Long = 0, gentle: Boolean = false): Boolean {
        val (future, controller) = connect(context) ?: return false
        try {
            if (gentle) controller.volume = PodioAlarms.GENTLE_STEPS.first()
            controller.playSongs(listOf(song), 0, positionMs)
        } catch (_: Exception) { MediaController.releaseFuture(future); return false }
        scope.launch {
            try {
                if (gentle) for (step in PodioAlarms.GENTLE_STEPS.drop(1)) {
                    delay(PodioAlarms.GENTLE_STEP_MS)
                    // Paused or changed by hand: the ramp stops there.
                    if (!controller.isConnected || controller.currentMediaItem?.mediaId != song.videoId || !controller.playWhenReady) break
                    controller.volume = step
                } else delay(SETTLE_MS)
            } finally {
                if (gentle && controller.isConnected) controller.volume = 1f
                MediaController.releaseFuture(future)
            }
        }
        return true
    }

    /** Pauses whatever plays. */
    suspend fun pause(context: Context): Boolean = withController(context) { it.pause() }

    private suspend fun withController(context: Context, use: suspend (MediaController) -> Unit): Boolean {
        val (future, controller) = connect(context) ?: return false
        return try { use(controller); true } catch (_: Exception) { false } finally { MediaController.releaseFuture(future) }
    }

    private suspend fun connect(context: Context): Pair<com.google.common.util.concurrent.ListenableFuture<MediaController>, MediaController>? {
        val app = context.applicationContext
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        val controller = withTimeoutOrNull(GIVE_UP_MS) { runCatching { future.await() }.getOrNull() }
        if (controller == null) { MediaController.releaseFuture(future); return null }
        return future to controller
    }
}
