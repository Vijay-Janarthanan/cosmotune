package com.cosmotune.reading.data

import org.jsoup.nodes.Element

/** One piece of an EPUB 3 recorded narration: the words ([fragment], an element id in the chapter) and the audio that speaks them. */
data class ReadingOverlayClip(val fragment: String, val audio: String, val beginMs: Long, val endMs: Long)

/** A chapter's recorded narration (its SMIL media overlay): the clips in reading order and the audio files they use, in order. */
data class ReadingOverlay(val clips: List<ReadingOverlayClip>) {
    val audio: List<String> get() = clips.map { it.audio }.distinct()
    /** The clip being spoken at [positionMs] into [audioHref], or null between clips. */
    fun clipAt(audioHref: String, positionMs: Long): Int? =
        clips.indexOfFirst { it.audio == audioHref && positionMs >= it.beginMs && (it.endMs <= it.beginMs || positionMs < it.endMs) }.takeIf { it >= 0 }
    /** The first clip at or after the element [fragment], for starting from a place on the page. */
    fun clipFor(fragment: String?): Int = fragment?.let { id -> clips.indexOfFirst { it.fragment == id }.takeIf { it >= 0 } } ?: 0
}

/** Reading EPUB 3 media overlays (SMIL 3): par elements pairing a text fragment with an audio clip. */
internal object ReadingOverlays {
    /**
     * The clips of [smil] (at [smilPath] in the package), for the chapter at [chapterPath]: text sources outside that chapter are
     * left out, audio sources are resolved to package paths by [resolve].
     */
    fun parse(smil: Element, smilPath: String, chapterPath: String, resolve: (String, String) -> String): ReadingOverlay {
        val clips = smil.getAllElements().filter { it.local() == "par" }.mapNotNull { par ->
            val text = par.children().firstOrNull { it.local() == "text" }?.attr("src") ?: return@mapNotNull null
            val audio = par.children().firstOrNull { it.local() == "audio" } ?: return@mapNotNull null
            val target = runCatching { resolve(smilPath, text) }.getOrNull() ?: return@mapNotNull null
            if (target.substringBefore('#') != chapterPath) return@mapNotNull null
            val fragment = target.substringAfter('#', "").ifBlank { return@mapNotNull null }
            val source = runCatching { resolve(smilPath, audio.attr("src")).substringBefore('#') }.getOrNull() ?: return@mapNotNull null
            val begin = clock(audio.attr("clipBegin")) ?: 0L
            val end = clock(audio.attr("clipEnd")) ?: 0L
            ReadingOverlayClip(fragment, source, begin, end)
        }
        return ReadingOverlay(clips.take(50_000))
    }

    /** A SMIL clock value in milliseconds: "1:02:03.5", "02:03.5", "3.5s", "1500ms", "2min", "1h" or a bare number of seconds. */
    fun clock(value: String): Long? {
        val text = value.trim().ifBlank { return null }
        if (':' in text) {
            val parts = text.split(':').map { it.toDoubleOrNull() ?: return null }
            if (parts.size !in 2..3) return null
            return (parts.fold(0.0) { total, part -> total * 60 + part } * 1000).toLong()
        }
        val match = Regex("""^([0-9]*\.?[0-9]+)\s*(h|min|s|ms)?$""").find(text) ?: return null
        val number = match.groupValues[1].toDouble()
        return (number * when (match.groupValues[2]) { "h" -> 3_600_000.0; "min" -> 60_000.0; "ms" -> 1.0; else -> 1000.0 }).toLong()
    }

    private fun Element.local() = tagName().substringAfter(':')
}
