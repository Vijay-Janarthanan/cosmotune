package com.cosmotune.video.player.android.smooth

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import com.cosmotune.video.local.VideoAspect
import com.cosmotune.video.player.smooth.DisplayRates
import com.cosmotune.video.settings.SmoothMode

/**
 * The picture while frame smoothing is on: a plain `SurfaceView` that [SmoothGlPipeline] draws into, instead of `ContentFrame`.
 * The decoder is pointed at the pipeline's surface through [player] (the same controller `ContentFrame` would have used, so the
 * commands reach the service's player in order). Leaving composition, losing the window or a failure of the pipeline all take the
 * decoder's surface away again before anything is released; the host ([SmoothingHub.route]) then draws the plain picture.
 */
@Composable
internal fun SmoothPicture(player: Player, hub: SmoothingHub, aspect: VideoAspect, modifier: Modifier) {
    val host = remember(player, hub) { SmoothHost(player, hub) }
    DisposableEffect(host) { onDispose { host.close() } }
    SideEffect { host.setAspect(aspect) }
    val settings by hub.settingsState.collectAsState()
    val readout by hub.readout.collectAsState()
    // A faster mode of the screen is asked for while smoothing is drawing at a rate above the one it runs at.
    LaunchedEffect(host, settings.targetFps, readout.mode) { host.askForRate() }
    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { context -> host.createView(context) }, modifier = Modifier.fillMaxSize())
    }
}

/** The main-thread side of [SmoothPicture]: the view, the pipeline of its window, and the decoder's surface between them. */
internal class SmoothHost(private val player: Player, private val hub: SmoothingHub) : SurfaceHolder.Callback {
    private val main = Handler(Looper.getMainLooper())
    private var view: SurfaceView? = null
    private var pipeline: SmoothGlPipeline? = null
    private var input: Surface? = null
    private var aspect = VideoAspect.FIT
    private var lastRate = -1f
    private var closed = false

    /** What the pipeline calls back (on its thread); a pipeline that was replaced is ignored. */
    private inner class Link : SmoothGlPipeline.Listener {
        var owner: SmoothGlPipeline? = null

        override fun onInputSurface(surface: Surface) {
            main.post { if (!closed && pipeline === owner) adopt(surface) }
        }

        override fun onFailed(reason: String) {
            main.post { if (pipeline === owner) hub.fail(reason) }
        }
    }

    fun createView(context: Context): SurfaceView = SurfaceView(context).also { created ->
        view = created
        created.holder.addCallback(this)
    }

    fun setAspect(value: VideoAspect) {
        aspect = value
        pipeline?.setAspect(value)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (closed) return
        stopPipeline()
        val link = Link()
        val created = SmoothGlPipeline(hub, link)
        link.owner = created
        created.setAspect(aspect)
        pipeline = created
        created.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        pipeline?.setOutput(holder.surface, width, height)
        askForRate()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopPipeline()
    }

    /** The decoder draws into [surface] from now on. */
    private fun adopt(surface: Surface) {
        input = surface
        player.setVideoSurface(surface)
    }

    /** The decoder lets go of the pipeline's surface, then the pipeline is released (it keeps that surface alive a moment longer). */
    private fun stopPipeline() {
        val stopped = pipeline ?: return
        pipeline = null
        input?.let { player.clearVideoSurface(it) }
        input = null
        lastRate = -1f
        stopped.release()
    }

    /** Asks the output surface for the rate smoothing is drawn at when that is above what the screen runs at (Android 11 and later). */
    fun askForRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = view?.holder?.surface?.takeIf { it.isValid } ?: return
        val settings = hub.settings
        val wanted = if (hub.readout.value.mode == SmoothMode.OFF) 0f else DisplayRates.requestHz(settings, hub.currentHz(), hub.ceilingHz()) ?: 0f
        if (wanted == lastRate) return
        lastRate = wanted
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(wanted, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else {
                surface.setFrameRate(wanted, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        } catch (e: Throwable) {
            Log.w(SMOOTH_TAG, "the screen could not be asked for $wanted Hz: $e")
        }
    }

    fun close() {
        closed = true
        view?.holder?.removeCallback(this)
        stopPipeline()
        view = null
    }
}
