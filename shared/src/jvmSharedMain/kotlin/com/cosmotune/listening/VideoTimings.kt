package com.cosmotune.listening

import com.opencanvas.core.sync.SyncMap
import java.util.Locale
import kotlin.math.abs

/**
 * The listener's own timing for a song's music video (the idea from Lokal, see NOTICE.md): when the picture runs ahead
 * of or behind the sound, each tap moves it by [STEP_MS], kept per video so the next play starts right. Stored as one
 * "videoId<TAB>ms" line each, through [ListOrders]'s text form.
 */
object VideoTimings {
    const val STEP_MS = 100L
    const val LIMIT_MS = 5_000L

    fun encode(timings: Map<String, Long>): String = ListOrders.encode(timings.mapValues { it.value.toString() })

    fun decode(text: String): Map<String, Long> =
        ListOrders.decode(text).mapNotNull { (id, ms) -> ms.toLongOrNull()?.takeIf { it != 0L && abs(it) <= LIMIT_MS }?.let { id to it } }.toMap()

    /** [timings] with [videoId] moved by [stepMs], kept within ±[LIMIT_MS]; back at zero it is forgotten. */
    fun nudge(timings: Map<String, Long>, videoId: String, stepMs: Long): Map<String, Long> =
        set(timings, videoId, (timings[videoId] ?: 0L) + stepMs)

    fun set(timings: Map<String, Long>, videoId: String, ms: Long): Map<String, Long> {
        val value = ms.coerceIn(-LIMIT_MS, LIMIT_MS)
        return ListOrders.with(timings.mapValues { it.value.toString() }, videoId, value.takeIf { it != 0L }?.toString())
            .mapValues { it.value.toLong() }
    }

    /**
     * The map with the listener's timing applied. A positive timing shows a later picture at the same moment of the
     * song, which brings forward a picture that was running behind the sound.
     */
    fun apply(map: SyncMap, ms: Long): SyncMap =
        if (ms == 0L || map.isEmpty) map else SyncMap(map.segments.map { it.copy(offsetMs = it.offsetMs + ms) })

    /** "As matched", or "Picture 0.3 s earlier" / "Picture 0.3 s later". */
    fun label(ms: Long): String = when {
        ms == 0L -> "As matched"
        ms > 0 -> "Picture ${seconds(ms)} earlier"
        else -> "Picture ${seconds(-ms)} later"
    }

    private fun seconds(ms: Long) = "%.1f s".format(Locale.ROOT, ms / 1_000.0)
}
