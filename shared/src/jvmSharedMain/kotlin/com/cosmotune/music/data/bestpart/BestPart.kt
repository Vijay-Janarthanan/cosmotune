package com.cosmotune.music.data.bestpart

import com.cosmotune.music.data.innertube.Innertube
import com.cosmotune.music.data.lyrics.LyricLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** YouTube's "Most replayed" curve for a video: how much each [segmentMs] stretch of it is replayed, 0..1, start to end. */
data class Heatmap(val segmentMs: Long, val intensity: List<Float>) {
    val durationMs: Long get() = segmentMs * intensity.size
}

/**
 * Where a song's best part starts (the player's Best part button), from what listeners do with it and from its words, never from
 * its audio. Adapted from upstream 1.10's `BestPart` (GPL-3.0, see NOTICE.md).
 *
 * The hottest moment of the curve is no use alone: a curve mostly falls from start to end as people drift off, so its maximum is
 * nearly always the first seconds. The best part is the biggest bump above that falling trend; its start is where the heat begins
 * to climb towards it, pulled back to the start of a lyric line when one is close. With no curve, the first time the chorus (the
 * most repeated lyric) comes round stands in. With neither, there is no best part and no button.
 */
object BestPart {
    /** Nothing in a song's opening seconds counts: everyone "replays" the start. */
    const val SKIP_HEAD_MS = 5_000L

    /** A best part needs some song left after it. */
    const val SKIP_TAIL_MS = 30_000L

    /** Interludes and skits have no best part to skip to. */
    const val MIN_SONG_MS = 60_000L

    private const val MIN_MARKERS = 10
    private const val MIN_RISE = 0.08f
    private const val MIN_LIFT = 0.03f
    private const val SMOOTH_HALF_MS = 4_500.0
    private const val TREND_HALF_MS = 45_000.0
    private const val TREND_FLOOR = 0.05f

    /** The heat markers in a youtube.com `next` answer, or null when the video has none (too few views to chart). */
    fun parseHeatmap(answer: JsonElement): Heatmap? {
        val entity = findAll(answer, "macroMarkersListEntity").filterIsInstance<JsonObject>()
            .firstOrNull { (it["markersList"] as? JsonObject)?.str("markerType") == "MARKER_TYPE_HEATMAP" } ?: return null
        val markers = ((entity["markersList"] as? JsonObject)?.get("markers") as? JsonArray)?.mapNotNull { element ->
            val marker = element as? JsonObject ?: return@mapNotNull null
            val start = marker.str("startMillis")?.toLongOrNull() ?: return@mapNotNull null
            val length = marker.str("durationMillis")?.toLongOrNull() ?: return@mapNotNull null
            val heat = marker.str("intensityScoreNormalized")?.toFloatOrNull() ?: return@mapNotNull null
            Triple(start, length, heat.coerceIn(0f, 1f))
        }?.sortedBy { it.first } ?: return null
        if (markers.size < MIN_MARKERS) return null
        // Equal segments (a hundredth of the video each); the median, in case the last one is cut short.
        val segment = markers.map { it.second }.sorted()[markers.size / 2]
        if (segment <= 0) return null
        return Heatmap(segment, markers.map { it.third })
    }

    /** Where the most replayed stretch begins for a song [durationMs] long (the curve's own length if 0); null when nothing stands out. */
    fun peakMs(heat: Heatmap, durationMs: Long = 0): Long? {
        val duration = durationMs.takeIf { it > 0 } ?: heat.durationMs
        val n = heat.intensity.size
        if (duration < MIN_SONG_MS || n < MIN_MARKERS) return null
        val seg = heat.segmentMs
        val half = (SMOOTH_HALF_MS / seg).roundToInt().coerceAtLeast(1)
        val smooth = FloatArray(n) { i ->
            val range = (i - half).coerceAtLeast(0)..(i + half).coerceAtMost(n - 1)
            range.sumOf { heat.intensity[it].toDouble() }.toFloat() / (range.last - range.first + 1)
        }
        val wide = (TREND_HALF_MS / seg).roundToInt().coerceAtLeast(3)
        val trend = FloatArray(n) { i ->
            // The opening segment is left out: its heat is everyone pressing play.
            val window = ((i - wide).coerceAtLeast(1)..(i + wide).coerceAtMost(n - 1)).map { smooth[it] }.sorted()
            window[window.size / 2]
        }
        fun rise(i: Int) = smooth[i] / trend[i].coerceAtLeast(TREND_FLOOR) - 1f
        val head = maxOf(SKIP_HEAD_MS, 2 * seg)
        val candidates = (0 until n).filter { i -> i * seg >= head && i * seg <= duration - SKIP_TAIL_MS }
        if (candidates.isEmpty()) return null
        val peak = candidates.maxBy { rise(it) }
        if (rise(peak) < MIN_RISE || smooth[peak] - trend[peak] < MIN_LIFT) return null
        // Back from the peak to where the heat starts climbing towards it: half the peak's rise.
        val floor = rise(peak) * 0.5f
        var start = peak
        while (start - 1 in candidates && rise(start - 1) >= floor) start--
        // The smoothed curve climbs gradually; the raw segments say where it jumps.
        val jump = (start..peak).firstOrNull { heat.intensity[it] / trend[it].coerceAtLeast(TREND_FLOOR) - 1f >= floor } ?: start
        return jump * seg
    }

