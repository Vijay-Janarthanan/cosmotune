package com.cosmotune.video.player.android

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.cosmotune.video.R

/**
 * The service that keeps Video's player alive in the background and lets system controllers and headsets reach it (spec 6.2,
 * "Service"): a foreground service of type `mediaPlayback`, declared in this module's manifest with the Media3 session action.
 *
 * It owns Video's one ExoPlayer ([VideoPlayerHolder.build]) and a [MediaSession] with its own notification (the thumbnail, the
 * title, the channel, play and pause; channel [CHANNEL_ID], small icon `ic_video_notification`). The engine in the same process
 * connects a `MediaController` to it ([AndroidVideoEngine]); the session serves the notification, the lock screen, headsets and
 * picture-in-picture's buttons.
 *
 * It lives beside Music's `PlaybackService` and never touches it. To keep the two apart in one process: its own session id
 * ([SESSION_ID]; Media3 refuses two sessions with one id), its own notification id ([NOTIFICATION_ID]; both providers would
 * otherwise post 1001 and replace each other's notification), its own channel and its own session-activity request code.
 * Media buttons: while Video plays, its session is the one Android sends them to; when no session is alive, the app's
 * `MediaButtonReceiver` looks for a `MediaLibraryService` first, which only Music's service is, so a press then resumes Music
 * as before (Video offers no playback resumption).
 */
@OptIn(UnstableApi::class)
class VideoPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * When the engine unloads (stopped, nothing left in the player), the service stops itself, so nothing lingers once the engine
     * lets go of it; while the engine is still bound, Android keeps it until the unbind.
     */
    private val unloadListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (player.mediaItemCount == 0 && player.playbackState == Player.STATE_IDLE) stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = VideoPlayerHolder.build(this)
        player.addListener(unloadListener)
        // No notification for an idle player (stopped, failed or unloaded): the in-app error says what happened, and a
        // notification with nothing to play would only linger.
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER)
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.video_notification_channel)
                .setNotificationId(NOTIFICATION_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_video_notification) },
        )
        mediaSession = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .apply { sessionActivity()?.let { setSessionActivity(it) } }
            .setBitmapLoader(VideoPlayerHolder.bitmapLoader(this))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** Swiping the app away stops the service unless a video is playing (then it keeps playing, with its notification). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!isPlaybackOngoing || mediaSession?.player?.playWhenReady != true) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        mediaSession?.let { session ->
            session.player.removeListener(unloadListener)
            VideoPlayerHolder.release(session.player as ExoPlayer)
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /** The app's launch intent, brought to the front rather than stacked (`FLAG_ACTIVITY_SINGLE_TOP`); null if there is none. */
    private fun sessionActivity(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            SESSION_ACTIVITY_REQUEST,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        /** The notification channel of Video's playback ("Video playback"); Music's is `bitchord_playback`. */
        const val CHANNEL_ID = "video_playback"

        /** The session's id; Music's session is `BitChordPlayback`. */
        const val SESSION_ID = "CosmotuneVideo"

        /** The notification's id; Music's provider uses Media3's default 1001, Music's downloads 0x8175, its uploads 0x8176. */
        const val NOTIFICATION_ID = 0x7669

        /** The request code of the session activity, so it is not Music's PendingIntent (request code 0 on the same launch intent). */
        private const val SESSION_ACTIVITY_REQUEST = 0x7669
    }
}
