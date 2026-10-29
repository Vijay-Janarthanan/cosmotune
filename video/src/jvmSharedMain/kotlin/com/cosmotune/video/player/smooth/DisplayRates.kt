package com.cosmotune.video.player.smooth

import com.cosmotune.video.settings.SmoothnessSettings
import kotlin.math.abs

/**
 * What rate the phone's screen can be drawn at (pure: the caller reads the display and hands the numbers over). A phone with an
 * adaptive refresh rate runs at 60 until something asks for more; from Android 11 a surface can ask
 * (`Surface.setFrameRate`), so the ceiling for a chosen 90 or 120 is the fastest mode the screen has at its current size.
 */
object DisplayRates {

    /** One mode of the screen: its size in pixels and its refresh rate. */
    class Mode(val width: Int, val height: Int, val hz: Float)

    /**
     * The fastest the screen can show: its [currentHz], or when a surface can ask for a faster mode ([canRequest]), the fastest of
     * [modes] with the size of [current] (a mode of another size would resize the screen).
     */
    fun ceilingHz(currentHz: Float, current: Mode?, modes: List<Mode>, canRequest: Boolean): Float {
        val now = if (currentHz > 1f) currentHz else DEFAULT_HZ
        if (!canRequest || current == null) return now
        val fastest = modes.filter { it.width == current.width && it.height == current.height }.maxOfOrNull { it.hz } ?: return now
        return maxOf(now, fastest)
    }

    /**
     * The refresh rate smoothing is drawn against for [settings]: the screen as it runs now when it follows the display, else the
     * ceiling (a fixed target above the current rate is reached by asking for it).
     */
    fun displayHzFor(settings: SmoothnessSettings, currentHz: Float, ceilingHz: Float): Float =
        if (settings.targetFps <= 0) currentHz else ceilingHz

    /**
     * The rate to ask the output surface for, or null when nothing needs asking: only a chosen target above what the screen runs at
     * now, and never more than the ceiling.
     */
    fun requestHz(settings: SmoothnessSettings, currentHz: Float, ceilingHz: Float): Float? {
        val target = settings.targetFps
        if (target <= 0) return null
        val wanted = minOf(target.toFloat(), ceilingHz)
        return if (wanted > currentHz + HZ_SLACK) wanted else null
    }

    /** The length of one refresh in nanoseconds. */
    fun periodNanos(hz: Float): Long = (1_000_000_000.0 / hz.coerceAtLeast(1f)).toLong()

    /** Whether two rates are the same one (59.94 and 60). */
    fun same(a: Float, b: Float): Boolean = abs(a - b) < HZ_SLACK

    private const val DEFAULT_HZ = 60f
    private const val HZ_SLACK = 1.5f
}