    /** The first time the chorus comes round in time-synced [lines]: the earliest line of the most repeated lyric, walked back over its block. */
    fun chorusMs(lines: List<LyricLine>, durationMs: Long): Long? {
        if (durationMs in 1 until MIN_SONG_MS) return null
        val timed = lines.filter { it.timeMs >= 0 && !it.isGap && it.text.isNotBlank() }
        if (timed.size < 4) return null
        val keys = timed.map { line -> line.text.lowercase().filter { it.isLetterOrDigit() } }
        val counts = keys.filter { it.length >= 4 }.groupingBy { it }.eachCount()
        val most = counts.values.maxOrNull() ?: return null
        if (most < 2) return null
        val limit = if (durationMs > 0) durationMs - SKIP_TAIL_MS else Long.MAX_VALUE
        val first = keys.indices.firstOrNull { i -> counts[keys[i]] == most && timed[i].timeMs in SKIP_HEAD_MS..limit } ?: return null
        var start = first
        while (start - 1 >= 0) {
            val count = counts[keys[start - 1]] ?: 0
            if (count < 2 || count < most - 1 || timed[start - 1].timeMs < SKIP_HEAD_MS || first - start >= 4) break
            start--
        }
        return timed[start].timeMs
    }

    /** Pulls [targetMs] back to a natural start: a lyric line beginning near it, else a second before it, so it never opens mid-word. */
    fun snap(targetMs: Long, lineStarts: List<Long>): Long {
        lineStarts.filter { it in (targetMs - 4_000)..(targetMs + 1_500) }
            .minByOrNull { abs(it - targetMs) + if (it > targetMs) 1_500 else 0 }
            ?.let { return (it - 250).coerceAtLeast(0) }
        return (targetMs - 1_000).coerceAtLeast(0)
    }

    /** The best part of a song [durationMs] long: from its [heat] curve if it has one, else the chorus in its synced [lines], else null. */
    fun pick(heat: Heatmap?, lines: List<LyricLine>, durationMs: Long): Long? {
        val starts = lines.filter { it.timeMs >= 0 && !it.isGap }.map { it.timeMs }
        return (heat?.let { peakMs(it, durationMs) } ?: chorusMs(lines, durationMs))?.let { snap(it, starts) }
    }

    // ---- The curve, asked for once a song and kept for the run ----

    private val heats = ConcurrentHashMap<String, Heatmap>()
    private val none = ConcurrentHashMap.newKeySet<String>()

    /** Whether [id] is a YouTube video id rather than a file's or an add-on's key: only those have a curve. */
    fun isYouTubeId(id: String): Boolean = YOUTUBE_ID.matches(id)

    /**
     * [videoId]'s heat curve from youtube.com, or null (no curve, not a YouTube video, offline). A video with none is remembered for
     * the run; a failed request is not, so the next song change asks again.
     */
    suspend fun heatmapFor(videoId: String): Heatmap? {
        if (!isYouTubeId(videoId) || videoId in none) return null
        heats[videoId]?.let { return it }
        val heat = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val answer = withTimeoutOrNull(8_000L) {
                try { Innertube.webNext(videoId) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            } ?: return@withContext null to false
            parseHeatmap(answer) to true
        }.let { (curve, answered) -> if (!answered) return null else curve }
        if (heats.size > 500) heats.clear()
        if (heat == null) none += videoId else heats[videoId] = heat
        return heat
    }

    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")

    private fun findAll(element: JsonElement, key: String): List<JsonElement> = buildList {
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> if (k == key) add(v) else walk(v) }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(element)
    }

    /** A string or number under [key], as text: YouTube sends the times as strings and the heat as a number. */
    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
