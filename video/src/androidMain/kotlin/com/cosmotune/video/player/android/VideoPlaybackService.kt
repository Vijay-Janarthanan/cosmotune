package com.cosmotune.video.player.android

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.cosmotune.video.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

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
            .setCallback(QueueButtonsCallback)
            .setMediaButtonPreferences(queueButtonPreferences())
            .build()
    }

    /**
     * Previous and next in the notification (and on the lock screen): the player holds one video and the queue is the controller's
     * (plan task Y-10), so the two buttons are custom commands in the back and forward slots, which [QueueButtonsCallback] hands
     * to [queueButtons].
     */
    private fun queueButtonPreferences(): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName(getString(R.string.video_notification_previous))
            .setSessionCommand(PREVIOUS_COMMAND)
            .setSlots(CommandButton.SLOT_BACK)
            .build(),
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName(getString(R.string.video_notification_next))
            .setSessionCommand(NEXT_COMMAND)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build(),
    )

    /**
     * Lets the notification's controller send the two queue commands and answers them; a headset's or a car's previous and next
     * keys (which a player of one item would ignore) go the same way. Everything else is Media3's default.
     */
    private object QueueButtonsCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val accepted = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (!session.isMediaNotificationController(controller)) return accepted.build()
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(PREVIOUS_COMMAND).add(NEXT_COMMAND).build()
            return accepted.setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_PREVIOUS -> queueButtons?.previous()
                ACTION_NEXT -> queueButtons?.next()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
            val buttons = queueButtons ?: return false
            val forward = when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> true
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> false
                else -> return false
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (forward) buttons.next() else buttons.previous()
            }
            return true
        }
    }

    /** Where the queue's previous and next go: the graph points it at its controller (plan task Y-16). Called on the main thread. */
    interface QueueButtons {
        fun previous()
        fun next()
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
        /** The notification channel of Video's playback ("Video playback"); Music's is `cosmotune_playback`. */
        const val CHANNEL_ID = "video_playback"

        /** The session's id; Music's session is `CosmotunePlayback`. */
        const val SESSION_ID = "CosmotuneVideo"

        /** The notification's id; Music's provider uses Media3's default 1001, Music's downloads 0x8175, its uploads 0x8176. */
        const val NOTIFICATION_ID = 0x7669

        /** The request code of the session activity, so it is not Music's PendingIntent (request code 0 on the same launch intent). */
        private const val SESSION_ACTIVITY_REQUEST = 0x7669

        /** The custom commands of the notification's previous and next buttons. */
        const val ACTION_PREVIOUS = "com.cosmotune.video.PREVIOUS"
        const val ACTION_NEXT = "com.cosmotune.video.NEXT"
        private val PREVIOUS_COMMAND = SessionCommand(ACTION_PREVIOUS, Bundle.EMPTY)
        private val NEXT_COMMAND = SessionCommand(ACTION_NEXT, Bundle.EMPTY)

        /** The queue the buttons and the headset's previous and next keys drive; null (the buttons do nothing) until the graph is built. */
        @Volatile
        var queueButtons: QueueButtons? = null
    }
}
