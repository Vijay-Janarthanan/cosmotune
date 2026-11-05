/*
 * Source: FORK.md companion source record TV-0008
 * Adaptations recorded in tv/UPSTREAM_IMPORT.json; authors credited in NOTICE.md.
 */
package com.cosmotune.music.ui.tv.audio

import android.media.audiofx.Virtualizer
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.music.data.settings.TvSettings
import com.cosmotune.tv.TvAudioEffectBinding
import com.cosmotune.music.data.DebugLog as Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

/**
 * Spatial Audio & 3D Surround Virtualizer Engine for TV speakers, soundbars, and headphones.
 * Expands stereo audio into a wide, immersive spatial soundstage.
 */
object TvSpatialAudioEngine {
    private const val TAG = "TvSpatialAudioEngine"
    private var virtualizer: Virtualizer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    fun install() {
        if (observer?.isActive == true) return
        val binding = TvAudioEffectBinding(::attach, ::release)
        observer = scope.launch {
            combine(AppSettings.audioSessionId, TvSettings.spatialAudioEnabled) { session, enabled -> session to enabled }
                .collect { (session, enabled) -> binding.update(session, enabled) }
        }
    }

    private fun attach(audioSessionId: Int) {
        if (audioSessionId == 0) return
        try {
            release()
            virtualizer = Virtualizer(0, audioSessionId).apply {
                if (strengthSupported) {
                    setStrength(1000.toShort()) // Full 3D spatial immersion
                }
                enabled = true
            }
            Log.d(TAG, "Attached optional Spatial Audio Virtualizer to session $audioSessionId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach Spatial Audio Virtualizer: ${e.message}")
        }
    }

    private fun release() {
        try {
            virtualizer?.release()
            virtualizer = null
        } catch (e: Exception) {
            // Ignore
        }
    }
}
