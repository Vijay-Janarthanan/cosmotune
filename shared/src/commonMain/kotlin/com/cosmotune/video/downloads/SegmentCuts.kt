package com.cosmotune.video.downloads

import com.cosmotune.video.model.Chapter
import com.cosmotune.video.segments.SegmentAction
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.segments.SkipSegment

/** A stretch of a video in milliseconds, from [startMs] up to (not including) [endMs]. */
data class CutRange(val startMs: Long, val endMs: Long)

/**
 * Cutting SponsorBlock segments out of a download ("Cut skipped parts out of downloads"): which stretches go, where a cut
 * may really start and end (a picture can only start again on a keyframe), and how the times of what stays move with it
 * (chapters, subtitles, the segments left in the sidecar).
 */
object SegmentCuts {
    /** The stretches to cut: segments that skip, in the [skipped] categories, merged where they touch or overlap. */
    fun ranges(segments: List<SkipSegment>, skipped: Set<SegmentCategory>): List<CutRange> {
        val wanted = segments.filter { it.action == SegmentAction.SKIP && it.category in skipped && it.endMs > it.startMs }
            .map { CutRange(it.startMs.coerceAtLeast(0), it.endMs) }
            .sortedBy { it.startMs }
        val out = ArrayList<CutRange>()
        for (range in wanted) {
            val last = out.lastOrNull()
            if (last != null && range.startMs <= last.endMs) out[out.size - 1] = CutRange(last.startMs, maxOf(last.endMs, range.endMs))
            else out += range
        }
        return out
    }

    /**
     * The cuts as they can be made in a file whose reference track (the picture, else the sound) has samples at [timesMs]
     * (in order) with [sync] marking the keyframes (null: every sample is one) and ends at [endMs]: each cut starts at the
     * first sample at or after its start and ends at the first keyframe at or after its end (or the end of the file); a cut
     * whose keyframe is more than [MAX_OVERSHOOT_MS] past its end is left out.
     */
    fun snap(timesMs: LongArray, sync: BooleanArray?, ranges: List<CutRange>, endMs: Long): List<CutRange> {
        val out = ArrayList<CutRange>()
        for (range in ranges) {
            val s = timesMs.indexOfFirst { it >= range.startMs }
            if (s < 0) continue
            var e = s
            while (e < timesMs.size && (timesMs[e] < range.endMs || (sync != null && !sync[e]))) e++
            val start = timesMs[s]
            val end = if (e < timesMs.size) timesMs[e] else endMs
            // a keyframe far past the segment's end would take what the listener wants to keep: that cut is not made
            if (end > start && end - range.endMs <= MAX_OVERSHOOT_MS) out += CutRange(start, end)
        }
        return out
    }

    /** How far past a segment's end a cut may run to reach a keyframe. */
    const val MAX_OVERSHOOT_MS = 10_000L

    /** Whether [ms] falls inside one of [cuts]. */
    fun isCut(ms: Long, cuts: List<CutRange>): Boolean = cuts.any { ms >= it.startMs && ms < it.endMs }

    /** Where [ms] of the whole video lands once [cuts] are gone (a time inside a cut lands where the cut was). */
    fun map(ms: Long, cuts: List<CutRange>): Long {
        var removed = 0L
        for (cut in cuts) {
            if (ms >= cut.endMs) removed += cut.endMs - cut.startMs
            else if (ms > cut.startMs) removed += ms - cut.startMs
        }
        return ms - removed
    }

    /** How long the cuts are together. */
    fun total(cuts: List<CutRange>): Long = cuts.sumOf { it.endMs - it.startMs }

    /** The chapters at their new times; of chapters that now start together only the last stays (the others were cut). */
    fun chapters(chapters: List<Chapter>, cuts: List<CutRange>): List<Chapter> =
        chapters.sortedBy { it.startMs }.map { it.copy(startMs = map(it.startMs, cuts)) }.asReversed().distinctBy { it.startMs }.asReversed()

    /** The cues at their new times; a cue wholly inside a cut goes. */
    fun cues(cues: List<VttCue>, cuts: List<CutRange>): List<VttCue> =
        cues.map { VttCue(map(it.startMs, cuts), map(it.endMs, cuts), it.text) }.filter { it.endMs > it.startMs }

    /** The segments that were not cut (not among the [removed] ids), at their new times. */
    fun remaining(segments: List<SkipSegment>, removed: Set<String>, cuts: List<CutRange>): List<SkipSegment> =
        segments.filterNot { it.uuid in removed }
            .map { it.copy(startMs = map(it.startMs, cuts), endMs = map(it.endMs, cuts), videoDurationMs = it.videoDurationMs?.let { d -> d - total(cuts) }) }
            .filter { it.endMs > it.startMs || it.action == SegmentAction.POI }

    /** [cues] as a WebVTT file. */
    fun vtt(cues: List<VttCue>): String = buildString {
        append("WEBVTT\n\n")
        for (cue in cues) append(time(cue.startMs)).append(" --> ").append(time(cue.endMs)).append('\n').append(cue.text).append("\n\n")
    }

    private fun time(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1_000 % 60
        return "${two(h)}:${two(m)}:${two(s)}.${(ms % 1_000).toString().padStart(3, '0')}"
    }

    private fun two(v: Long) = v.toString().padStart(2, '0')
}
