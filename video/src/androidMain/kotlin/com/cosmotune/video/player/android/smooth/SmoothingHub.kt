package com.cosmotune.video.player.android.smooth

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.cosmotune.video.player.smooth.DisplayRates
import com.cosmotune.video.player.smooth.PresetResolver
import com.cosmotune.video.player.smooth.SmoothGovernor
import com.cosmotune.video.player.smooth.SmoothPolicy
import com.cosmotune.video.player.smooth.SmoothSchedule
import com.cosmotune.video.settings.SmoothMode
import com.cosmotune.video.settings.SmoothnessReadout
import com.cosmotune.video.settings.SmoothnessSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the engine and the GL pipeline share about frame smoothing: the settings, the governor that steps Smooth down, the
 * readout, and the one decision that matters to the screen: [route], whether the picture goes through the GL pipeline at all.
 * While [route] is false the picture is drawn by the plain `ContentFrame` path exactly as without smoothing (nothing of this runs).
 *
 * The route is on when smoothing is asked for, nothing has failed, the video is not one smoothing leaves alone and the decoder's
 * own frame rate (the format's, known before the first frame) is one smoothing would change. A video whose rate is not declared
 * is routed to be measured; when the measure says smoothing has nothing to do, the pipeline hands the picture back
 * ([declineForVideo]).
 *
 * Any thread may call anything; the state is volatile or behind the lock.
 */
internal class SmoothingHub(context: Context) {
    private val appContext = context.applicationContext

    @Volatile
    var settings = SmoothnessSettings()
        private set

    private val settingsFlow = MutableStateFlow(settings)

    /** The settings now, for the host that asks the screen for a faster mode. */
    val settingsState: StateFlow<SmoothnessSettings> = settingsFlow.asStateFlow()

    val governor = SmoothGovernor()
    val resolver = PresetResolver()

    private val readoutFlow = MutableStateFlow(SmoothnessReadout.None)

    /** What is truly happening: the pipeline writes it while it draws, this class when the picture is plain. */
    val readout: StateFlow<SmoothnessReadout> = readoutFlow.asStateFlow()

    private val routeFlow = MutableStateFlow(false)

    /** True while the picture goes through the GL pipeline. */
    val route: StateFlow<Boolean> = routeFlow.asStateFlow()

    /** Changes with every new video, so the pipeline starts its measures again. */
    val generation = AtomicInteger()

    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0
    @Volatile var pixelRatio = 1f

    /** The playback speed, which scales the audio delay (the clock's interval holds that many times the video's time). */
    @Volatile var playbackSpeed = 1f

    private var formatSeen = false
    private var hintFps: Float? = null
    private var hdr = false
    private var audioOnly = false
    private var declinedNotice: String? = null
    private var failure: String? = null
    private var appliedDelay = 0.0

    // ---- the display ----

    @Volatile private var displayHz = 60f
    @Volatile private var ceilingHz = 60f
    @Volatile private var displayAt = Long.MIN_VALUE

    /** The screen's refresh rate as it runs now. */
    fun currentHz(): Float {
        refreshDisplay()
        return displayHz
    }

    /** The fastest rate the screen can be asked for (see [DisplayRates.ceilingHz]). */
    fun ceilingHz(): Float {
        refreshDisplay()
        return ceilingHz
    }

    private fun refreshDisplay() {
        val now = SystemClock.elapsedRealtime()
        if (displayAt != Long.MIN_VALUE && now - displayAt < DISPLAY_EVERY_MS) return
        displayAt = now
        try {
            val display = appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val current = display.refreshRate
            val canRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val mode = display.mode
            val modes = display.supportedModes.map { DisplayRates.Mode(it.physicalWidth, it.physicalHeight, it.refreshRate) }
            displayHz = if (current > 1f) current else 60f
            ceilingHz = DisplayRates.ceilingHz(current, DisplayRates.Mode(mode.physicalWidth, mode.physicalHeight, mode.refreshRate), modes, canRequest)
        } catch (e: Throwable) {
            Log.w(SMOOTH_TAG, "the display's rate is not known: $e")
        }
    }

    // ---- what the engine tells ----

    /** New settings (any thread). A different mode, target or preset gives a stepped-down mode its full ceiling back. */
    @Synchronized
    fun configure(next: SmoothnessSettings) {
        val old = settings
        settings = next
        settingsFlow.value = next
        if (old.mode != next.mode || old.targetFps != next.targetFps || old.preset != next.preset) {
            governor.reset()
            declinedNotice = null
        }
        if (old.mode != next.mode) failure = null
        recompute()
    }

    /** A new video (or none): every measure starts again and the format is awaited. */
    @Synchronized
    fun onLoad() {
        generation.incrementAndGet()
        governor.reset()
        resolver.reset()
        formatSeen = false
        hintFps = null
        hdr = false
        declinedNotice = null
        setAudioDelay(0.0)
        recompute()
    }

    /** The video decoder's input format: its declared frame rate (null when it declares none) and whether it is HDR. */
    @Synchronized
    fun onFormat(fps: Float?, hdr: Boolean) {
        formatSeen = true
        hintFps = fps?.takeIf { it > 1f }
        this.hdr = hdr
        recompute()
    }

    @Synchronized
    fun onAudioOnly(audioOnly: Boolean) {
        this.audioOnly = audioOnly
        recompute()
    }

    fun onVideoSize(width: Int, height: Int, pixelRatio: Float) {
        videoWidth = width
        videoHeight = height
        this.pixelRatio = if (pixelRatio > 0f) pixelRatio else 1f
    }

    // ---- what the pipeline tells ----

    /** The measure says smoothing has nothing to do for this video: hand the picture back to the plain path. */
    @Synchronized
    fun declineForVideo(notice: String?) {
        declinedNotice = notice ?: ""
        recompute()
    }

    /** The governor lowered its ceiling (or the pipeline asks to look again). */
    @Synchronized
    fun recompute() {
        val s = settings
        val unwanted = s.mode == SmoothMode.OFF
        val reason = failure
        val hint = hintFps
        val output = SmoothPolicy.outputFps(s, DisplayRates.displayHzFor(s, currentHz(), ceilingHz()))
        val effective = SmoothPolicy.effectiveMode(s, hint, output, governor.ceiling)
        val routed = !unwanted && reason == null && !hdr && !audioOnly && formatSeen && declinedNotice == null &&
            governor.ceiling != SmoothMode.OFF && (hint == null || effective != SmoothMode.OFF)
        if (!routed) setAudioDelay(0.0)
        routeFlow.value = routed
        if (routed || unwanted) {
            if (unwanted) readoutFlow.value = SmoothnessReadout.None
            return
        }
        val notice = when {
            reason != null -> reason
            hdr -> "HDR videos are shown as they are."
            audioOnly || !formatSeen -> null
            declinedNotice != null -> declinedNotice?.takeIf { it.isNotEmpty() }
            governor.notice != null -> governor.notice
            else -> SmoothPolicy.whyNot(s, hint, output, effective)
        }
        readoutFlow.value = SmoothnessReadout(mode = SmoothMode.OFF, sourceFps = hint?.let { (it * 10).roundToInt() / 10f }, notice = notice)
    }

    /** An error the pipeline cannot work through: smoothing stops for this setting, the plain picture carries on. */
    @Synchronized
    fun fail(reason: String) {
        Log.w(SMOOTH_TAG, "frame smoothing stopped: $reason")
        failure = "Frame smoothing stopped after an error, so frames are shown as they come."
        governor.stop(failure!!)
        recompute()
    }

    fun publish(readout: SmoothnessReadout) {
        readoutFlow.value = readout
    }

    /** The sound is held back by [intervalNanos] of the clock while the picture is smoothed; 0 (or null) clears it. */
    @Synchronized
    fun holdAudio(intervalNanos: Long?) {
        setAudioDelay(if (intervalNanos == null || intervalNanos <= 0) 0.0 else SmoothSchedule.audioDelaySeconds(intervalNanos, playbackSpeed))
    }

    private fun setAudioDelay(seconds: Double) {
        // A new value only when it matters (the gap between frames wobbles by a millisecond or two).
        if (seconds == 0.0) {
            if (appliedDelay == 0.0) return
        } else if (abs(seconds - appliedDelay) < DELAY_STEP_S) {
            return
        }
        appliedDelay = seconds
        SmoothAudioDelay.seconds = seconds
    }

    companion object {
        private const val DISPLAY_EVERY_MS = 2_000L
        private const val DELAY_STEP_S = 0.004

        /** Whether this phone can run the pipeline at all: it has OpenGL ES 2.0 (Blend) or better. */
        fun supported(context: Context): Boolean = try {
            val info = context.applicationContext.getSystemService(ActivityManager::class.java)?.deviceConfigurationInfo
            info != null && info.reqGlEsVersion >= 0x20000
        } catch (_: Throwable) {
            false
        }
    }
}
