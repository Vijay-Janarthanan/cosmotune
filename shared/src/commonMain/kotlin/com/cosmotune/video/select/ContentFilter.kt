package com.cosmotune.video.select

import com.cosmotune.video.model.Comment
import com.cosmotune.video.model.VideoItem

/**
 * The listener's own filter (Settings, Content, Filters): videos whose title holds a hidden word, videos of a hidden channel, and
 * comments with a hidden word, by a hidden channel or with fewer likes than [commentMinLikes] are left out of Home, the feed,
 * search, Up next, Shorts and the comments. A word matches as a whole word, any case; a word written `/like this/` is a regular
 * expression (one that does not compile matches nothing).
 */
class ContentFilter(words: Collection<String>, private val channels: Set<String>, private val commentMinLikes: Long = 0) {
    private val words: List<String> = words.toList()
    private val patterns: List<Regex> = words.mapNotNull { patternOf(it) }

    /** Whether [other] hides the same things. */
    fun sameAs(other: ContentFilter): Boolean = words == other.words && channels == other.channels && commentMinLikes == other.commentMinLikes

    /** Whether nothing is hidden: the lists pass through untouched. */
    val isEmpty: Boolean get() = patterns.isEmpty() && channels.isEmpty() && commentMinLikes <= 0

    fun keep(item: VideoItem): Boolean {
        if (item.channelId != null && item.channelId in channels) return false
        return patterns.none { it.containsMatchIn(item.title) }
    }

    /** Whether a channel's own row (a search result) stays. */
    fun keepChannel(channelId: String): Boolean = channelId !in channels

    fun keepComment(comment: Comment): Boolean {
        if (comment.pinned || comment.byCreator) return true
        if (comment.authorChannelId != null && comment.authorChannelId in channels) return false
        if (commentMinLikes > 0 && (comment.likeCount ?: 0) < commentMinLikes) return false
        return patterns.none { it.containsMatchIn(comment.text) || it.containsMatchIn(comment.authorName) }
    }

    /** [items] without what this filter hides; [item] gives the video of an entry, or null for one that is no video (kept). */
    fun <T> filter(items: List<T>, item: (T) -> VideoItem?): List<T> =
        if (isEmpty || items.isEmpty()) items else items.filter { entry -> item(entry)?.let(::keep) ?: true }

    companion object {
        val NONE = ContentFilter(emptyList(), emptySet())

        /** The words as the listener typed them, trimmed, blanks and repeats (any case) left out, at most [MAX_WORDS]. */
        fun cleanWords(words: Collection<String>): List<String> =
            words.map { it.trim() }.filter { it.isNotEmpty() && it.length <= 200 }.distinctBy { it.lowercase() }.take(MAX_WORDS)

        /** Whether [word] is written as a regular expression that does not compile (the settings say so under it). */
        fun isBrokenPattern(word: String): Boolean = isRegex(word) && patternOf(word) == null

        private fun isRegex(word: String) = word.length > 2 && word.startsWith('/') && word.endsWith('/')

        private fun patternOf(raw: String): Regex? {
            val word = raw.trim()
            if (word.isEmpty()) return null
            if (isRegex(word)) return runCatching { Regex(word.substring(1, word.length - 1), RegexOption.IGNORE_CASE) }.getOrNull()
            val escaped = Regex.escape(word)
            // a whole word where the word starts and ends with a letter or digit; anything else (an emoji, "#tag") anywhere
            val start = if (word.first().isLetterOrDigit()) "(?<![\\p{L}\\p{N}])" else ""
            val end = if (word.last().isLetterOrDigit()) "(?![\\p{L}\\p{N}])" else ""
            return Regex(start + escaped + end, RegexOption.IGNORE_CASE)
        }

        const val MAX_WORDS = 200
    }
}

/** The listener's filter as these settings hold it. */
fun com.cosmotune.video.settings.VideoSettings.contentFilter(): ContentFilter =
    if (hiddenWords.isEmpty() && hiddenChannels.isEmpty() && commentMinLikes <= 0) ContentFilter.NONE
    else ContentFilter(hiddenWords, hiddenChannels.keys, commentMinLikes)
