package com.cosmotune.video.player

import kotlin.concurrent.Volatile
import kotlin.jvm.Synchronized
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How long a video takes from the click to its first frame, one line per stage, in Video's log. A click starts a video's timeline
 * ([click]); every stage after it ([mark]) says how long after the click it came, and what it decided: whether the signed path or
 * the anonymous one answered, each signed client's attempt and how long it took, the PoToken, the streams chosen (resolution and
 * codec), mpv's `loadfile` or Media3's `prepare`, the first frame and the start of playback.
 *
 * A stage of a video nobody clicked yet (a stream resolved ahead of time) is logged with no time since a click, and says so.
 * The lines go to [sink] (each platform's Video log); [listener] gets the same stages as data, which is what the opt-in startup
 * harness reads. Both are null until set, and then the trace costs one map lookup per stage.
 */
object StartupTrace {

    /** One stage of a video's start: [sinceClickMs] is null when nothing clicked [videoId] yet (work done ahead of time). */
    class Stage(val videoId: String, val name: String, val sinceClickMs: Long?, val detail: String)

    /** Where the lines go: the platform's Video log. */
    @Volatile var sink: ((videoId: String, line: String) -> Unit)? = null

    /** Every stage as data, for the startup harness; null in the app. */
    @Volatile var listener: ((Stage) -> Unit)? = null

    private val clicks = LinkedHashMap<String, TimeMark>()

    /** The click on [videoId]: its timeline starts again from here. */
    fun click(videoId: String, detail: String = "") {
        locked {
            clicks.remove(videoId)
            clicks[videoId] = TimeSource.Monotonic.markNow()
            while (clicks.size > KEPT) clicks.remove(clicks.keys.first())
        }
        emit(Stage(videoId, "click", 0, detail))
    }

    /** A stage of [videoId]'s start, timed from its click. */
    fun mark(videoId: String, name: String, detail: String = "") {
        if (sink == null && listener == null) return
        val since = locked { clicks[videoId]?.elapsedNow()?.inWholeMilliseconds }
        emit(Stage(videoId, name, since, detail))
    }

    /** A monotonic moment, for a stage that reports how long it took itself. */
    fun now(): TimeMark = TimeSource.Monotonic.markNow()

    /** Milliseconds since [mark]. */
    fun msSince(mark: TimeMark): Long = mark.elapsedNow().inWholeMilliseconds

    private fun emit(stage: Stage) {
        listener?.invoke(stage)
        val out = sink ?: return
        val at = stage.sinceClickMs?.let { "+${it}ms" } ?: "ahead"
        out(stage.videoId, "startup ${stage.videoId} $at ${stage.name}${if (stage.detail.isEmpty()) "" else ": ${stage.detail}"}")
    }

    @Synchronized
    private fun <T> locked(block: () -> T): T = block()

    private const val KEPT = 16
}
