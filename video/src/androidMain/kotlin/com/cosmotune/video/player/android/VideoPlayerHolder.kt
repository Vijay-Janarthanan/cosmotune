package com.cosmotune.video.player.android

import android.content.Context
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.CacheBitmapLoader
import com.cosmotune.video.player.android.smooth.VideoRenderersFactory

/**
 * Video's one ExoPlayer (spec 6.2, "Service"): built by [VideoPlaybackService] when it starts, and held here so the engine in the
 * same process can read what a `MediaController` does not carry: the bandwidth estimate, the dropped frames, the decoder's name
 * and a playback error with its whole cause chain (a 403 from googlevideo).
 *
 * Main thread only, like the player itself. Never Music's player: Music's `PlaybackService` is not touched.
 */
@OptIn(UnstableApi::class)
object VideoPlayerHolder {

    /** The player of the running service, or null while the service is not running. */
    @Volatile
    var player: ExoPlayer? = null
        private set

    /** The player's bandwidth meter (the stats card's bandwidth and the selector's measured bandwidth, spec 6.4, 6.5). */
    @Volatile
    var bandwidthMeter: DefaultBandwidthMeter? = null
        private set

    /**
     * Builds the service's player: the media sources of [VideoMediaSourceFactory]; movie audio with audio focus handled
     * (`handleAudioFocus = true`, as Music's own player: starting one pauses the other, spec 6.2 "Focus"); paused when the
     * headphones come out; the CPU and Wi-Fi kept awake while it streams; decoder fallback on, so a decoder that fails to start
     * gives way to the next one. The renderers are the defaults, with a pass-through audio processor that delays the sound by one
     * frame while frame smoothing shows the picture one frame late (it does nothing otherwise).
     */
    @MainThread
    fun build(context: Context): ExoPlayer {
        val meter = DefaultBandwidthMeter.Builder(context).build()
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val built = ExoPlayer.Builder(context, VideoRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(VideoMediaSourceFactory(context))
            .setBandwidthMeter(meter)
            .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = built
        bandwidthMeter = meter
        return built
    }

    /** Forgets [released] (the service's player, about to be released) and releases it. */
    @MainThread
    fun release(released: ExoPlayer) {
        if (player === released) {
            player = null
            bandwidthMeter = null
        }
        released.release()
    }

    /**
     * Loads the notification's thumbnail through Video's own client (no cookies), not Media3's default HTTP stack, and keeps the
     * last one so a notification update does not fetch it again.
     */
    fun bitmapLoader(context: Context): BitmapLoader = CacheBitmapLoader(
        DataSourceBitmapLoader.Builder(context)
            .setDataSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(GooglevideoDataSource.client)))
            .build(),
    )
}
