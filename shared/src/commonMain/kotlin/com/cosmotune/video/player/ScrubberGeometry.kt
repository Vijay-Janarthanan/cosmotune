package com.cosmotune.video.player

import com.cosmotune.video.model.Chapter
import com.cosmotune.video.model.Storyboard
import com.cosmotune.video.segments.SegmentAction
import com.cosmotune.video.segments.SkipSegment

/**
 * Where a time falls in a storyboard (spec 6.5: the 160 x 90 frame in the scrubber's bubble): the sheet image ([sheet], an index into
 * `Storyboard.urls`), the frame's [column] and [row] in that sheet, and the frame's [index] in the whole storyboard.
 */
data class StoryboardFrame(val sheet: Int, val column: Int, val row: Int, val index: Int)

/**
 * The minimal scrubber's arithmetic (spec 6.5; pure): times as fractions of the line, SponsorBlock's segments as coloured ranges,
 * the 2 dp gaps at chapter starts, the chapter and the storyboard frame of a time while dragging, and the time as the chrome writes
 * it ("1:21", "1:02:03"). Every fraction is clamped to 0..1.
 */
object ScrubberGeometry {
    /** [timeMs] as a fraction of [durationMs], clamped to 0..1; 0 when there is no duration. */
    fun fraction(timeMs: Long, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (timeMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)

    /**
     * The segments drawn over the line (spec 6.5, 7.1): (start, end, colour) with the fractions clamped to 0..1, sorted by start
     * then end, the colour the category's ARGB (`SegmentCategory.colorArgb`). A highlight (`POI`) is a mark (start = end); a
     * segment that labels the whole video (`FULL`, exclusive access) and a chapter are not drawn. Nothing without a duration.
     */
    fun ranges(segments: List<SkipSegment>, durationMs: Long): List<Triple<Float, Float, Long>> {
        if (durationMs <= 0) return emptyList()
        return segments
            .filter { it.action != SegmentAction.FULL && it.action != SegmentAction.CHAPTER }
            .map {
                val start = fraction(it.startMs, durationMs)
                val end = if (it.action == SegmentAction.POI) start else fraction(it.endMs, durationMs).coerceAtLeast(start)
                Triple(start, end, it.category.colorArgb)
            }
            .sortedWith(compareBy<Triple<Float, Float, Long>> { it.first }.thenBy { it.second })
    }

    /**
     * The chapter starts where the line has its 2 dp gaps (spec 6.5): fractions strictly between 0 and 1 (the first chapter's start at
     * 0 and anything at or past the end make no gap), sorted, each once. Nothing without a duration.
     */
    fun chapterStarts(chapters: List<Chapter>, durationMs: Long): List<Float> {
        if (durationMs <= 0) return emptyList()
        return chapters
            .filter { it.startMs in 1 until durationMs }
            .map { fraction(it.startMs, durationMs) }
            .distinct()
            .sorted()
    }

    /** The index in [chapters] of the chapter playing at [timeMs] (the last one to start at or before it), or -1 when none has started. */
    fun chapterIndexAt(chapters: List<Chapter>, timeMs: Long): Int {
        var best = -1
        for (i in chapters.indices) {
            val c = chapters[i]
            if (c.startMs <= timeMs && (best < 0 || c.startMs >= chapters[best].startMs)) best = i
        }
        return best
    }

    /**
     * The storyboard frame of [timeMs] (spec 6.5): one frame every `intervalMs`, clamped to the frames there are, `columns x rows`
     * frames per sheet, left to right then top to bottom; never a sheet beyond the last address. A broken storyboard (no interval, no
     * columns, no frames) gives the first frame.
     */
    fun storyboardFrame(sb: Storyboard, timeMs: Long): StoryboardFrame {
        val columns = sb.columns.coerceAtLeast(1)
        val rows = sb.rows.coerceAtLeast(1)
        val last = (sb.totalFrames - 1).coerceAtLeast(0)
        val index = if (sb.intervalMs <= 0) 0 else (timeMs.coerceAtLeast(0) / sb.intervalMs).coerceAtMost(last.toLong()).toInt()
        val perSheet = columns * rows
        val sheet = (index / perSheet).coerceAtMost((sb.urls.size - 1).coerceAtLeast(0))
        val within = index - sheet * perSheet
        // A sheet clamped to the last address keeps the frame inside that sheet.
        val inSheet = within.coerceAtMost(perSheet - 1)
        return StoryboardFrame(sheet = sheet, column = inSheet % columns, row = inSheet / columns, index = index)
    }

    /** [ms] as the chrome writes a time: "0:05", "1:21", "1:02:03" (whole seconds, rounded down; negative times are 0). */
    fun timeLabel(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val ss = s.toString().padStart(2, '0')
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
    }
}
