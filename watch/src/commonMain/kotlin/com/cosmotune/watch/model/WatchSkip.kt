package com.cosmotune.watch.model

data class WatchSkip(val startMs: Long, val endMs: Long, val label: String)

/** A chapter of what plays (a server's chapter list, an MKV's chapters): where it starts and its name. */
data class WatchChapter(val startMs: Long, val title: String)

/** What a copy brings beside its picture: the stretches to skip and its chapters, both in order. */
data class PlaybackExtras(val skips: List<WatchSkip> = emptyList(), val chapters: List<WatchChapter> = emptyList())
