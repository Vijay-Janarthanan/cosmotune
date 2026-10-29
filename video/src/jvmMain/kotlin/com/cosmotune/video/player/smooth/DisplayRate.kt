package com.cosmotune.video.player.smooth

import androidx.compose.runtime.staticCompositionLocalOf
import java.awt.GraphicsEnvironment
import java.awt.Window

/**
 * The AWT window the video is drawn in (the main window, or the picture-in-picture window), so frame smoothing can read the refresh
 * rate of the monitor that window is on instead of the primary monitor's. Null where nobody provides it: the primary monitor then.
 */
val LocalVideoHostWindow = staticCompositionLocalOf<Window?> { null }

/** The refresh rate of the screen, as Java reports it and as smoothing should take it. */
internal object DisplayRate {
    const val DEFAULT_HZ = 60f

    /** The common refresh rates; Windows (and Java) report a 59.94 Hz mode as 59, a 143.98 Hz one as 143, and so on. */
    private val NOMINAL = intArrayOf(24, 25, 30, 48, 50, 60, 72, 75, 90, 100, 120, 144, 165, 180, 200, 240, 360, 480)

    /** Java's answer for "unknown" (`DisplayMode.REFRESH_RATE_UNKNOWN`). */
    private const val UNKNOWN = 0
    private const val MAX_PLAUSIBLE_HZ = 1000

    /**
     * What a reported whole-number rate means: null for unknown or absurd; one below a common rate is that rate (59 is 59.94 Hz,
     * which the screen presents as 60); anything else is taken as it is.
     */
    fun fromReported(reported: Int): Float? {
        if (reported <= UNKNOWN || reported > MAX_PLAUSIBLE_HZ) return null
        for (nominal in NOMINAL) if (reported == nominal - 1) return nominal.toFloat()
        return reported.toFloat()
    }

    /** The rate to use: the reported one when it is known, else the last known one, else [DEFAULT_HZ]. */
    fun choose(reported: Int, lastKnown: Float?): Float = fromReported(reported) ?: lastKnown ?: DEFAULT_HZ

    /** The primary monitor's refresh rate as reported (0 when the system does not say, or there is no screen). */
    fun primaryReported(): Int = try {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.refreshRate
    } catch (_: Throwable) {
        UNKNOWN // headless, or no screen to ask
    }

    /** The refresh rate of the monitor [window] is on as reported, else the primary monitor's. */
    fun reportedFor(window: Window?): Int {
        val own = try {
            window?.graphicsConfiguration?.device?.displayMode?.refreshRate ?: UNKNOWN
        } catch (_: Throwable) {
            UNKNOWN
        }
        return if (own > UNKNOWN) own else primaryReported()
    }

    /** The primary monitor's refresh rate (60 when the system does not say). */
    fun hz(): Float = choose(primaryReported(), null)
}

/**
 * Where frame smoothing gets the display's rate: the surfaces that draw the video say which host (window) they are in
 * ([attach], on composition, [detach] when they leave), and [hz] reads the monitor of the one that attached last, which is the one on
 * screen (the picture-in-picture window takes over from the page it left). A rate the system does not report keeps the last known one.
 * [reportedRate] answers for a host with a whole-number rate (0 for unknown); null host means no window is known.
 */
internal class DisplayRateSource(private val reportedRate: (host: Any?) -> Int = { DisplayRate.reportedFor(it as? Window) }) {
    private val hosts = LinkedHashMap<Any, Any>()
    @Volatile private var lastKnown: Float? = null

    /** The surface [key] is drawn in [host] (a null host removes what [key] said before). */
    fun attach(key: Any, host: Any?) {
        synchronized(hosts) {
            hosts.remove(key)
            if (host != null) hosts[key] = host
        }
    }

    fun detach(key: Any) {
        synchronized(hosts) { hosts.remove(key) }
    }

    /** The rate of the display the video is on now. Cheap enough for every few seconds; any thread. */
    fun hz(): Float {
        val host = synchronized(hosts) { hosts.values.lastOrNull() }
        val rate = DisplayRate.choose(reportedRate(host), lastKnown)
        lastKnown = rate
        return rate
    }
}
