package com.cosmotune.video.player

import com.cosmotune.video.model.Chapter

/** Where the next and previous chapter keys and the "Next chapter" button go (Shift+Right and Shift+Left on a keyboard). */
object ChapterSkip {
    /** Back within this much of a chapter's start goes to the chapter before it, as a music player's Previous does. */
    const val RESTART_SLACK_MS = 3_000L

    /** The start of the chapter after [positionMs], or null in the last one. */
    fun next(chapters: List<Chapter>, positionMs: Long): Long? =
        chapters.map { it.startMs }.sorted().firstOrNull { it > positionMs + 500 }

    /** The start of the chapter playing now, or of the one before it when [positionMs] is near its start; null with no chapters. */
    fun previous(chapters: List<Chapter>, positionMs: Long): Long? {
        val starts = chapters.map { it.startMs }.sorted()
        if (starts.isEmpty()) return null
        val current = starts.lastOrNull { it <= positionMs } ?: return 0L
        if (positionMs - current > RESTART_SLACK_MS) return current
        return starts.lastOrNull { it < current } ?: current
    }
}
