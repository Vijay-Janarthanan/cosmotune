package com.cosmotune.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import androidx.annotation.RequiresApi
import com.cosmotune.audio.PlatformSpatialState

/** Public system APIs only; no attempt to turn on spatialization or select a head tracker. */
internal class AndroidPlatformSpatial(context: Context, changed: () -> Unit) : AutoCloseable {
    private val delegate = if (Build.VERSION.SDK_INT >= 32) runCatching { Api32(context, changed) }.getOrNull() else null
    fun update(rate: Int?, channels: Int?, encoding: Int?) { delegate?.update(rate, channels, encoding) }
    override fun close() { delegate?.close(); AndroidListeningDevices.spatial.value = PlatformSpatialState() }

    @RequiresApi(32)
    private class Api32(context: Context, private val changed: () -> Unit) : AutoCloseable {
        private val spatial = requireNotNull(context.getSystemService(AudioManager::class.java)).spatializer
        private var rate: Int? = null; private var channels: Int? = null; private var encoding: Int? = null
        private val listener = object : Spatializer.OnSpatializerStateChangedListener {
            override fun onSpatializerEnabledChanged(spatializer: Spatializer, enabled: Boolean) = publish()
            override fun onSpatializerAvailableChanged(spatializer: Spatializer, available: Boolean) = publish()
        }
        private val headListener = if (Build.VERSION.SDK_INT >= 33) HeadTracker(spatial, context, ::publish) else null
        init { runCatching { spatial.addOnSpatializerStateChangedListener(context.mainExecutor, listener) }; publish() }
        fun update(rate: Int?, channels: Int?, encoding: Int?) { this.rate = rate; this.channels = channels; this.encoding = encoding; publish() }
        private fun publish() {
            val state = runCatching {
                val mask = when (channels) { 1 -> AudioFormat.CHANNEL_OUT_MONO; 2 -> AudioFormat.CHANNEL_OUT_STEREO
                    6 -> AudioFormat.CHANNEL_OUT_5POINT1; 8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND; else -> null }
                val eligible = if (mask != null && rate != null) runCatching {
                    spatial.canBeSpatialized(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                        AudioFormat.Builder().setChannelMask(mask).setSampleRate(requireNotNull(rate)).setEncoding(encoding ?: AudioFormat.ENCODING_PCM_16BIT).build())
                }.getOrNull() else null
                PlatformSpatialState(spatial.immersiveAudioLevel != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE,
                    spatial.isAvailable, spatial.isEnabled, eligible, headListener?.available(), reported = true)
            }.getOrDefault(PlatformSpatialState())
            AndroidListeningDevices.spatial.value = state
            changed()
        }
        override fun close() { runCatching { spatial.removeOnSpatializerStateChangedListener(listener) }; headListener?.close() }
    }
    @RequiresApi(33)
    private class HeadTracker(private val spatial: Spatializer, context: Context, changed: () -> Unit) : AutoCloseable {
        private val listener = Spatializer.OnHeadTrackerAvailableListener { _, _ -> changed() }
        init { runCatching { spatial.addOnHeadTrackerAvailableListener(context.mainExecutor, listener) } }
        fun available(): Boolean? = runCatching { spatial.isHeadTrackerAvailable }.getOrNull()
        override fun close() { runCatching { spatial.removeOnHeadTrackerAvailableListener(listener) } }
    }
}
