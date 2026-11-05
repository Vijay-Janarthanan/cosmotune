package com.cosmotune.video.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.cosmotune.design.AppMode
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoPlayerEngine
import com.cosmotune.video.settings.VideoSettingsRepository
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.ui.VideoHostLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * What both graph factories wire besides the slots (plan task Y-16): the audio of the two modes, the region of the source. The
 * platform parts (the store flush, the app's version, the notification's buttons) are in each factory.
 */

/**
 * Audio is exclusive across the two modes (the controller's ruling after Y-3). The graph lives as long as the process, so the
 * video would play on with no Video screen when the mode flips to Music: [watch] follows the stored mode and stops a loaded video
 * as soon as the mode is not Video. [musicStarted] is the host's word that Music began to play while Video is drawn (the desktop's
 * system media controls can do that; on the phone audio focus pauses the video by itself): a playing video stops. Starting a video
 * pauses Music through the host's link ([PauseMusicWhileVideoPlays]).
 */
internal class ModeAudio(private val playback: VideoPlayback, private val scope: CoroutineScope) {
    private var watched: StateFlow<AppMode>? = null
    private var job: Job? = null

    /** Follows [modes] (the look settings' stored mode); a second call with the same flow does nothing. */
    @Synchronized
    fun watch(modes: StateFlow<AppMode>?) {
        if (modes == null || modes === watched) return
        job?.cancel()
        watched = modes
        job = scope.launch { modes.collect { if (it != AppMode.VIDEO) stopLoaded() } }
    }

    /** The mode is not Video: a loaded video (playing or paused) stops, its place kept in the history. */
    fun stopLoaded() {
        if (playback.state.value.item != null) playback.stop()
    }

    /** Music began to play while Video is drawn: a video that plays stops. */
    fun musicStarted() {
        if (playback.state.value.playing) playback.stop()
    }
}

/**
 * While Video is drawn, Music pauses whenever a video starts to play (spec 9): through [links] (the phone's is empty, since audio
 * focus does it there; the desktop's pauses Music's player). Runs on the composition's own thread.
 */
@Composable
internal fun PauseMusicWhileVideoPlays(playback: VideoPlayback, links: VideoHostLinks) {
    val current by rememberUpdatedState(links)
    LaunchedEffect(playback) {
        playback.state.map { it.playing }.distinctUntilChanged().collect { playing -> if (playing) current.pauseMusic() }
    }
}

/**
 * Keeps [source]'s region and language those of Video settings (spec 5.11: "Region for Home" and "Content language"; null is the
 * device's [deviceCountry] and [deviceLanguage]), from now on and at every change. The source's region is process-wide (Music's
 * fallback reads it too; it only reads streams, which the region does not change).
 */
internal fun CoroutineScope.followRegion(settings: VideoSettingsRepository, source: VideoSource, deviceCountry: String, deviceLanguage: String): Job =
    launch {
        settings.settings
            .map { regionOf(it.region, deviceCountry) to regionOf(it.contentLanguage, deviceLanguage) }
            .distinctUntilChanged()
            .collect { (country, language) -> source.setRegion(country, language) }
    }

/**
 * Hands the engine the frame-smoothing settings now and at every change (the desktop smooths the picture; the phone's engine
 * reads the display-rate switch). Both are the engine's own business: the settings are only passed on.
 */
internal fun CoroutineScope.followSmoothness(settings: VideoSettingsRepository, engine: VideoPlayerEngine): Job =
    launch {
        settings.settings
            .map { it.smoothness }
            .distinctUntilChanged()
            .collect { engine.setSmoothness(it) }
    }

/** [chosen] when it is set, else [device]. */
private fun regionOf(chosen: String?, device: String): String = chosen?.takeIf { it.isNotBlank() } ?: device
