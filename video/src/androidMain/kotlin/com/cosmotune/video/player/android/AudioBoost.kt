package com.cosmotune.video.player.android

import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.annotation.MainThread

/**
 * The phone's volume and voice boosts on the service player's audio session: a [LoudnessEnhancer] for the gain over 100 % (and a
 * little more with the voice boost, to even out soft speech) and an [Equalizer] that lifts the speech bands for the voice boost.
 * Bound again when the session changes (a new player). An effect the device refuses is left out; playback never depends on it.
 */
internal class AudioBoost {
    private var session = 0
    private var loudness: LoudnessEnhancer? = null
    private var equalizer: Equalizer? = null
    private var boost = 100
    private var voice = false

    @MainThread
    fun set(boost: Int, voice: Boolean) {
        this.boost = boost.coerceIn(100, 200)
        this.voice = voice
        apply()
    }

    /** Binds to the player's current session if it changed, then applies what was asked. */
    @MainThread
    fun apply() {
        val id = VideoPlayerHolder.player?.audioSessionId ?: 0
        val wanted = boost > 100 || voice
        if (!wanted) {
            release()
            return
        }
        if (id == 0) return
        if (id != session) {
            release()
            session = id
        }
        // the gain in millibels: 100 % more volume is about +6 dB; the voice boost adds 3 dB of evening out
        val gainMb = (2000 * kotlin.math.log10(boost / 100.0)).toInt() + if (voice) 300 else 0
        val enhancer = loudness ?: runCatching { LoudnessEnhancer(id) }.getOrNull()?.also { loudness = it }
        runCatching {
            enhancer?.setTargetGain(gainMb)
            enhancer?.enabled = gainMb > 0
        }
        if (voice) {
            val eq = equalizer ?: runCatching { Equalizer(0, id) }.getOrNull()?.also { equalizer = it }
            runCatching {
                eq ?: return@runCatching
                val (low, high) = eq.bandLevelRange.let { it[0] to it[1] }
                for (band in 0 until eq.numberOfBands) {
                    val centreHz = eq.getCenterFreq(band.toShort()) / 1000
                    val level = when {
                        centreHz < 150 -> (-400).coerceAtLeast(low.toInt())
                        centreHz in 800..4500 -> 500.coerceAtMost(high.toInt())
                        else -> 0
                    }
                    eq.setBandLevel(band.toShort(), level.toShort())
                }
                eq.enabled = true
            }
        } else {
            runCatching { equalizer?.release() }
            equalizer = null
        }
    }

    @MainThread
    fun release() {
        runCatching { loudness?.release() }
        runCatching { equalizer?.release() }
        loudness = null
        equalizer = null
        session = 0
    }
}
