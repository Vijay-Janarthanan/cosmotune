package com.cosmotune.watch.model

data class WatchSkip(val startMs: Long, val endMs: Long, val label: String)

/** A chapter of what plays (a server's chapter list, an MKV's chapters): where it starts and its name. */
data class WatchChapter(val startMs: Long, val title: String)

/** What a copy brings beside its picture: the stretches to skip and its chapters, both in order, and its seek thumbnails. */
data class PlaybackExtras(val skips: List<WatchSkip> = emptyList(), val chapters: List<WatchChapter> = emptyList(), val previews: SeekPreviews? = null)

/**
 * Seek thumbnails (a server's trickplay tiles): [sheets] are images of [columns] x [rows] frames of [frameWidth] x [frameHeight],
 * left to right then top to bottom, one frame every [intervalMs], [frames] in all.
 */
data class SeekPreviews(val sheets: List<String>, val frameWidth: Int, val frameHeight: Int, val columns: Int, val rows: Int,
    val intervalMs: Long, val frames: Int, val headers: Map<String, String> = emptyMap()) {
    data class Frame(val sheet: Int, val column: Int, val row: Int)

    /** The frame shown for [timeMs], held to the frames and sheets there are. */
    fun frameAt(timeMs: Long): Frame {
        val perSheet = (columns * rows).coerceAtLeast(1)
        val last = (frames - 1).coerceAtLeast(0)
        val index = if (intervalMs <= 0) 0 else (timeMs.coerceAtLeast(0) / intervalMs).coerceAtMost(last.toLong()).toInt()
        val sheet = (index / perSheet).coerceAtMost((sheets.size - 1).coerceAtLeast(0))
        val within = (index - sheet * perSheet).coerceAtMost(perSheet - 1)
        return Frame(sheet, within % columns.coerceAtLeast(1), within / columns.coerceAtLeast(1))
    }
}
