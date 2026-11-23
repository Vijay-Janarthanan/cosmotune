package com.cosmotune.media.comments

/** A listener's comment that names a moment in the song ("2:14 this part"), for the dots on the waveform seek line. */
data class TimedComment(val atMs: Long, val text: String, val author: String)

/**
 * The timed comments on a song's video: the first page of its top comments, each one that names a moment inside the song
 * kept once, at the first moment it names. Nothing is posted and nothing needs a sign-in. The comments themselves come from
 * [fetch], which Video mode sets when the app starts (`VideoTimedComments.install`); without it a song has none.
 */
object TimedComments {
    /** A video's top comments as (text, author, likes), or null when they cannot be read. */
    @Volatile var fetch: (suspend (videoId: String) -> List<Triple<String, String, Long>>?)? = null

    private const val MAX = 40
    private const val MAX_TEXT = 140
    private val stamp = Regex("""(?<![\d:])(?:(\d{1,2}):)?(\d{1,2}):([0-5]\d)(?![\d:])""")
    private val cache = object : LinkedHashMap<String, List<TimedComment>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TimedComment>>?) = size > 64
    }

    /** [videoId]'s timed comments within [durationMs] (0 = unknown), in order; empty when there are none or they cannot be read. */
    suspend fun forSong(videoId: String, durationMs: Long): List<TimedComment> {
        synchronized(cache) { cache[videoId] }?.let { return within(it, durationMs) }
        val read = fetch ?: return emptyList()
        val comments = runCatching { read(videoId) }.getOrNull() ?: return emptyList()
        val found = pick(comments)
        synchronized(cache) { cache[videoId] = found }
        return within(found, durationMs)
    }

    /** The moment a comment names, in ms, or null when it names none. */
    fun firstMoment(text: String): Long? = stamp.find(text)?.let { match ->
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        ((hours * 60 + minutes) * 60 + seconds) * 1000
    }

    /** From (text, author, likes): the comments that name a moment, the best liked first when two land within two seconds. */
    fun pick(comments: List<Triple<String, String, Long>>): List<TimedComment> {
        val timed = comments.mapNotNull { (text, author, likes) ->
            val at = firstMoment(text) ?: return@mapNotNull null
            Triple(TimedComment(at, tidy(text), author), likes, at)
        }.sortedByDescending { it.second }
        val kept = ArrayList<TimedComment>()
        for ((comment) in timed) {
            if (kept.size >= MAX) break
            if (kept.none { kotlin.math.abs(it.atMs - comment.atMs) < 2_000 }) kept += comment
        }
        return kept.sortedBy { it.atMs }
    }

    private fun within(comments: List<TimedComment>, durationMs: Long) =
        if (durationMs <= 0) comments else comments.filter { it.atMs < durationMs }

    private fun tidy(text: String): String {
        val one = text.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= MAX_TEXT) one else one.take(MAX_TEXT - 1).trimEnd() + "…"
    }
}
